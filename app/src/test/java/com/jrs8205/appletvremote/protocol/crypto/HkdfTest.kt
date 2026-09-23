package com.jrs8205.appletvremote.protocol.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HkdfTest {

    /** Independent RFC 5869 implementation on top of the JDK's HMAC, used as the oracle. */
    private fun referenceHkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(64) else salt
        val extract = Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(effectiveSalt, "HmacSHA512")) }
        val prk = extract.doFinal(ikm)
        val expand = Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(prk, "HmacSHA512")) }
        var previous = ByteArray(0)
        val output = ArrayList<Byte>()
        var counter = 1
        while (output.size < length) {
            expand.update(previous)
            expand.update(info)
            expand.update(counter.toByte())
            previous = expand.doFinal()
            output.addAll(previous.toList())
            counter++
        }
        return output.take(length).toByteArray()
    }

    @Test
    fun matchesReferenceForPairSetupLabels() {
        val ikm = ByteArray(64) { (it * 3).toByte() }
        val actual = Hkdf.sha512(ikm, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        val expected = referenceHkdf(ikm, "Pair-Setup-Encrypt-Salt".toByteArray(), "Pair-Setup-Encrypt-Info".toByteArray(), 32)
        assertArrayEquals(expected, actual)
        assertEquals(32, actual.size)
    }

    @Test
    fun emptySaltBehavesAsZeroKeyOfHashLength() {
        val ikm = ByteArray(32) { it.toByte() }
        val actual = Hkdf.sha512(ikm, "", "ClientEncrypt-main")
        assertArrayEquals(referenceHkdf(ikm, ByteArray(0), "ClientEncrypt-main".toByteArray(), 32), actual)
    }

    @Test
    fun supportsOutputLongerThanOneHashBlock() {
        val ikm = byteArrayOf(1, 2, 3)
        val actual = Hkdf.sha512(ikm, "salt".toByteArray(), "info".toByteArray(), 100)
        assertArrayEquals(referenceHkdf(ikm, "salt".toByteArray(), "info".toByteArray(), 100), actual)
    }

    @Test
    fun knownVector() {
        // HKDF-SHA512 of ikm 0x0b*22, salt 000102..0c, info f0f1..f9, L=42 (RFC 5869 test case 1 inputs, SHA-512 variant).
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val expected = (
            "832390086cda71fb47625bb5ceb168e4c8e26a1a16ed34d9fc7fe92c1481579338da362cb8d9f925d7cb"
        ).hexToByteArray()
        assertArrayEquals(expected, Hkdf.sha512(ikm, salt, info, 42))
    }
}
