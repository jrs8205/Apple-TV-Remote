package com.jrs8205.appletvremote.protocol.crypto

import org.bouncycastle.math.ec.rfc7748.X25519 as BcX25519

class X25519KeyPair private constructor(private val privateKey: ByteArray) {

    val publicKey: ByteArray = ByteArray(BcX25519.POINT_SIZE).also { BcX25519.scalarMultBase(privateKey, 0, it, 0) }

    /** Diffie-Hellman agreement; an all-zero result means the peer sent a low-order point and is rejected. */
    fun agree(peerPublicKey: ByteArray): ByteArray {
        require(peerPublicKey.size == BcX25519.POINT_SIZE) { "peer public key must be ${BcX25519.POINT_SIZE} bytes" }
        val shared = ByteArray(BcX25519.POINT_SIZE)
        check(BcX25519.calculateAgreement(privateKey, 0, peerPublicKey, 0, shared, 0)) { "X25519 agreement produced an all-zero secret" }
        return shared
    }

    companion object {
        fun generate(random: RandomSource): X25519KeyPair = X25519KeyPair(random.nextBytes(BcX25519.SCALAR_SIZE))
    }
}
