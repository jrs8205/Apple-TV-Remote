package com.jrs8205.appletvremote.lgtv

import com.jrs8205.appletvremote.protocol.log.ProtocolLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class LgTvException(message: String) : RuntimeException(message)

/**
 * One WebSocket session with an LG webOS TV. Newer sets only accept `wss` on port 3001 with a
 * self-signed certificate, so the socket trusts whatever certificate the configured host presents.
 */
class LgTvClient(
    private val host: String,
    private val log: ProtocolLog = ProtocolLog.None,
) : AutoCloseable {

    private val http = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .sslSocketFactory(trustingContext().socketFactory, TRUST_ALL)
        .hostnameVerifier { _, _ -> true }
        .build()

    private val ids = AtomicInteger(1)
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<LgTvMessages.Incoming>>()
    private val opened = CompletableDeferred<Unit>()
    private val closed = CompletableDeferred<String>()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var pairingPrompted: (() -> Unit)? = null

    /**
     * Connects and registers. With a stored [clientKey] this completes silently; without one the
     * TV shows a prompt, [onPrompt] fires, and the returned key must be stored for next time.
     */
    suspend fun connect(clientKey: String?, onPrompt: () -> Unit = {}, timeoutMs: Long = 60_000): String {
        pairingPrompted = onPrompt
        open()
        val id = "register_${ids.getAndIncrement()}"
        val reply = exchange(id, LgTvMessages.register(id, clientKey), timeoutMs)
        val key = reply.payload?.optString("client-key")?.takeIf { it.isNotEmpty() }
            ?: throw LgTvException("TV did not return a client key (${reply.error ?: reply.type})")
        return key
    }

    suspend fun switchInput(inputId: String) {
        request("ssap://tv/switchInput", mapOf("inputId" to inputId))
    }

    suspend fun turnOff() {
        request("ssap://system/turnOff")
    }

    /** The TV's own MAC addresses when it reports them; wired first. */
    suspend fun macAddresses(): List<String> {
        val info = runCatching { request("ssap://com.webos.service.connectionmanager/getinfo") }.getOrNull() ?: return emptyList()
        return listOf("wiredInfo", "wifiInfo").mapNotNull { info.optJSONObject(it)?.optString("macAddress")?.takeIf { m -> m.isNotEmpty() } }
    }

    suspend fun request(uri: String, payload: Map<String, Any?> = emptyMap(), timeoutMs: Long = 8_000): JSONObject {
        val id = ids.getAndIncrement().toString()
        val reply = exchange(id, LgTvMessages.request(id, uri, payload), timeoutMs)
        val body = reply.payload ?: JSONObject()
        if (reply.type == "error" || (body.has("returnValue") && !body.optBoolean("returnValue", true))) {
            throw LgTvException(reply.error ?: body.optString("errorText").ifEmpty { "$uri failed" })
        }
        return body
    }

    override fun close() {
        socket?.close(1000, null)
        socket = null
    }

    private suspend fun open() {
        if (socket != null) return
        val request = Request.Builder().url("wss://$host:3001/").build()
        socket = http.newWebSocket(request, Listener())
        val result = withTimeoutOrNull(4_000) {
            kotlinx.coroutines.selects.select {
                opened.onAwait { null }
                closed.onAwait { it }
            }
        }
        if (result != null) throw LgTvException("could not reach the TV: $result")
        if (result == null && !opened.isCompleted) throw LgTvException("could not reach the TV: timeout")
    }

    private suspend fun exchange(id: String, message: String, timeoutMs: Long): LgTvMessages.Incoming {
        val waiter = CompletableDeferred<LgTvMessages.Incoming>()
        waiters[id] = waiter
        try {
            (socket ?: throw LgTvException("not connected")).send(message)
            return withTimeoutOrNull(timeoutMs) { waiter.await() } ?: throw LgTvException("no reply from the TV")
        } finally {
            waiters.remove(id)
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val incoming = LgTvMessages.parse(text) ?: return
            val id = incoming.id ?: return
            if (incoming.type == "response" && incoming.payload?.optString("pairingType") == "PROMPT") {
                pairingPrompted?.invoke()
                return
            }
            waiters[id]?.complete(incoming)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            log.log { "LG TV socket failed: $t" }
            closed.complete(t.message ?: t.javaClass.simpleName)
            waiters.values.forEach { it.completeExceptionally(LgTvException("connection lost: ${t.message}")) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.complete("closed $code")
            waiters.values.forEach { it.completeExceptionally(LgTvException("connection closed")) }
        }
    }

    private companion object {
        val TRUST_ALL = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        fun trustingContext(): SSLContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(TRUST_ALL), SecureRandom()) }
    }
}
