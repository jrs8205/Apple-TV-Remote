package com.jrs8205.appletvremote.protocol.tlv8

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Tlv8Test {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun encodesShortItemsInOrder() {
        val encoded = Tlv8.encode(listOf(TlvType.STATE to bytes(0x01), TlvType.METHOD to bytes(0x00)))
        assertArrayEquals(bytes(0x06, 0x01, 0x01, 0x00, 0x01, 0x00), encoded)
    }

    @Test
    fun encodesEmptyValue() {
        assertArrayEquals(bytes(0x07, 0x00), Tlv8.encode(listOf(TlvType.ERROR to ByteArray(0))))
    }

    @Test
    fun splitsValuesLongerThan255BytesIntoFragments() {
        val value = ByteArray(300) { it.toByte() }
        val encoded = Tlv8.encode(listOf(TlvType.PUBLIC_KEY to value))
        assertEquals(300 + 4, encoded.size)
        assertEquals(0x03.toByte(), encoded[0])
        assertEquals(0xFF.toByte(), encoded[1])
        assertEquals(0x03.toByte(), encoded[257])
        assertEquals(45.toByte(), encoded[258])
        assertArrayEquals(value.copyOfRange(255, 300), encoded.copyOfRange(259, 304))
    }

    @Test
    fun splitsSixHundredBytesIntoThreeFragments() {
        val value = ByteArray(600) { (it * 7).toByte() }
        val encoded = Tlv8.encode(listOf(TlvType.PUBLIC_KEY to value))
        assertEquals(600 + 6, encoded.size)
        assertEquals(0xFF.toByte(), encoded[1])
        assertEquals(0xFF.toByte(), encoded[258])
        assertEquals(90.toByte(), encoded[515])
    }

    @Test
    fun decodeMergesConsecutiveFragmentsOfTheSameType() {
        val value = ByteArray(600) { (it * 7).toByte() }
        val decoded = Tlv8.decode(Tlv8.encode(listOf(TlvType.PUBLIC_KEY to value, TlvType.STATE to bytes(0x03))))
        assertArrayEquals(value, decoded.getValue(TlvType.PUBLIC_KEY))
        assertArrayEquals(bytes(0x03), decoded.getValue(TlvType.STATE))
        assertEquals(2, decoded.size)
    }

    @Test
    fun decodesEmptyInputToEmptyMap() {
        assertEquals(emptyMap<Int, ByteArray>(), Tlv8.decode(ByteArray(0)))
    }

    @Test
    fun decodesTypesPreservingFirstSeenOrder() {
        val decoded = Tlv8.decode(bytes(0x06, 0x01, 0x02, 0x02, 0x02, 0xAA, 0xBB))
        assertEquals(listOf(TlvType.STATE, TlvType.SALT), decoded.keys.toList())
        assertArrayEquals(bytes(0xAA, 0xBB), decoded.getValue(TlvType.SALT))
    }

    @Test
    fun rejectsTruncatedItem() {
        assertThrows(Tlv8Exception::class.java) { Tlv8.decode(bytes(0x06, 0x05, 0x01)) }
        assertThrows(Tlv8Exception::class.java) { Tlv8.decode(bytes(0x06)) }
    }

    @Test
    fun rejectsTypeOutsideByteRange() {
        assertThrows(Tlv8Exception::class.java) { Tlv8.encode(listOf(256 to bytes(0x01))) }
    }
}
