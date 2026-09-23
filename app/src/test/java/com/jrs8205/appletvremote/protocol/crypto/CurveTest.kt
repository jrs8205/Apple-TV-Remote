package com.jrs8205.appletvremote.protocol.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.EdECPoint
import java.security.spec.EdECPublicKeySpec
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPrivateKeySpec
import java.security.spec.XECPublicKeySpec
import javax.crypto.KeyAgreement

class CurveTest {

    private class FixedRandom(private val bytes: ByteArray) : RandomSource {
        override fun nextBytes(count: Int): ByteArray = bytes.copyOf(count)
    }

    @Test
    fun ed25519DerivesRfc8032PublicKeyAndSignature() {
        val seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hexToByteArray()
        val keyPair = Ed25519KeyPair(seed)
        assertArrayEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a".hexToByteArray(), keyPair.publicKey)
        val expectedSignature = (
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
            ).hexToByteArray()
        assertArrayEquals(expectedSignature, keyPair.sign(ByteArray(0)))
        assertTrue(Ed25519.verify(keyPair.publicKey, ByteArray(0), expectedSignature))
    }

    @Test
    fun ed25519RejectsTamperedSignatureAndMessage() {
        val keyPair = Ed25519KeyPair.generate(SecureRandomSource)
        val message = "hello".toByteArray()
        val signature = keyPair.sign(message)
        assertTrue(Ed25519.verify(keyPair.publicKey, message, signature))
        assertFalse(Ed25519.verify(keyPair.publicKey, "hellp".toByteArray(), signature))
        val tampered = signature.copyOf().also { it[5] = (it[5].toInt() xor 0x10).toByte() }
        assertFalse(Ed25519.verify(keyPair.publicKey, message, tampered))
        assertFalse(Ed25519.verify(keyPair.publicKey, message, ByteArray(10)))
    }

    @Test
    fun ed25519SignatureVerifiesWithJdk() {
        val keyPair = Ed25519KeyPair.generate(SecureRandomSource)
        val message = ByteArray(50) { it.toByte() }
        val signature = keyPair.sign(message)
        val encoded = keyPair.publicKey
        val xOdd = (encoded[31].toInt() and 0x80) != 0
        val yBytes = encoded.reversedArray().also { it[0] = (it[0].toInt() and 0x7F).toByte() }
        val spec = EdECPublicKeySpec(NamedParameterSpec.ED25519, EdECPoint(xOdd, BigInteger(1, yBytes)))
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(spec)
        val verifier = Signature.getInstance("Ed25519").apply { initVerify(publicKey); update(message) }
        assertTrue(verifier.verify(signature))
    }

    @Test
    fun ed25519RequiresThirtyTwoByteSeed() {
        assertThrows(IllegalArgumentException::class.java) { Ed25519KeyPair(ByteArray(16)) }
    }

    @Test
    fun x25519MatchesRfc7748Vectors() {
        val alicePrivate = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".hexToByteArray()
        val bobPrivate = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb".hexToByteArray()
        val alice = X25519KeyPair.generate(FixedRandom(alicePrivate))
        val bob = X25519KeyPair.generate(FixedRandom(bobPrivate))
        assertArrayEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a".hexToByteArray(), alice.publicKey)
        assertArrayEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f".hexToByteArray(), bob.publicKey)
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742".hexToByteArray()
        assertArrayEquals(shared, alice.agree(bob.publicKey))
        assertArrayEquals(shared, bob.agree(alice.publicKey))
    }

    @Test
    fun x25519AgreementMatchesJdk() {
        val ours = X25519KeyPair.generate(SecureRandomSource)
        val jdkPrivateBytes = ByteArray(32) { (it * 11 + 3).toByte() }
        val factory = KeyFactory.getInstance("XDH")
        val jdkPrivate = factory.generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, jdkPrivateBytes))
        val theirs = X25519KeyPair.generate(FixedRandom(jdkPrivateBytes))
        val ourPublicU = BigInteger(1, ours.publicKey.reversedArray().also { it[0] = (it[0].toInt() and 0x7F).toByte() })
        val ourPublicForJdk = factory.generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, ourPublicU))
        val jdkShared = KeyAgreement.getInstance("XDH").apply {
            init(jdkPrivate)
            doPhase(ourPublicForJdk, true)
        }.generateSecret()
        assertArrayEquals(jdkShared, ours.agree(theirs.publicKey))
    }

    @Test
    fun x25519RejectsAllZeroSharedSecret() {
        val keyPair = X25519KeyPair.generate(SecureRandomSource)
        assertThrows(IllegalStateException::class.java) { keyPair.agree(ByteArray(32)) }
    }

    @Test
    fun secureRandomSourceReturnsRequestedLength() {
        assertEquals(32, SecureRandomSource.nextBytes(32).size)
        assertFalse(SecureRandomSource.nextBytes(32).contentEquals(SecureRandomSource.nextBytes(32)))
    }
}
