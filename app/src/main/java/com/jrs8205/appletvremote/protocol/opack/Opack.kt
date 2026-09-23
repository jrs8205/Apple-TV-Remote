package com.jrs8205.appletvremote.protocol.opack

import java.io.ByteArrayOutputStream
import java.util.UUID

class OpackException(message: String) : RuntimeException(message)

/** Seconds since 2001-01-01T00:00:00Z, the reference date OPACK uses for dates. */
data class OpackDate(val secondsSince2001: Double)

/**
 * OPACK is the compact binary serialization used inside Companion Link frames.
 * Multi-byte integers and length prefixes are little-endian; UUIDs are big-endian.
 *
 * The encoder never writes back-references (0xA0..0xC4): they only save space and the
 * receiving side accepts fully spelled-out values. The decoder resolves them the way the
 * Apple TV emits them: only strings, byte arrays, UUIDs, dates, floats and integers wider
 * than one byte enter the reference table; small integers, booleans, null and containers do not.
 */
object Opack {

    fun encode(value: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        Encoder(out).write(value)
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): Any? = Decoder(bytes).read()

    private class Encoder(private val out: ByteArrayOutputStream) {

        fun write(value: Any?) {
            when (value) {
                null -> out.write(0x04)
                true -> out.write(0x01)
                false -> out.write(0x02)
                is Byte -> writeInt(value.toLong())
                is Short -> writeInt(value.toLong())
                is Int -> writeInt(value.toLong())
                is Long -> writeInt(value)
                is Float -> writeDouble(0x36, value.toDouble())
                is Double -> writeDouble(0x36, value)
                is String -> writeSized(value.toByteArray(Charsets.UTF_8), 0x40, 0x60)
                is ByteArray -> writeSized(value, 0x70, 0x90)
                is UUID -> writeUuid(value)
                is OpackDate -> writeDouble(0x06, value.secondsSince2001)
                is List<*> -> writeContainer(value.size, 0xD0) { value.forEach { write(it) } }
                is Map<*, *> -> writeContainer(value.size, 0xE0) {
                    value.forEach { (key, item) ->
                        write(key)
                        write(item)
                    }
                }
                else -> throw OpackException("cannot encode ${value::class.java.name}")
            }
        }

        private fun writeInt(value: Long) {
            when {
                value in 0..39 -> out.write(0x08 + value.toInt())
                value in 0..0xFF -> { out.write(0x30); writeLittleEndian(value, 1) }
                value in 0..0xFFFF -> { out.write(0x31); writeLittleEndian(value, 2) }
                value in 0..0xFFFF_FFFFL -> { out.write(0x32); writeLittleEndian(value, 4) }
                else -> { out.write(0x33); writeLittleEndian(value, 8) }
            }
        }

        private fun writeDouble(tag: Int, value: Double) {
            out.write(tag)
            writeLittleEndian(java.lang.Double.doubleToLongBits(value), 8)
        }

        private fun writeSized(data: ByteArray, inlineBase: Int, prefixedBase: Int) {
            val length = data.size
            when {
                length <= 32 -> out.write(inlineBase + length)
                length <= 0xFF -> { out.write(prefixedBase + 1); writeLittleEndian(length.toLong(), 1) }
                length <= 0xFFFF -> { out.write(prefixedBase + 2); writeLittleEndian(length.toLong(), 2) }
                length <= 0xFF_FFFF -> { out.write(prefixedBase + 3); writeLittleEndian(length.toLong(), 3) }
                else -> { out.write(prefixedBase + 4); writeLittleEndian(length.toLong(), 4) }
            }
            out.write(data)
        }

        private fun writeUuid(uuid: UUID) {
            out.write(0x05)
            writeBigEndian(uuid.mostSignificantBits)
            writeBigEndian(uuid.leastSignificantBits)
        }

        private fun writeContainer(size: Int, base: Int, body: () -> Unit) {
            if (size < 15) {
                out.write(base + size)
                body()
            } else {
                out.write(base + 0x0F)
                body()
                out.write(0x03)
            }
        }

        private fun writeLittleEndian(value: Long, byteCount: Int) {
            for (i in 0 until byteCount) out.write(((value ushr (8 * i)) and 0xFF).toInt())
        }

        private fun writeBigEndian(value: Long) {
            for (i in 7 downTo 0) out.write(((value ushr (8 * i)) and 0xFF).toInt())
        }
    }

