package com.jrs8205.appletvremote.protocol.companion

import java.io.DataInputStream

class FrameException(message: String) : RuntimeException(message)

data class FrameHeader(val type: FrameType, val length: Int)

class RawFrame(val type: FrameType, val header: ByteArray, val payload: ByteArray)

/** Companion Link frames: one type byte, a 24-bit big-endian payload length, then the payload. */
object FrameCodec {

    const val HEADER_LENGTH = 4
    const val MAX_PAYLOAD_LENGTH = 1 shl 20

    fun encodeHeader(type: FrameType, payloadLength: Int): ByteArray {
        checkLength(payloadLength)
        return byteArrayOf(
            type.code.toByte(),
            (payloadLength ushr 16).toByte(),
            (payloadLength ushr 8).toByte(),
            payloadLength.toByte(),
        )
    }

    fun decodeHeader(header: ByteArray): FrameHeader {
        if (header.size != HEADER_LENGTH) throw FrameException("header must be $HEADER_LENGTH bytes, got ${header.size}")
        val length = ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
        checkLength(length)
        return FrameHeader(FrameType.fromCode(header[0].toInt() and 0xFF), length)
    }

    /** Blocks until a whole frame is available; throws [java.io.EOFException] when the stream ends first. */
    fun readFrame(input: DataInputStream): RawFrame {
        val header = ByteArray(HEADER_LENGTH)
        input.readFully(header)
        val decoded = decodeHeader(header)
        val payload = ByteArray(decoded.length)
        input.readFully(payload)
        return RawFrame(decoded.type, header, payload)
    }

    private fun checkLength(length: Int) {
        if (length < 0 || length > MAX_PAYLOAD_LENGTH) {
            throw FrameException("frame payload of $length bytes exceeds $MAX_PAYLOAD_LENGTH")
        }
    }
}
