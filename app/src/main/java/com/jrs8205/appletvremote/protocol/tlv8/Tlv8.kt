package com.jrs8205.appletvremote.protocol.tlv8

import java.io.ByteArrayOutputStream

class Tlv8Exception(message: String) : RuntimeException(message)

/** TLV item types used by the HomeKit style pairing that Companion Link carries in `_pd`. */
object TlvType {
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val STATE = 0x06
    const val ERROR = 0x07
    const val RETRY_DELAY = 0x08
    const val SIGNATURE = 0x0A
    const val NAME = 0x11
}

/** Type-length-value encoding with one-byte type and length; values over 255 bytes span consecutive items. */
object Tlv8 {

    private const val MAX_FRAGMENT = 255

    fun encode(items: List<Pair<Int, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((type, value) in items) {
            if (type !in 0..0xFF) throw Tlv8Exception("type $type does not fit in one byte")
            if (value.isEmpty()) {
                out.write(type)
                out.write(0)
                continue
            }
            var offset = 0
            while (offset < value.size) {
                val length = minOf(MAX_FRAGMENT, value.size - offset)
                out.write(type)
                out.write(length)
                out.write(value, offset, length)
                offset += length
            }
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): Map<Int, ByteArray> {
        val buffers = LinkedHashMap<Int, ByteArrayOutputStream>()
        var previousType = -1
        var position = 0
        while (position < bytes.size) {
            if (position + 2 > bytes.size) throw Tlv8Exception("truncated item header at offset $position")
            val type = bytes[position].toInt() and 0xFF
            val length = bytes[position + 1].toInt() and 0xFF
            position += 2
            if (position + length > bytes.size) throw Tlv8Exception("item of type $type needs $length bytes at offset $position")
            val buffer = if (type == previousType) buffers.getValue(type) else buffers.getOrPut(type) { ByteArrayOutputStream() }
            buffer.write(bytes, position, length)
            position += length
            previousType = type
        }
        return buffers.mapValues { it.value.toByteArray() }
    }
}
