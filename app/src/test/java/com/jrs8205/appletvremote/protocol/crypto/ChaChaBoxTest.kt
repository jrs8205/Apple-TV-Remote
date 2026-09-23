package com.jrs8205.appletvremote.protocol.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ChaChaBoxTest {

    private val rfcKey = "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f".hexToByteArray()
    private val rfcNonce = "070000004041424344454647".hexToByteArray()
    private val rfcAad = "50515253c0c1c2c3c4c5c6c7".hexToByteArray()
    private val rfcPlaintext =
        "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
            .toByteArray()
    private val rfcCiphertext = (
        "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
            "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36" +
            "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
            "3ff4def08e4b7a9de576d26586cec64b6116"
        ).hexToByteArray()
    private val rfcTag = "1ae10b594f09e26a7e902ecbd0600691".hexToByteArray()

    @Test
    fun sealsRfc8439Vector() {
        val sealed = ChaChaBox(rfcKey).seal(rfcNonce, rfcPlaintext, rfcAad)
        assertArrayEquals(rfcCiphertext + rfcTag, sealed)
    }

    @Test
    fun opensRfc8439Vector() {
        val opened = ChaChaBox(rfcKey).open(rfcNonce, rfcCiphertext + rfcTag, rfcAad)
        assertArrayEquals(rfcPlaintext, opened)
    }

    @Test
    fun rejectsTamperedTag() {
        val tampered = rfcCiphertext + rfcTag.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(AuthenticationFailedException::class.java) { ChaChaBox(rfcKey).open(rfcNonce, tampered, rfcAad) }
    }

    @Test
    fun rejectsWrongAad() {
        assertThrows(AuthenticationFailedException::class.java) {
            ChaChaBox(rfcKey).open(rfcNonce, rfcCiphertext + rfcTag, byteArrayOf(1))
        }
    }

    @Test
    fun matchesJdkImplementationWithoutAad() {
        val key = ByteArray(32) { (it * 7).toByte() }
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val plaintext = ByteArray(77) { (it * 13).toByte() }
        val jdk = Cipher.getInstance("ChaCha20-Poly1305").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        }.doFinal(plaintext)
        assertArrayEquals(jdk, ChaChaBox(key).seal(nonce, plaintext))
        assertArrayEquals(plaintext, ChaChaBox(key).open(nonce, jdk))
    }

    @Test
    fun sealingEmptyPlaintextProducesOnlyTag() {
        val sealed = ChaChaBox(rfcKey).seal(rfcNonce, ByteArray(0), rfcAad)
        assertEquals(16, sealed.size)
        assertArrayEquals(ByteArray(0), ChaChaBox(rfcKey).open(rfcNonce, sealed, rfcAad))
    }

    @Test
    fun boxIsReusableAcrossMessages() {
        val box = ChaChaBox(rfcKey)
        val first = box.seal(Nonce.counter(0), byteArrayOf(1, 2, 3))
        val second = box.seal(Nonce.counter(1), byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), box.open(Nonce.counter(0), first))
        assertArrayEquals(byteArrayOf(1, 2, 3), box.open(Nonce.counter(1), second))
    }

    @Test
    fun rejectsKeyOfWrongLength() {
        assertThrows(IllegalArgumentException::class.java) { ChaChaBox(ByteArray(31)) }
    }
}
