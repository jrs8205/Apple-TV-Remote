package com.jrs8205.appletvremote.protocol.srp

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Verifier side of SRP-6a written independently of [SrpClient], following the same
 * HomeKit conventions: k = H(N || PAD(g)), u = H(PAD(A) || PAD(B)), x = H(s || H(I ":" p)).
 */
class TestSrpServer(
    private val username: String,
    password: String,
    val salt: ByteArray,
    privateKey: ByteArray,
) {
    private val n = SrpGroup3072.N
    private val g = SrpGroup3072.G
    private val padLength = SrpGroup3072.PAD_LENGTH
    private val b = BigInteger(1, privateKey)
    private val verifier: BigInteger
    val publicKey: ByteArray
    var sessionKey: ByteArray? = null
        private set
    var serverProof: ByteArray? = null
        private set

    init {
        val x = BigInteger(1, hash(salt, hash("$username:$password".toByteArray())))
        verifier = g.modPow(x, n)
        val k = BigInteger(1, hash(unsigned(n), pad(g)))
        val bPublic = (k.multiply(verifier).add(g.modPow(b, n))).mod(n)
        publicKey = unsigned(bPublic)
    }

    /** Returns true and computes the server proof when the client proof is valid. */
    fun verifyClientProof(clientPublic: ByteArray, clientProof: ByteArray): Boolean {
        val a = BigInteger(1, clientPublic)
        val u = BigInteger(1, hash(pad(a), pad(BigInteger(1, publicKey))))
        val s = a.multiply(verifier.modPow(u, n)).mod(n).modPow(b, n)
        val key = hash(unsigned(s))
        val hn = hash(unsigned(n))
        val hg = hash(unsigned(g))
        val xor = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        val expected = hash(xor, hash(username.toByteArray()), salt, clientPublic, publicKey, key)
        if (!expected.contentEquals(clientProof)) return false
        sessionKey = key
        serverProof = hash(clientPublic, clientProof, key)
        return true
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
