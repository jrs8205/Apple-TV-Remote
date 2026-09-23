package com.jrs8205.appletvremote.protocol.companion

import com.jrs8205.appletvremote.protocol.crypto.ChaChaBox
import com.jrs8205.appletvremote.protocol.crypto.Nonce

/**
 * Encrypts and decrypts frame payloads after pair-verify. Each direction has its own key and
 * message counter; the 4-byte header is authenticated as associated data. Empty payloads are
 * sent in the clear and do not advance the counter. Not thread-safe: callers serialize sends
 * and only the read loop decrypts.
 */
class FrameCipher(encryptKey: ByteArray, decryptKey: ByteArray) {

    private val encryptBox = ChaChaBox(encryptKey)
    private val decryptBox = ChaChaBox(decryptKey)
    private var sendCounter = 0L
    private var receiveCounter = 0L

    /** Returns the complete frame: header followed by ciphertext and tag. */
    fun seal(type: FrameType, plaintext: ByteArray): ByteArray {
        if (plaintext.isEmpty()) return FrameCodec.encodeHeader(type, 0)
        val header = FrameCodec.encodeHeader(type, plaintext.size + TAG_LENGTH)
        val body = encryptBox.seal(Nonce.counter(sendCounter++), plaintext, header)
        return header + body
    }

    fun open(header: ByteArray, body: ByteArray): ByteArray {
        if (body.isEmpty()) return body
        return decryptBox.open(Nonce.counter(receiveCounter++), body, header)
    }

    private companion object {
        const val TAG_LENGTH = 16
    }
}
