package com.jrs8205.appletvremote.protocol.opack

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class OpackTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun encodesBooleansAndNull() {
        assertArrayEquals(bytes(0x01), Opack.encode(true))
        assertArrayEquals(bytes(0x02), Opack.encode(false))
        assertArrayEquals(bytes(0x04), Opack.encode(null))
    }

    @Test
    fun decodesBooleansAndNull() {
        assertEquals(true, Opack.decode(bytes(0x01)))
        assertEquals(false, Opack.decode(bytes(0x02)))
        assertNull(Opack.decode(bytes(0x04)))
    }

    @Test
    fun encodesSmallIntegersInline() {
        assertArrayEquals(bytes(0x08), Opack.encode(0))
        assertArrayEquals(bytes(0x2F), Opack.encode(39))
        assertArrayEquals(bytes(0x2F), Opack.encode(39L))
    }

    @Test
    fun encodesIntegersWithLittleEndianWidths() {
        assertArrayEquals(bytes(0x30, 0x28), Opack.encode(40))
        assertArrayEquals(bytes(0x30, 0xFF), Opack.encode(255))
        assertArrayEquals(bytes(0x31, 0x00, 0x01), Opack.encode(256))
        assertArrayEquals(bytes(0x31, 0xFF, 0xFF), Opack.encode(65535))
        assertArrayEquals(bytes(0x32, 0x00, 0x00, 0x01, 0x00), Opack.encode(65536))
        assertArrayEquals(bytes(0x33, 0, 0, 0, 0, 1, 0, 0, 0), Opack.encode(0x1_0000_0000L))
        assertArrayEquals(bytes(0x33, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF), Opack.encode(-1L))
    }

    @Test
    fun decodesIntegersAsLong() {
        assertEquals(0L, Opack.decode(bytes(0x08)))
        assertEquals(39L, Opack.decode(bytes(0x2F)))
        assertEquals(255L, Opack.decode(bytes(0x30, 0xFF)))
        assertEquals(65536L, Opack.decode(bytes(0x32, 0x00, 0x00, 0x01, 0x00)))
        assertEquals(-1L, Opack.decode(bytes(0x33, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)))
    }

    @Test
    fun encodesDoubleAsLittleEndianFloat64() {
        assertArrayEquals(bytes(0x36, 0, 0, 0, 0, 0, 0, 0xF8, 0x3F), Opack.encode(1.5))
        assertArrayEquals(bytes(0x36, 0, 0, 0, 0, 0, 0, 0xF8, 0x3F), Opack.encode(1.5f))
    }

    @Test
    fun decodesFloat32AndFloat64() {
        assertEquals(1.5, Opack.decode(bytes(0x35, 0, 0, 0xC0, 0x3F)))
        assertEquals(1.5, Opack.decode(bytes(0x36, 0, 0, 0, 0, 0, 0, 0xF8, 0x3F)))
    }

    @Test
    fun encodesStringsWithInlineOrPrefixedLength() {
        assertArrayEquals(bytes(0x40), Opack.encode(""))
        assertArrayEquals(bytes(0x42, '_'.code, 'i'.code), Opack.encode("_i"))
        val s32 = "a".repeat(32)
        assertArrayEquals(bytes(0x60) + s32.toByteArray(), Opack.encode(s32))
        val s33 = "a".repeat(33)
        assertArrayEquals(bytes(0x61, 33) + s33.toByteArray(), Opack.encode(s33))
        val s256 = "b".repeat(256)
        assertArrayEquals(bytes(0x62, 0x00, 0x01) + s256.toByteArray(), Opack.encode(s256))
    }

    @Test
    fun stringLengthCountsUtf8Bytes() {
        val encoded = Opack.encode("ä")
        assertArrayEquals(bytes(0x42, 0xC3, 0xA4), encoded)
        assertEquals("ä", Opack.decode(encoded))
    }

    @Test
    fun encodesByteArraysWithInlineOrPrefixedLength() {
        assertArrayEquals(bytes(0x70), Opack.encode(ByteArray(0)))
        val b32 = ByteArray(32) { 7 }
        assertArrayEquals(bytes(0x90) + b32, Opack.encode(b32))
        val b33 = ByteArray(33) { 7 }
        assertArrayEquals(bytes(0x91, 33) + b33, Opack.encode(b33))
        val b256 = ByteArray(256) { 9 }
        assertArrayEquals(bytes(0x92, 0x00, 0x01) + b256, Opack.encode(b256))
    }

    @Test
    fun encodesUuidBigEndian() {
        val uuid = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
        val expected = bytes(
            0x05, 0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
            0x88, 0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF,
        )
        assertArrayEquals(expected, Opack.encode(uuid))
        assertEquals(uuid, Opack.decode(expected))
    }

    @Test
    fun roundTripsDates() {
        val date = OpackDate(123.5)
        val encoded = Opack.encode(date)
        assertEquals(0x06.toByte(), encoded[0])
        assertEquals(9, encoded.size)
        assertEquals(date, Opack.decode(encoded))
    }

    @Test
    fun encodesShortLists() {
        assertArrayEquals(bytes(0xD0), Opack.encode(emptyList<Any>()))
        assertArrayEquals(bytes(0xD2, 0x09, 0x41, 'a'.code), Opack.encode(listOf(1, "a")))
    }

    @Test
    fun encodesLongListsWithTerminator() {
        val list = List(15) { 0 }
        assertArrayEquals(bytes(0xDF) + ByteArray(15) { 0x08 } + bytes(0x03), Opack.encode(list))
        assertEquals(List(15) { 0L }, Opack.decode(Opack.encode(list)))
    }

    @Test
    fun encodesShortDictionaries() {
        assertArrayEquals(bytes(0xE0), Opack.encode(emptyMap<String, Any>()))
        assertArrayEquals(bytes(0xE1, 0x42, '_'.code, 't'.code, 0x0A), Opack.encode(mapOf("_t" to 2)))
    }

    @Test
    fun encodesLongDictionariesWithTerminator() {
        val map = (0 until 15).associate { "k$it" to it }
        val encoded = Opack.encode(map)
        assertEquals(0xEF.toByte(), encoded[0])
        assertEquals(0x03.toByte(), encoded.last())
        assertEquals(map.mapValues { it.value.toLong() }, Opack.decode(encoded))
    }

    @Test
    fun decodesDictionariesPreservingOrder() {
        val decoded = Opack.decode(Opack.encode(linkedMapOf("z" to 1, "a" to 2, "m" to 3))) as Map<*, *>
        assertEquals(listOf("z", "a", "m"), decoded.keys.toList())
    }

    @Test
    fun roundTripsNestedStructure() {
        val value = linkedMapOf<String, Any?>(
            "_i" to "_systemInfo",
            "_t" to 2,
            "_x" to 123456L,
            "_c" to linkedMapOf(
                "_bf" to 0,
                "_cf" to 512,
                "_idsID" to byteArrayOf(1, 2, 3),
                "list" to listOf(true, false, null, 1.25, "x"),
            ),
        )
        val decoded = Opack.decode(Opack.encode(value)) as Map<*, *>
        assertEquals("_systemInfo", decoded["_i"])
        assertEquals(2L, decoded["_t"])
        assertEquals(123456L, decoded["_x"])
        val content = decoded["_c"] as Map<*, *>
        assertEquals(512L, content["_cf"])
        assertArrayEquals(byteArrayOf(1, 2, 3), content["_idsID"] as ByteArray)
        assertEquals(listOf(true, false, null, 1.25, "x"), content["list"])
    }

    @Test
    fun encoderNeverEmitsBackReferences() {
        val encoded = Opack.encode(listOf("abc", "abc"))
        assertArrayEquals(
            bytes(0xD2, 0x43, 'a'.code, 'b'.code, 'c'.code, 0x43, 'a'.code, 'b'.code, 'c'.code),
            encoded,
        )
    }

    @Test
    fun decodesInlineBackReferenceToEarlierString() {
        val decoded = Opack.decode(bytes(0xD2, 0x43, 'a'.code, 'b'.code, 'c'.code, 0xA0))
        assertEquals(listOf("abc", "abc"), decoded)
    }

    @Test
    fun decodesPrefixedBackReference() {
        val decoded = Opack.decode(bytes(0xD2, 0x43, 'a'.code, 'b'.code, 'c'.code, 0xC1, 0x00))
        assertEquals(listOf("abc", "abc"), decoded)
    }

    @Test
    fun backReferenceTableSkipsSmallIntegersBooleansAndContainers() {
        // Table after decoding is ["xyz", "abcd"]: small ints, booleans and the nested list are not indexed.
        val decoded = Opack.decode(
            bytes(
                0xD5, 0x09, 0x01, 0xD1, 0x43, 'x'.code, 'y'.code, 'z'.code,
                0x44, 'a'.code, 'b'.code, 'c'.code, 'd'.code, 0xA1,
            ),
        )
        assertEquals(listOf(1L, true, listOf("xyz"), "abcd", "abcd"), decoded)
    }

    @Test
    fun backReferenceTableIncludesWideIntegersAndBytes() {
        // Table is [255, bytes(9)], so 0xA1 refers to the byte array.
        val decoded = Opack.decode(bytes(0xD3, 0x30, 0xFF, 0x71, 0x09, 0xA1)) as List<*>
        assertEquals(255L, decoded[0])
        assertArrayEquals(bytes(0x09), decoded[1] as ByteArray)
        assertArrayEquals(bytes(0x09), decoded[2] as ByteArray)
    }

    @Test
    fun rejectsDanglingBackReference() {
        val error = assertThrows(OpackException::class.java) { Opack.decode(bytes(0xD1, 0xA0)) }
        assertTrue(error.message!!.contains("reference"))
    }

    @Test
    fun rejectsUnknownTypeByte() {
        assertThrows(OpackException::class.java) { Opack.decode(bytes(0x00)) }
    }

    @Test
    fun rejectsTruncatedInput() {
        assertThrows(OpackException::class.java) { Opack.decode(bytes(0x43, 'a'.code)) }
        assertThrows(OpackException::class.java) { Opack.decode(bytes(0x31, 0x01)) }
    }

    @Test
    fun rejectsUnsupportedValueOnEncode() {
        assertThrows(OpackException::class.java) { Opack.encode(Any()) }
    }
}
