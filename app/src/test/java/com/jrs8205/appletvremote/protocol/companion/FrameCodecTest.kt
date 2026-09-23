package com.jrs8205.appletvremote.protocol.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException

class FrameCodecTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun frameTypeCodesMatchTheProtocol() {
        assertEquals(1, FrameType.NO_OP.code)
        assertEquals(3, FrameType.PS_START.code)
        assertEquals(4, FrameType.PS_NEXT.code)
        assertEquals(5, FrameType.PV_START.code)
        assertEquals(6, FrameType.PV_NEXT.code)
        assertEquals(7, FrameType.U_OPACK.code)
        assertEquals(8, FrameType.E_OPACK.code)
        assertEquals(9, FrameType.P_OPACK.code)
        assertEquals(FrameType.E_OPACK, FrameType.fromCode(8))
        assertEquals(FrameType.UNKNOWN, FrameType.fromCode(0x7F))
    }

    @Test
    fun encodesHeaderAsTypeAndBigEndian24BitLength() {
        assertArrayEquals(bytes(0x08, 0x00, 0x00, 0x05), FrameCodec.encodeHeader(FrameType.E_OPACK, 5))
        assertArrayEquals(bytes(0x03, 0x01, 0x02, 0x03), FrameCodec.encodeHeader(FrameType.PS_START, 0x010203))
    }

    @Test
    fun decodesHeader() {
        val header = FrameCodec.decodeHeader(bytes(0x04, 0x00, 0x00, 0x10))
        assertEquals(FrameType.PS_NEXT, header.type)
        assertEquals(16, header.length)
    }

    @Test
    fun rejectsPayloadsOverOneMebibyte() {
        assertThrows(FrameException::class.java) { FrameCodec.decodeHeader(bytes(0x08, 0x10, 0x00, 0x01)) }
        assertThrows(FrameException::class.java) { FrameCodec.encodeHeader(FrameType.E_OPACK, (1 shl 20) + 1) }
    }

    @Test
    fun readsFrameFromStream() {
        val stream = DataInputStream(ByteArrayInputStream(bytes(0x08, 0x00, 0x00, 0x03, 0xE0, 0x01, 0x02, 0xFF)))
        val frame = FrameCodec.readFrame(stream)
        assertEquals(FrameType.E_OPACK, frame.type)
        assertArrayEquals(bytes(0x08, 0x00, 0x00, 0x03), frame.header)
        assertArrayEquals(bytes(0xE0, 0x01, 0x02), frame.payload)
        assertEquals(0xFF, stream.read())
    }

    @Test
    fun readsEmptyPayloadFrame() {
        val stream = DataInputStream(ByteArrayInputStream(bytes(0x01, 0x00, 0x00, 0x00)))
        val frame = FrameCodec.readFrame(stream)
        assertEquals(FrameType.NO_OP, frame.type)
        assertEquals(0, frame.payload.size)
    }

    @Test
    fun readFrameFailsOnTruncatedStream() {
        val stream = DataInputStream(ByteArrayInputStream(bytes(0x08, 0x00, 0x00, 0x03, 0xE0)))
        assertThrows(EOFException::class.java) { FrameCodec.readFrame(stream) }
    }
}
