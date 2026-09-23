package com.jrs8205.appletvremote.protocol.crypto

object Nonce {

    private const val LENGTH = 12

    /** Pairing messages use fixed ASCII labels such as `PS-Msg05`, zero-padded on the left to 12 bytes. */
    fun label(text: String): ByteArray {
        val ascii = text.toByteArray(Charsets.US_ASCII)
        require(ascii.size <= LENGTH) { "nonce label '$text' is longer than $LENGTH bytes" }
        return ByteArray(LENGTH - ascii.size) + ascii
    }

    /** Session frames use a per-direction message counter written little-endian into 12 bytes. */
    fun counter(value: Long): ByteArray {
        val nonce = ByteArray(LENGTH)
        for (i in 0 until 8) nonce[i] = ((value ushr (8 * i)) and 0xFF).toByte()
        return nonce
    }
}
