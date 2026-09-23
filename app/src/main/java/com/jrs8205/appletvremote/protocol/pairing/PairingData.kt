package com.jrs8205.appletvremote.protocol.pairing

import com.jrs8205.appletvremote.protocol.tlv8.Tlv8
import com.jrs8205.appletvremote.protocol.tlv8.Tlv8Exception
import com.jrs8205.appletvremote.protocol.tlv8.TlvType

/** Reads the `_pd` TLV out of a pairing reply and turns TLV error items into exceptions. */
internal object PairingData {

    fun read(message: Map<*, *>, expectedState: Int): Map<Int, ByteArray> {
        val raw = message["_pd"] as? ByteArray ?: throw PairingException.Malformed("no _pd in message")
        val tlv = try {
            Tlv8.decode(raw)
        } catch (e: Tlv8Exception) {
            throw PairingException.Malformed(e.message ?: "bad TLV")
        }
        tlv[TlvType.ERROR]?.let { error ->
            val code = error.firstOrNull()?.toInt()?.and(0xFF) ?: 0
            throw PairingException.fromErrorCode(code, tlv[TlvType.RETRY_DELAY]?.let(::littleEndian))
        }
        val state = tlv[TlvType.STATE]?.firstOrNull()?.toInt()?.and(0xFF)
            ?: throw PairingException.Malformed("no state in message")
        if (state != expectedState) throw PairingException.UnexpectedState(expectedState, state)
        return tlv
    }

    fun require(tlv: Map<Int, ByteArray>, type: Int, name: String): ByteArray =
        tlv[type] ?: throw PairingException.Malformed("missing $name")

    private fun littleEndian(bytes: ByteArray): Int {
        var value = 0
        for (i in bytes.indices.reversed()) value = (value shl 8) or (bytes[i].toInt() and 0xFF)
        return value
    }
}
