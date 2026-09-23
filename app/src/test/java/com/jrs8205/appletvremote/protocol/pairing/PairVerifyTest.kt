package com.jrs8205.appletvremote.protocol.pairing

import com.jrs8205.appletvremote.protocol.crypto.Ed25519KeyPair
import com.jrs8205.appletvremote.protocol.crypto.SecureRandomSource
import com.jrs8205.appletvremote.protocol.tlv8.Tlv8
import com.jrs8205.appletvremote.protocol.tlv8.TlvType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PairVerifyTest {

    private val identity = ControllerIdentity(
        pairingId = "3b1a0c2e-7d4f-4a2b-9c1d-5e6f7a8b9c0d",
        signingKey = Ed25519KeyPair.generate(SecureRandomSource),
        displayName = "Apple TV Remote",
    )
    private lateinit var accessory: FakeAccessory
    private lateinit var credentials: Credentials

    @Before
    fun pair() {
        accessory = FakeAccessory()
        val setup = PairSetup(identity, SecureRandomSource)
        val m4 = accessory.handleSetupM3(setup.m3(accessory.handleSetupM1(setup.m1()), "3939"))
        credentials = setup.finish(accessory.handleSetupM5(setup.m5(m4)))
    }

    @Test
    fun m1CarriesEphemeralKeyAndAuthType() {
        val m1 = PairVerify(credentials, SecureRandomSource).m1()
        assertEquals(4L, m1["_auTy"])
        val tlv = Tlv8.decode(m1["_pd"] as ByteArray)
        assertArrayEquals(byteArrayOf(1), tlv.getValue(TlvType.STATE))
        assertEquals(32, tlv.getValue(TlvType.PUBLIC_KEY).size)
        assertEquals(setOf("_pd", "_auTy"), m1.keys)
    }

    @Test
    fun derivesMatchingSessionKeys() {
        val verify = PairVerify(credentials, SecureRandomSource)
        val m3 = verify.m3(accessory.handleVerifyM1(verify.m1()))
        assertEquals(setOf("_pd"), m3.keys)
        val keys = verify.finish(accessory.handleVerifyM3(m3))
        assertEquals(true, accessory.verifySignatureValid)
        assertArrayEquals(accessory.lastVerifyKeys!!.decryptKey, keys.encryptKey)
        assertArrayEquals(accessory.lastVerifyKeys!!.encryptKey, keys.decryptKey)
        assertEquals(32, keys.encryptKey.size)
    }

    @Test
    fun rejectsAccessoryWithDifferentIdentifier() {
        accessory.verifyIdOverride = "11:22:33:44:55:66".toByteArray()
        val verify = PairVerify(credentials, SecureRandomSource)
        assertThrows(PairingException.CredentialsRejected::class.java) { verify.m3(accessory.handleVerifyM1(verify.m1())) }
    }

    @Test
    fun rejectsInvalidAccessorySignature() {
        accessory.tamperVerifySignature = true
        val verify = PairVerify(credentials, SecureRandomSource)
        assertThrows(PairingException.SignatureInvalid::class.java) { verify.m3(accessory.handleVerifyM1(verify.m1())) }
    }

    @Test
    fun errorInM2MeansCredentialsWereRejected() {
        accessory.errorOnVerify = 2
        val verify = PairVerify(credentials, SecureRandomSource)
        assertThrows(PairingException.CredentialsRejected::class.java) { verify.m3(accessory.handleVerifyM1(verify.m1())) }
    }

    @Test
    fun errorInM4MeansCredentialsWereRejected() {
        val verify = PairVerify(credentials, SecureRandomSource)
        verify.m3(accessory.handleVerifyM1(verify.m1()))
        val m4 = mapOf("_pd" to Tlv8.encode(listOf(TlvType.STATE to byteArrayOf(4), TlvType.ERROR to byteArrayOf(2))))
        assertThrows(PairingException.CredentialsRejected::class.java) { verify.finish(m4) }
    }

    @Test
    fun wrongControllerKeyIsRejectedByAccessory() {
        val otherIdentity = identity.copy(signingKey = Ed25519KeyPair.generate(SecureRandomSource))
        val forged = Credentials(otherIdentity, credentials.accessoryId, credentials.accessoryPublicKey)
        val verify = PairVerify(forged, SecureRandomSource)
        val m4 = accessory.handleVerifyM3(verify.m3(accessory.handleVerifyM1(verify.m1())))
        assertEquals(false, accessory.verifySignatureValid)
        assertThrows(PairingException.CredentialsRejected::class.java) { verify.finish(m4) }
    }

}
