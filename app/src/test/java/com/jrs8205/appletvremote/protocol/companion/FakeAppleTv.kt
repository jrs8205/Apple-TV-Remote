package com.jrs8205.appletvremote.protocol.companion

import com.jrs8205.appletvremote.protocol.opack.Opack
import com.jrs8205.appletvremote.protocol.pairing.FakeAccessory
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A minimal Apple TV on localhost: answers pair-setup and pair-verify through [FakeAccessory],
 * switches to encrypted frames after verify, records every OPACK message and replies to requests.
 */
class FakeAppleTv(val accessory: FakeAccessory = FakeAccessory()) : AutoCloseable {

    data class Recorded(val frameType: FrameType, val messageType: Long?, val name: String?, val content: Map<*, *>, val xid: Long?)

    val remoteSid = 0x1234_5678L
    val recorded = CopyOnWriteArrayList<Recorded>()
    val messages: List<Recorded> get() = recorded.filter { it.frameType == FrameType.E_OPACK }
    @Volatile var connectionCount = 0
        private set

    /** Returns the `_c` content to reply with, or null to leave the request unanswered. */
    @Volatile var responder: (name: String, content: Map<*, *>) -> Map<String, Any?>? = { name, _ -> defaultReply(name) }
    @Volatile var errorFor: Map<String, String> = emptyMap()
    @Volatile var holdResponseFor: String? = null

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    @Volatile private var current: Connection? = null
    private val acceptThread: Thread

    init {
        acceptThread = thread(name = "fake-apple-tv", isDaemon = true) {
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    connectionCount++
                    val connection = Connection(socket)
                    current = connection
                    connection.serve()
                }
            } catch (_: IOException) {
            }
        }
    }

    fun defaultReply(name: String): Map<String, Any?> = when (name) {
        "_sessionStart" -> mapOf("_sid" to remoteSid)
        "FetchAttentionState" -> mapOf("state" to 3L)
        else -> emptyMap()
    }

    fun sendEvent(name: String, content: Map<String, Any?>) {
        checkNotNull(current) { "no connection" }.write(FrameType.E_OPACK, mapOf("_i" to name, "_t" to 1L, "_c" to content))
    }

    fun closeConnection() {
        current?.socket?.close()
    }

    fun awaitMessage(name: String, timeoutMs: Long = 5000, skip: Int = 0): Recorded {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            val matches = messages.filter { it.name == name }
            if (matches.size > skip) return matches[skip]
            Thread.sleep(5)
        }
        error("no message named $name within $timeoutMs ms; saw ${messages.map { it.name }}")
    }

    override fun close() {
        current?.socket?.close()
        server.close()
    }

    private inner class Connection(val socket: Socket) {
        private val input = DataInputStream(socket.getInputStream())
        private val output: OutputStream = BufferedOutputStream(socket.getOutputStream())
        private val writeLock = Any()
        @Volatile private var cipher: FrameCipher? = null
        private var held: Pair<Long, Map<String, Any?>>? = null

        fun serve() {
            try {
                while (true) handle(FrameCodec.readFrame(input))
            } catch (_: IOException) {
            } finally {
                socket.close()
            }
        }

        private fun handle(frame: RawFrame) {
            val payload = cipher?.takeIf { frame.payload.isNotEmpty() }?.open(frame.header, frame.payload) ?: frame.payload
            val message = Opack.decode(payload) as Map<*, *>
            when (frame.type) {
                FrameType.PS_START -> write(FrameType.PS_NEXT, accessory.handleSetupM1(message))
                FrameType.PS_NEXT -> {
                    val state = com.jrs8205.appletvremote.protocol.tlv8.Tlv8.decode(message["_pd"] as ByteArray)
                        .getValue(com.jrs8205.appletvremote.protocol.tlv8.TlvType.STATE)[0].toInt()
                    write(FrameType.PS_NEXT, if (state == 3) accessory.handleSetupM3(message) else accessory.handleSetupM5(message))
                }
                FrameType.PV_START -> write(FrameType.PV_NEXT, accessory.handleVerifyM1(message))
                FrameType.PV_NEXT -> {
                    val reply = accessory.handleVerifyM3(message)
                    write(FrameType.PV_NEXT, reply)
                    accessory.lastVerifyKeys?.let { cipher = FrameCipher(encryptKey = it.encryptKey, decryptKey = it.decryptKey) }
                }
                FrameType.E_OPACK -> handleOpack(message)
                else -> Unit
            }
        }

        private fun handleOpack(message: Map<*, *>) {
            val name = message["_i"] as? String
            val messageType = message["_t"] as? Long
            val xid = message["_x"] as? Long
            val content = message["_c"] as? Map<*, *> ?: emptyMap<Any, Any>()
            recorded += Recorded(FrameType.E_OPACK, messageType, name, content, xid)
            if (messageType != 2L || name == null || xid == null) return
            errorFor[name]?.let { error ->
                write(FrameType.E_OPACK, mapOf("_t" to 3L, "_x" to xid, "_em" to error))
                return
            }
            val reply = responder(name, content) ?: return
            if (holdResponseFor == name && held == null) {
                held = xid to reply
                return
            }
            respond(xid, reply)
            held?.let { (heldXid, heldReply) ->
                held = null
                respond(heldXid, heldReply)
            }
        }

        private fun respond(xid: Long, content: Map<String, Any?>) {
            write(FrameType.E_OPACK, mapOf("_t" to 3L, "_x" to xid, "_c" to content))
        }

        fun write(type: FrameType, message: Map<String, Any?>) {
            val plain = Opack.encode(message)
            val bytes = cipher?.seal(type, plain) ?: (FrameCodec.encodeHeader(type, plain.size) + plain)
            synchronized(writeLock) {
                output.write(bytes)
                output.flush()
            }
        }
    }
}
