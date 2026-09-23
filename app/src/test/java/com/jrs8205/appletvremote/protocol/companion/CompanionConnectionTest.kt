package com.jrs8205.appletvremote.protocol.companion

import com.jrs8205.appletvremote.protocol.crypto.Ed25519KeyPair
import com.jrs8205.appletvremote.protocol.crypto.SecureRandomSource
import com.jrs8205.appletvremote.protocol.pairing.ControllerIdentity
import com.jrs8205.appletvremote.protocol.pairing.Credentials
import com.jrs8205.appletvremote.protocol.pairing.FakeAccessory
import com.jrs8205.appletvremote.protocol.pairing.PairSetup
import com.jrs8205.appletvremote.protocol.pairing.PairVerify
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CompanionConnectionTest {

    private val identity = ControllerIdentity("3b1a0c2e-7d4f-4a2b-9c1d-5e6f7a8b9c0d", Ed25519KeyPair.generate(SecureRandomSource), "Remote")
    private lateinit var accessory: FakeAccessory
    private lateinit var credentials: Credentials
    private lateinit var tv: FakeAppleTv

    @Before
    fun setUp() {
        accessory = FakeAccessory()
        val setup = PairSetup(identity, SecureRandomSource)
        val m4 = accessory.handleSetupM3(setup.m3(accessory.handleSetupM1(setup.m1()), "3939"))
        credentials = setup.finish(accessory.handleSetupM5(setup.m5(m4)))
        tv = FakeAppleTv(accessory)
    }

    @After
    fun tearDown() = tv.close()

    private fun connection(timeoutMs: Long = 5000) =
        CompanionConnection(PlainSocketConnector, "127.0.0.1", tv.port, requestTimeoutMs = timeoutMs)

    private suspend fun verified(timeoutMs: Long = 5000): CompanionConnection {
        val connection = connection(timeoutMs)
        connection.open()
        val verify = PairVerify(credentials, SecureRandomSource)
        val m2 = connection.pairingExchange(FrameType.PV_START, verify.m1(), FrameType.PV_NEXT)
        val m4 = connection.pairingExchange(FrameType.PV_NEXT, verify.m3(m2), FrameType.PV_NEXT)
        connection.installCipher(verify.finish(m4))
        return connection
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    @Test
    fun verifiesThenExchangesEncryptedRequests() = test {
        val connection = verified()
        val reply = connection.request("_systemInfo", mapOf("_cf" to 512L, "name" to "Remote"))
        assertEquals(emptyMap<Any, Any>(), reply)
        val recorded = tv.awaitMessage("_systemInfo")
        assertEquals(2L, recorded.messageType)
        assertEquals(512L, recorded.content["_cf"])
        assertEquals("Remote", recorded.content["name"])
        assertNotNull(recorded.xid)
        assertTrue(connection.isOpen)
        connection.close()
        assertFalse(connection.isOpen)
    }

    @Test
    fun responsesAreMatchedByXidEvenWhenOutOfOrder() = test {
        tv.responder = { name, _ -> mapOf("echo" to name) }
        tv.holdResponseFor = "first"
        val connection = verified()
        val first = async(Dispatchers.IO) { connection.request("first") }
        tv.awaitMessage("first")
        val second = async(Dispatchers.IO) { connection.request("second") }
        assertEquals("second", second.await()["echo"])
        assertEquals("first", first.await()["echo"])
        connection.close()
    }

    @Test
    fun xidsStartBelow65536AndIncrementPerMessage() = test {
        val connection = verified()
        connection.request("a")
        connection.event("b")
        connection.request("c")
        val xids = listOf(tv.awaitMessage("a"), tv.awaitMessage("b"), tv.awaitMessage("c")).map { it.xid!! }
        assertTrue(xids[0] >= 0 && xids[0] < 65536 + 8)
        assertEquals(xids[0] + 1, xids[1])
        assertEquals(xids[0] + 2, xids[2])
        connection.close()
    }

    @Test
    fun remoteErrorBecomesException() = test {
        tv.errorFor = mapOf("_hidC" to "command not allowed")
        val connection = verified()
        val error = runCatching { connection.request("_hidC", mapOf("_hidC" to 6L)) }.exceptionOrNull()
        assertTrue(error is CompanionException.Remote)
        assertEquals("command not allowed", (error as CompanionException.Remote).remoteMessage)
        assertTrue(connection.isOpen)
        connection.close()
    }

    @Test
    fun timeoutClosesTheConnection() = test {
        tv.responder = { _, _ -> null }
        val connection = verified(timeoutMs = 300)
        val error = runCatching { connection.request("slow") }.exceptionOrNull()
        assertTrue("got $error", error is CompanionException.Timeout)
        assertFalse(connection.isOpen)
        val next = runCatching { connection.request("after") }.exceptionOrNull()
        assertTrue("got $next", next is CompanionException.ConnectionClosed)
    }

    @Test
    fun peerClosingFailsPendingRequests() = test {
        tv.responder = { _, _ -> null }
        val connection = verified()
        val pending = async(Dispatchers.IO) { runCatching { connection.request("never") } }
        tv.awaitMessage("never")
        tv.closeConnection()
        val error = pending.await().exceptionOrNull()
        assertTrue("got $error", error is CompanionException.ConnectionClosed)
        assertFalse(connection.isOpen)
        assertNull(connection.awaitClosed())
    }

    @Test
    fun eventsFromTheTvReachTheFlow() = test {
        val connection = verified()
        val collected = async { connection.events.first { it.name == "_iMC" } }
        delay(50)
        tv.sendEvent("_iMC", mapOf("_mcF" to 3L))
        val event = collected.await()
        assertEquals(3L, event.content["_mcF"])
        connection.close()
    }

    @Test
    fun eventsAreSentAsTypeOneWithoutWaiting() = test {
        val connection = verified()
        connection.event("_interest", mapOf("_regEvents" to listOf("_iMC")))
        val recorded = tv.awaitMessage("_interest")
        assertEquals(1L, recorded.messageType)
        assertEquals(listOf("_iMC"), recorded.content["_regEvents"])
        connection.close()
    }

    @Test
    fun requestBeforeOpenFails() = test {
        val connection = connection()
        val error = runCatching { connection.request("x") }.exceptionOrNull()
        assertTrue("got $error", error is CompanionException.ConnectionClosed)
    }

    @Test
    fun closeIsIdempotentAndUnblocksReader() = test {
        val connection = verified()
        val closer = launch { connection.close() }
        closer.join()
        connection.close()
        assertFalse(connection.isOpen)
    }
}