    private class Decoder(private val bytes: ByteArray) {
        private var position = 0
        private val references = ArrayList<Any?>()

        fun read(): Any? {
            val tag = next()
            return when {
                tag == 0x01 -> true
                tag == 0x02 -> false
                tag == 0x04 -> null
                tag == 0x05 -> remember(readUuid())
                tag == 0x06 -> remember(OpackDate(readDouble()))
                tag == 0x07 -> -1L
                tag in 0x08..0x2F -> (tag - 0x08).toLong()
                tag in 0x30..0x33 -> remember(readInt(1 shl (tag - 0x30)))
                tag == 0x35 -> remember(java.lang.Float.intBitsToFloat(readLittleEndian(4).toInt()).toDouble())
                tag == 0x36 -> remember(readDouble())
                tag in 0x40..0x60 -> remember(String(take(tag - 0x40), Charsets.UTF_8))
                tag in 0x61..0x64 -> remember(String(take(readLength(tag - 0x60)), Charsets.UTF_8))
                tag == 0x6F -> remember(readNullTerminatedString())
                tag in 0x70..0x90 -> remember(take(tag - 0x70))
                tag in 0x91..0x94 -> remember(take(readLength(tag - 0x90)))
                tag in 0xA0..0xC0 -> reference(tag - 0xA0)
                tag in 0xC1..0xC4 -> reference(readLength(tag - 0xC0))
                tag in 0xD0..0xDE -> readList(tag - 0xD0)
                tag == 0xDF -> readListUntilTerminator()
                tag in 0xE0..0xEE -> readMap(tag - 0xE0)
                tag == 0xEF -> readMapUntilTerminator()
                else -> throw OpackException("cannot unpack byte 0x%02x at offset ${position - 1}".format(tag))
            }
        }

        private fun remember(value: Any?): Any? {
            references.add(value)
            return value
        }

        private fun reference(index: Int): Any? {
            if (index !in references.indices) {
                throw OpackException("back-reference $index points outside the ${references.size} known objects")
            }
            return references[index]
        }

        private fun readInt(byteCount: Int): Long =
            if (byteCount == 8) readLittleEndian(8) else readLittleEndian(byteCount)

        private fun readDouble(): Double = java.lang.Double.longBitsToDouble(readLittleEndian(8))

        private fun readLength(byteCount: Int): Int {
            val length = readLittleEndian(byteCount)
            if (length < 0 || length > Int.MAX_VALUE) throw OpackException("length $length out of range")
            return length.toInt()
        }

        private fun readUuid(): UUID {
            val msb = readBigEndian()
            val lsb = readBigEndian()
            return UUID(msb, lsb)
        }

        private fun readNullTerminatedString(): String {
            val start = position
            while (next() != 0x00) { /* scan to terminator */ }
            return String(bytes, start, position - 1 - start, Charsets.UTF_8)
        }

        private fun readList(size: Int): List<Any?> = List(size) { read() }

        private fun readListUntilTerminator(): List<Any?> {
            val items = ArrayList<Any?>()
            while (peek() != 0x03) items.add(read())
            position++
            return items
        }

        private fun readMap(size: Int): Map<Any?, Any?> {
            val map = LinkedHashMap<Any?, Any?>(size * 2)
            repeat(size) {
                val key = read()
                map[key] = read()
            }
            return map
        }

        private fun readMapUntilTerminator(): Map<Any?, Any?> {
            val map = LinkedHashMap<Any?, Any?>()
            while (peek() != 0x03) {
                val key = read()
                map[key] = read()
            }
            position++
            return map
        }

        private fun readLittleEndian(byteCount: Int): Long {
            var value = 0L
            for (i in 0 until byteCount) value = value or (next().toLong() shl (8 * i))
            return value
        }

        private fun readBigEndian(): Long {
            var value = 0L
            repeat(8) { value = (value shl 8) or next().toLong() }
            return value
        }

        private fun peek(): Int {
            if (position >= bytes.size) throw OpackException("unexpected end of data at offset $position")
            return bytes[position].toInt() and 0xFF
        }

        private fun next(): Int {
            val value = peek()
            position++
            return value
        }

        private fun take(count: Int): ByteArray {
            if (count < 0 || position + count > bytes.size) {
                throw OpackException("unexpected end of data: need $count bytes at offset $position, have ${bytes.size - position}")
            }
            val slice = bytes.copyOfRange(position, position + count)
            position += count
            return slice
        }
    }
}
