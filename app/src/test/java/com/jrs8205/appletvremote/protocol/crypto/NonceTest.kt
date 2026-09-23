package com.jrs8205.appletvremote.protocol.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NonceTest {

    @Test
    fun labelNonceIsLeftPaddedToTwelveBytes() {
        val expected = byteArrayOf(0, 0, 0, 0) + "PS-Msg05".toByteArray()
        assertArrayEquals(expected, Nonce.label("PS-Msg05"))
    }

    @Test
    fun labelLongerThanTwelveBytesIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { Nonce.label("this-is-too-long") }
    }

    @Test
    fun counterNonceIsLittleEndianInTwelveBytes() {
        assertArrayEquals(ByteArray(12), Nonce.counter(0))
        assertArrayEquals(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), Nonce.counter(1))
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), Nonce.counter(0x1234))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0), Nonce.counter(0x1_0000_0000L))
    }
}
