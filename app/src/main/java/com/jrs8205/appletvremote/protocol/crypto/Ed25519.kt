package com.jrs8205.appletvremote.protocol.crypto

import org.bouncycastle.math.ec.rfc8032.Ed25519 as BcEd25519

class Ed25519KeyPair(val seed: ByteArray) {

    init {
        require(seed.size == BcEd25519.SECRET_KEY_SIZE) { "seed must be ${BcEd25519.SECRET_KEY_SIZE} bytes, got ${seed.size}" }
    }

    val publicKey: ByteArray = ByteArray(BcEd25519.PUBLIC_KEY_SIZE).also { BcEd25519.generatePublicKey(seed, 0, it, 0) }

    fun sign(message: ByteArray): ByteArray {
        val signature = ByteArray(BcEd25519.SIGNATURE_SIZE)
        BcEd25519.sign(seed, 0, message, 0, message.size, signature, 0)
        return signature
    }

    companion object {
        fun generate(random: RandomSource): Ed25519KeyPair = Ed25519KeyPair(random.nextBytes(BcEd25519.SECRET_KEY_SIZE))
    }
}

object Ed25519 {
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != BcEd25519.PUBLIC_KEY_SIZE || signature.size != BcEd25519.SIGNATURE_SIZE) return false
        return BcEd25519.verify(signature, 0, publicKey, 0, message, 0, message.size)
    }
}
