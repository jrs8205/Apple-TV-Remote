package com.jrs8205.appletvremote.protocol.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

class AuthenticationFailedException(message: String) : RuntimeException(message)

/** ChaCha20-Poly1305 AEAD (RFC 8439) with a 12-byte nonce and a 16-byte tag appended to the ciphertext. */
class ChaChaBox(key: ByteArray) {

    init {
        require(key.size == 32) { "key must be 32 bytes, got ${key.size}" }
    }

    private val keyParameter = KeyParameter(key)

    fun seal(nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = EMPTY): ByteArray =
        run(forEncryption = true, nonce = nonce, input = plaintext, aad = aad)

    fun open(nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray = EMPTY): ByteArray =
        try {
            run(forEncryption = false, nonce = nonce, input = ciphertext, aad = aad)
        } catch (e: InvalidCipherTextException) {
            throw AuthenticationFailedException("ChaCha20-Poly1305 tag check failed")
        }

    private fun run(forEncryption: Boolean, nonce: ByteArray, input: ByteArray, aad: ByteArray): ByteArray {
        require(nonce.size == 12) { "nonce must be 12 bytes, got ${nonce.size}" }
        val cipher = ChaCha20Poly1305()
        cipher.init(forEncryption, AEADParameters(keyParameter, TAG_BITS, nonce, aad))
        val output = ByteArray(cipher.getOutputSize(input.size))
        val produced = cipher.processBytes(input, 0, input.size, output, 0)
        val total = produced + cipher.doFinal(output, produced)
        return if (total == output.size) output else output.copyOf(total)
    }

    private companion object {
        const val TAG_BITS = 128
        val EMPTY = ByteArray(0)
    }
}
