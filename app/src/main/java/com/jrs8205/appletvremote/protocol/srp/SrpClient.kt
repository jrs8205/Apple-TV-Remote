package com.jrs8205.appletvremote.protocol.srp

import com.jrs8205.appletvremote.protocol.crypto.RandomSource
import java.math.BigInteger
import java.security.MessageDigest

class SrpException(message: String) : RuntimeException(message)

class SrpProofs(val clientPublic: ByteArray, val clientProof: ByteArray)

/**
 * SRP-6a client (RFC 5054 group, SHA-512) with the HomeKit conventions:
 * k = H(N || PAD(g)), u = H(PAD(A) || PAD(B)), x = H(s || H(I ":" P)), K = H(S),
 * M1 = H(H(N) xor H(g) || H(I) || s || A || B || K), M2 = H(A || M1 || K).
 */
class SrpClient(
    private val username: String,
    private val password: String,
    private val random: RandomSource,
) {
    private val n = SrpGroup3072.N
    private val g = SrpGroup3072.G
    private val padLength = SrpGroup3072.PAD_LENGTH

    private var state: Completed? = null

    private class Completed(val clientPublic: ByteArray, val clientProof: ByteArray, val sessionKey: ByteArray)

    /** K, available once [computeProofs] has run. */
    val sessionKey: ByteArray
        get() = checkNotNull(state) { "computeProofs must run first" }.sessionKey

    fun computeProofs(salt: ByteArray, serverPublic: ByteArray): SrpProofs {
        val b = BigInteger(1, serverPublic)
        if (b.mod(n).signum() == 0) throw SrpException("server public value is zero mod N")

        val (a, clientPublic) = freshExponent()
        val k = BigInteger(1, hash(unsigned(n), pad(g)))
        val u = BigInteger(1, hash(pad(BigInteger(1, clientPublic)), pad(b)))
        if (u.signum() == 0) throw SrpException("scrambling parameter is zero")
        val x = BigInteger(1, hash(salt, hash("$username:$password".toByteArray(Charsets.UTF_8))))

        // S = (B - k * g^x) ^ (a + u * x) mod N
        val base = b.subtract(k.multiply(g.modPow(x, n))).mod(n)
        val exponent = a.add(u.multiply(x))
        val premaster = base.modPow(exponent, n)
        val key = hash(unsigned(premaster))

        val hn = hash(unsigned(n))
        val hg = hash(unsigned(g))
        val xor = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        val proof = hash(xor, hash(username.toByteArray(Charsets.UTF_8)), salt, clientPublic, serverPublic, key)

        state = Completed(clientPublic, proof, key)
        return SrpProofs(clientPublic, proof)
    }

    /** Constant-time check of the accessory's M2 = H(A || M1 || K). */
    fun verifyServerProof(serverProof: ByteArray): Boolean {
        val completed = checkNotNull(state) { "computeProofs must run first" }
        val expected = hash(completed.clientPublic, completed.clientProof, completed.sessionKey)
        return MessageDigest.isEqual(expected, serverProof)
    }

    /** Draws a random exponent until A has no leading zero byte, so A is always exactly [padLength] bytes. */
    private fun freshExponent(): Pair<BigInteger, ByteArray> {
        repeat(64) {
            val a = BigInteger(1, random.nextBytes(32))
            val publicValue = unsigned(g.modPow(a, n))
            if (publicValue.size == padLength) return a to publicValue
        }
        throw SrpException("could not generate a full-length client public value")
    }

    private fun pad(value: BigInteger): ByteArray {
        val bytes = unsigned(value)
        return ByteArray(padLength - bytes.size) + bytes
    }

    private fun unsigned(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }

    private fun hash(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-512")
        parts.forEach(digest::update)
        return digest.digest()
    }
}
