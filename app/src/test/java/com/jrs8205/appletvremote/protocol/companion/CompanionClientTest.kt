package com.jrs8205.appletvremote.protocol.companion

import com.jrs8205.appletvremote.protocol.crypto.Ed25519KeyPair
import com.jrs8205.appletvremote.protocol.crypto.SecureRandomSource
import com.jrs8205.appletvremote.protocol.pairing.ControllerIdentity
import com.jrs8205.appletvremote.protocol.pairing.Credentials
import com.jrs8205.appletvremote.protocol.pairing.FakeAccessory
import com.jrs8205.appletvremote.protocol.pairing.PairSetup
import com.jrs8205.appletvremote.protocol.pairing.PairingException
import com.jrs8205.appletvremote.protocol.textinput.KeyedArchive
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CompanionClientTest {

    private val identity = ControllerIdentity("3b1a0c2e-7d4f-4a2b-9c1d-5e6f7a8b9c0d", Ed25519KeyPair.generate(SecureRandomSource), "Remote")
    private val clientInfo = ClientInfo(name = "Apple TV Remote", model = "Pixel", publicId = "7f000001-aaaa-bbbb-cccc-000000000001")
    private lateinit var accessory: FakeAccessory
    private lateinit var credentials: Credentials
    private lateinit var tv: FakeAppleTv
    private var now = 1_000_000_000L

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

    private fun client(timeoutMs: Long = 5000) = CompanionClient(
        connector = PlainSocketConnector,
        host = "127.0.0.1",
        port = tv.port,
        credentials = credentials,
        clientInfo = clientInfo,
        requestTimeoutMs = timeoutMs,
        attentionTimeoutMs = 500,
        clock = { now },
    )

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    @Test
    fun connectRunsTheHandshakeSequence() = test {
        val client = client()
        client.ensureConnected()
        assertEquals(ConnectionState.Ready, client.state.value)
        val names = tv.messages.map { it.name to it.messageType }
        assertEquals(
            listOf("_systemInfo" to 2L, "_sessionStart" to 2L, "TVRCSessionStart" to 2L, "_tiStart" to 2L, "_interest" to 1L, "_interest" to 1L, "FetchAttentionState" to 2L),
            names,
        )
        val info = tv.awaitMessage("_systemInfo").content
        assertEquals(0L, info["_bf"])
        assertEquals(512L, info["_cf"])
        assertEquals(128L, info["_clFl"])
        assertEquals("cafecafecafe", info["_i"])
        assertArrayEquals(identity.pairingId.toByteArray(), info["_idsID"] as ByteArray)
        assertEquals(clientInfo.publicId, info["_pubID"])
        assertEquals(256L, info["_sf"])
        assertEquals("170.18", info["_sv"])
        assertEquals("Apple TV Remote", info["name"])
        assertEquals("Pixel", info["model"])
        val start = tv.awaitMessage("_sessionStart").content
        assertEquals("com.apple.tvremoteservices", start["_srvT"])
        assertTrue((start["_sid"] as Long) in 0..0xFFFF_FFFFL)
        assertEquals(listOf("_iMC"), tv.awaitMessage("_interest").content["_regEvents"])
        assertEquals(listOf("SystemStatus"), tv.awaitMessage("_interest", skip = 1).content["_regEvents"])
        client.disconnect()
    }

    @Test
    fun ensureConnectedIsIdempotent() = test {
        val client = client()
        client.ensureConnected()
        val count = tv.messages.size
        client.ensureConnected()
        assertEquals(count, tv.messages.size)
        client.disconnect()
    }

    @Test
    fun pressButtonSendsDownThenUp() = test {
        val client = client()
        client.pressButton(HidButton.SELECT)
        val down = tv.awaitMessage("_hidC")
        val up = tv.awaitMessage("_hidC", skip = 1)
        assertEquals(mapOf("_hidC" to 6L, "_hBtS" to 1L), down.content)
        assertEquals(mapOf("_hidC" to 6L, "_hBtS" to 2L), up.content)
        assertEquals(2L, down.messageType)
        client.disconnect()
    }

    @Test
    fun touchStartsTheSurfaceLazilyAndUsesRelativeTimestamps() = test {
        val client = client()
        client.ensureConnected()
        now = 5_000_000_000L
        client.touch(TouchPhase.PRESS, 500, 500)
        now += 7_000_000L
        client.touch(TouchPhase.HOLD, 510, 520)
        val start = tv.awaitMessage("_touchStart")
        assertEquals(2L, start.messageType)
        assertEquals(mapOf("_width" to 1000.0, "_height" to 1000.0, "_tFl" to 0L), start.content)
        val press = tv.awaitMessage("_hidT")
        assertEquals(1L, press.messageType)
        assertEquals(mapOf("_ns" to 0L, "_tFg" to 1L, "_cx" to 500L, "_tPh" to 1L, "_cy" to 500L), press.content)
        val hold = tv.awaitMessage("_hidT", skip = 1)
        assertEquals(7_000_000L, hold.content["_ns"])
        assertEquals(3L, hold.content["_tPh"])
        assertEquals(510L, hold.content["_cx"])
        client.disconnect()
        assertEquals(1, tv.messages.count { it.name == "_touchStart" })
        tv.awaitMessage("_touchStop")
    }

    @Test
    fun mediaAndSkipCommands() = test {
        val client = client()
        client.media(MediaCommand.PLAY)
        client.skip(-10.0)
        assertEquals(mapOf("_mcc" to 1L), tv.awaitMessage("_mcc").content)
        assertEquals(mapOf("_mcc" to 7L, "_skpS" to -10.0), tv.awaitMessage("_mcc", skip = 1).content)
        client.disconnect()
    }

    @Test
    fun incomingEventsAreTyped() = test {
        val client = client()
        client.ensureConnected()
        val media = async { client.events.first { it is CompanionEvent.MediaCapabilitiesChanged } }
        val status = async { client.events.first { it is CompanionEvent.SystemStatusChanged && it.status == SystemStatus.ASLEEP } }
        val keyboard = async { client.events.first { it is CompanionEvent.TextInputStarted } }
        delay(50)
        tv.sendEvent("_iMC", mapOf("_mcF" to 2L))
        tv.sendEvent("SystemStatus", mapOf("state" to 1L))
        tv.sendEvent("_tiStarted", mapOf("_tiD" to byteArrayOf(1, 2, 3)))
        assertEquals(PlayState.PLAYING, (media.await() as CompanionEvent.MediaCapabilitiesChanged).capabilities.playState)
        status.await()
        assertArrayEquals(byteArrayOf(1, 2, 3), (keyboard.await() as CompanionEvent.TextInputStarted).content["_tiD"] as ByteArray)
        client.disconnect()
    }

    @Test
    fun unansweredAttentionStateDoesNotBreakTheConnection() = test {
        tv.responder = { name, _ -> if (name == "FetchAttentionState") null else tv.defaultReply(name) }
        val client = client()
        client.ensureConnected()
        assertEquals(ConnectionState.Ready, client.state.value)
        assertEquals(null, client.fetchAttentionState())
        client.pressButton(HidButton.MENU)
        tv.awaitMessage("_hidC")
        assertEquals(ConnectionState.Ready, client.state.value)
        client.disconnect()
    }

    @Test
    fun rejectedTvRcSessionStartIsIgnored() = test {
        tv.errorFor = mapOf("TVRCSessionStart" to "unsupported")
        val client = client()
        client.ensureConnected()
        assertEquals(ConnectionState.Ready, client.state.value)
        assertEquals(mapOf("ProtocolVersionKey" to "1.2"), tv.awaitMessage("TVRCSessionStart").content)
        client.disconnect()
    }

    @Test
    fun fetchAttentionStateReturnsTheStatus() = test {
        tv.responder = { name, _ -> if (name == "FetchAttentionState") mapOf("state" to 2L) else tv.defaultReply(name) }
        val client = client()
        assertEquals(SystemStatus.SCREENSAVER, client.fetchAttentionState())
        client.disconnect()
    }

    @Test
    fun disconnectSendsTeardownAndCloses() = test {
        val client = client()
        client.ensureConnected()
        val localSid = tv.awaitMessage("_sessionStart").content["_sid"] as Long
        client.disconnect()
        assertEquals(ConnectionState.Disconnected, client.state.value)
        val dereg = tv.messages.filter { it.name == "_interest" && it.content.containsKey("_deregEvents") }
        assertEquals(listOf(listOf("_iMC"), listOf("SystemStatus")), dereg.map { it.content["_deregEvents"] })
        val stop = tv.awaitMessage("_sessionStop").content
        assertEquals("com.apple.tvremoteservices", stop["_srvT"])
        assertEquals((tv.remoteSid shl 32) or localSid, stop["_sid"])
        tv.awaitMessage("_tiStop")
        assertEquals(0, tv.messages.count { it.name == "_touchStop" })
    }

    @Test
    fun connectionLossIsNoticedAndTheNextCommandReconnects() = test {
        val client = client()
        client.ensureConnected()
        tv.closeConnection()
        client.state.first { it == ConnectionState.Disconnected }
        client.pressButton(HidButton.MENU)
        assertEquals(2, tv.connectionCount)
        assertEquals(ConnectionState.Ready, client.state.value)
        assertEquals(2, tv.messages.count { it.name == "_systemInfo" })
        tv.awaitMessage("_hidC")
        client.disconnect()
    }

    @Test
    fun rejectedCredentialsFailTheConnection() = test {
        accessory.errorOnVerify = 2
        val client = client()
        val error = runCatching { client.ensureConnected() }.exceptionOrNull()
        assertTrue("got $error", error is PairingException.CredentialsRejected)
        assertTrue(client.state.value is ConnectionState.Failed)
    }

    @Test
    fun unreachableHostFailsWithConnectionClosed() = test {
        tv.close()
        val client = client()
        val error = runCatching { client.ensureConnected() }.exceptionOrNull()
        assertTrue("got $error", error is CompanionException.ConnectionClosed)
        assertTrue(client.state.value is ConnectionState.Failed)
    }

    @Test
    fun textInputStateComesFromTheStartReply() = test {
        val archive = CompanionClientTest::class.java.getResourceAsStream("/rti/ti_state.bplist")!!.use { it.readBytes() }
        tv.responder = { name, _ -> if (name == "_tiStart") mapOf("_tiD" to archive) else tv.defaultReply(name) }
        val client = client()
        val state = client.textInputState()!!
        assertEquals("Search", state.prompt)
        assertEquals("typed so far", state.currentText)
        assertArrayEquals(ByteArray(16) { (0x10 + it).toByte() }, state.sessionUuid)
        client.disconnect()
    }

    @Test
    fun sendTextRestartsTheSessionThenClearsAndInserts() = test {
        val archive = CompanionClientTest::class.java.getResourceAsStream("/rti/ti_state.bplist")!!.use { it.readBytes() }
        tv.responder = { name, _ -> if (name == "_tiStart") mapOf("_tiD" to archive) else tv.defaultReply(name) }
        val client = client()
        client.ensureConnected()
        client.sendText("hei", replace = true)
        tv.awaitMessage("_tiStop")
        tv.awaitMessage("_tiStart", skip = 1)
        val clear = tv.awaitMessage("_tiC")
        val insert = tv.awaitMessage("_tiC", skip = 1)
        assertEquals(1L, clear.messageType)
        assertEquals(1L, clear.content["_tiV"])
        val clearArchive = clear.content["_tiD"] as ByteArray
        val session = KeyedArchive.resolve(clearArchive, listOf("textOperations", "targetSessionUUID", "NS.uuidbytes")) as ByteArray
        assertArrayEquals(ByteArray(16) { (0x10 + it).toByte() }, session)
        assertEquals("", KeyedArchive.resolve(clearArchive, listOf("textOperations", "textToAssert")))
        val insertArchive = insert.content["_tiD"] as ByteArray
        assertEquals("hei", KeyedArchive.resolve(insertArchive, listOf("textOperations", "keyboardOutput", "insertionText")))
        client.sendText("!", replace = false)
        tv.awaitMessage("_tiC", skip = 2)
        assertEquals(3, tv.messages.count { it.name == "_tiC" })
        client.disconnect()
    }

    @Test
    fun sendTextWithoutAKeyboardSessionFails() = test {
        val client = client()
        val error = runCatching { client.sendText("hei", replace = true) }.exceptionOrNull()
        assertTrue("got $error", error is CompanionException.Protocol)
        client.disconnect()
    }
}
