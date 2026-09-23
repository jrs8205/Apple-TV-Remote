package com.jrs8205.appletvremote.protocol.companion

import com.jrs8205.appletvremote.protocol.crypto.Ed25519KeyPair
import com.jrs8205.appletvremote.protocol.crypto.SecureRandomSource
import com.jrs8205.appletvremote.protocol.pairing.ControllerIdentity
import com.jrs8205.appletvremote.protocol.pairing.FakeAccessory
import com.jrs8205.appletvremote.protocol.pairing.PairingException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingSessionTest {

    private val identity = ControllerIdentity("3b1a0c2e-7d4f-4a2b-9c1d-5e6f7a8b9c0d", Ed25519KeyPair.generate(SecureRandomSource), "Remote")

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    @Test
    fun pairsWithThePinShownOnTheTv() = test {
        FakeAppleTv(FakeAccessory(pin = "3939")).use { tv ->
            val connection = CompanionConnection(PlainSocketConnector, "127.0.0.1", tv.port)
            connection.open()
            val session = PairingSession(connection, identity, SecureRandomSource)
            session.start()
            val credentials = session.finish("3939")
            assertArrayEquals(tv.accessory.accessoryId, credentials.accessoryId)
            assertArrayEquals(tv.accessory.publicKey, credentials.accessoryPublicKey)
            assertEquals(true, tv.accessory.controllerSignatureValid)
            connection.close()
        }
    }

    @Test
    fun wrongPinSurfacesAsWrongPin() = test {
        FakeAppleTv(FakeAccessory(pin = "1111")).use { tv ->
            val connection = CompanionConnection(PlainSocketConnector, "127.0.0.1", tv.port)
            connection.open()
            val session = PairingSession(connection, identity, SecureRandomSource)
            session.start()
            val error = runCatching { session.finish("2222") }.exceptionOrNull()
            assertTrue("got $error", error is PairingException.WrongPin)
            connection.close()
        }
    }
}
