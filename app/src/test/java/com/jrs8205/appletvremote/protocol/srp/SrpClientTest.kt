package com.jrs8205.appletvremote.protocol.srp

import com.jrs8205.appletvremote.protocol.crypto.RandomSource
import com.jrs8205.appletvremote.protocol.crypto.SecureRandomSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties

class SrpClientTest {

    private class FixedRandom(private val bytes: ByteArray) : RandomSource {
        override fun nextBytes(count: Int): ByteArray = bytes.copyOf(count)
    }

    private val vector: Properties = Properties().apply {
        SrpClientTest::class.java.getResourceAsStream("/srp/vector-3072-sha512.properties")!!.use { load(it) }
    }

    private fun hex(key: String): ByteArray = vector.getProperty(key).hexToByteArray()

    @Test
    fun reproducesSrptoolsVector() {
        val client = SrpClient(vector.getProperty("username"), vector.getProperty("password"), FixedRandom(hex("a")))
        val proofs = client.computeProofs(hex("salt"), hex("B"))
        assertArrayEquals(hex("A"), proofs.clientPublic)
        assertArrayEquals(hex("M1"), proofs.clientProof)
        assertArrayEquals(hex("K"), client.sessionKey)
        assertTrue(client.verifyServerProof(hex("M2")))
    }

    @Test
    fun rejectsTamperedServerProof() {
        val client = SrpClient("Pair-Setup", "3939", FixedRandom(hex("a")))
        client.computeProofs(hex("salt"), hex("B"))
        val tampered = hex("M2").also { it[3] = (it[3].toInt() xor 0x01).toByte() }
        assertFalse(client.verifyServerProof(tampered))
        assertFalse(client.verifyServerProof(ByteArray(0)))
    }

    @Test
    fun wrongPinProducesDifferentProof() {
        val client = SrpClient("Pair-Setup", "0000", FixedRandom(hex("a")))
        val proofs = client.computeProofs(hex("salt"), hex("B"))
        assertArrayEquals(hex("A"), proofs.clientPublic)
        assertFalse(hex("M1").contentEquals(proofs.clientProof))
    }

    @Test
    fun roundTripsAgainstIndependentServer() {
        val salt = ByteArray(16) { (it * 5 + 1).toByte() }
        val server = TestSrpServer("Pair-Setup", "1234", salt, SecureRandomSource.nextBytes(32))
        val client = SrpClient("Pair-Setup", "1234", SecureRandomSource)
        val proofs = client.computeProofs(salt, server.publicKey)
        assertTrue(server.verifyClientProof(proofs.clientPublic, proofs.clientProof))
        assertArrayEquals(server.sessionKey, client.sessionKey)
        assertTrue(client.verifyServerProof(server.serverProof!!))
    }

    @Test
    fun independentServerRejectsWrongPin() {
        val salt = ByteArray(16) { it.toByte() }
        val server = TestSrpServer("Pair-Setup", "1234", salt, SecureRandomSource.nextBytes(32))
        val client = SrpClient("Pair-Setup", "4321", SecureRandomSource)
        val proofs = client.computeProofs(salt, server.publicKey)
        assertFalse(server.verifyClientProof(proofs.clientPublic, proofs.clientProof))
    }

    @Test
    fun clientPublicIsAlwaysFullGroupLength() {
        repeat(8) {
            val client = SrpClient("Pair-Setup", "1234", SecureRandomSource)
            val proofs = client.computeProofs(ByteArray(16), hex("B"))
            assertEquals(384, proofs.clientPublic.size)
            assertEquals(64, proofs.clientProof.size)
        }
    }

    @Test
    fun privateExponentIsFreshPerClient() {
        val first = SrpClient("Pair-Setup", "1234", SecureRandomSource).computeProofs(ByteArray(16), hex("B"))
        val second = SrpClient("Pair-Setup", "1234", SecureRandomSource).computeProofs(ByteArray(16), hex("B"))
        assertFalse(first.clientPublic.contentEquals(second.clientPublic))
    }

    @Test
    fun retriesWhenPublicWouldHaveLeadingZeroByte() {
        // The first candidate exponent yields a public value shorter than 384 bytes; the client must draw again.
        val shortPublicExponent = hex("aShortPublic")
        var calls = 0
        val random = RandomSource { count ->
            calls++
            if (calls == 1) shortPublicExponent.copyOf(count) else hex("a").copyOf(count)
        }
        val proofs = SrpClient("Pair-Setup", "3939", random).computeProofs(hex("salt"), hex("B"))
        assertEquals(2, calls)
        assertArrayEquals(hex("A"), proofs.clientPublic)
    }

    @Test
    fun rejectsServerPublicThatIsZeroModN() {
        val client = SrpClient("Pair-Setup", "1234", SecureRandomSource)
        assertThrows(SrpException::class.java) { client.computeProofs(ByteArray(16), ByteArray(384)) }
        assertThrows(SrpException::class.java) { client.computeProofs(ByteArray(16), SrpGroup3072.N.toByteArray()) }
    }

    @Test
    fun serverProofBeforeComputeIsAnError() {
        assertThrows(IllegalStateException::class.java) {
            SrpClient("Pair-Setup", "1234", SecureRandomSource).verifyServerProof(ByteArray(64))
        }
    }
}
