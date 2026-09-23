package com.jrs8205.appletvremote.protocol.companion

import com.jrs8205.appletvremote.protocol.crypto.AuthenticationFailedException
import com.jrs8205.appletvremote.protocol.crypto.ChaChaBox
import com.jrs8205.appletvremote.protocol.crypto.Nonce
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameCipherTest {

    private val clientKey = ByteArray(32) { it.toByte() }
    private val serverKey = ByteArray(32) { (100 + it).toByte() }

    @Test
    fun sealsWithCounterNonceAndHeaderAsAad() {
        val cipher = FrameCipher(encryptKey = clientKey, decryptKey = serverKey)
        val frame = cipher.seal(FrameType.E_OPACK, "abc".toByteArray())
        val header = byteArrayOf(0x08, 0x00, 0x00, 0x13)
        assertArrayEquals(header, frame.copyOfRange(0, 4))
        val expectedBody = ChaChaBox(clientKey).seal(Nonce.counter(0), "abc".toByteArray(), header)
        assertArrayEquals(expectedBody, frame.copyOfRange(4, frame.size))
        assertEquals(4 + 3 + 16, frame.size)
    }

    @Test
    fun counterAdvancesPerSealedFrame() {
        val cipher = FrameCipher(encryptKey = clientKey, decryptKey = serverKey)
        cipher.seal(FrameType.E_OPACK, "first".toByteArray())
        val second = cipher.seal(FrameType.E_OPACK, "second".toByteArray())
        val header = second.copyOfRange(0, 4)
        val expected = ChaChaBox(clientKey).seal(Nonce.counter(1), "second".toByteArray(), header)
        assertArrayEquals(expected, second.copyOfRange(4, second.size))
    }

    @Test
    fun peersRoundTripInBothDirections() {
        val client = FrameCipher(encryptKey = clientKey, decryptKey = serverKey)
        val server = FrameCipher(encryptKey = serverKey, decryptKey = clientKey)
        repeat(3) { i ->
            val toServer = client.seal(FrameType.E_OPACK, "ping$i".toByteArray())
            assertArrayEquals("ping$i".toByteArray(), server.open(toServer.copyOfRange(0, 4), toServer.copyOfRange(4, toServer.size)))
            val toClient = server.seal(FrameType.E_OPACK, "pong$i".toByteArray())
            assertArrayEquals("pong$i".toByteArray(), client.open(toClient.copyOfRange(0, 4), toClient.copyOfRange(4, toClient.size)))
        }
    }

    @Test
    fun tamperedBodyIsRejected() {
        val client = FrameCipher(encryptKey = clientKey, decryptKey = serverKey)
        val server = FrameCipher(encryptKey = serverKey, decryptKey = clientKey)
        val frame = client.seal(FrameType.E_OPACK, "abc".toByteArray())
        val body = frame.copyOfRange(4, frame.size).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(AuthenticationFailedException::class.java) { server.open(frame.copyOfRange(0, 4), body) }
    }

    @Test
    fun emptyPayloadIsSentInTheClearWithoutAdvancingTheCounter() {
        val cipher = FrameCipher(encryptKey = clientKey, decryptKey = serverKey)
        assertArrayEquals(byteArrayOf(0x01, 0, 0, 0), cipher.seal(FrameType.NO_OP, ByteArray(0)))
        val next = cipher.seal(FrameType.E_OPACK, "x".toByteArray())
        val expected = ChaChaBox(clientKey).seal(Nonce.counter(0), "x".toByteArray(), next.copyOfRange(0, 4))
        assertArrayEquals(expected, next.copyOfRange(4, next.size))
        assertArrayEquals(ByteArray(0), cipher.open(byteArrayOf(0x01, 0, 0, 0), ByteArray(0)))
    }
}
