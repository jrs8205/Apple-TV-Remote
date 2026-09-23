package com.jrs8205.appletvremote.protocol.pairing

import com.jrs8205.appletvremote.protocol.crypto.Ed25519KeyPair
import com.jrs8205.appletvremote.protocol.crypto.SecureRandomSource
import com.jrs8205.appletvremote.protocol.tlv8.Tlv8
import com.jrs8205.appletvremote.protocol.tlv8.TlvType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PairSetupTest {

    private val identity = ControllerIdentity(
        pairingId = "3b1a0c2e-7d4f-4a2b-9c1d-5e6f7a8b9c0d",
        signingKey = Ed25519KeyPair.generate(SecureRandomSource),
        displayName = "Apple TV Remote",
    )

    private fun runSetup(accessory: FakeAccessory, pin: String = "3939"): Credentials {
        val setup = PairSetup(identity, SecureRandomSource)
        val m2 = accessory.handleSetupM1(setup.m1())
        val m4 = accessory.handleSetupM3(setup.m3(m2, pin))
        val m6 = accessory.handleSetupM5(setup.m5(m4))
        return setup.finish(m6)
    }

    @Test
    fun m1CarriesMethodStateAndPinAuthType() {
        val m1 = PairSetup(identity, SecureRandomSource).m1()
        assertEquals(1L, m1["_pwTy"])
        val tlv = Tlv8.decode(m1["_pd"] as ByteArray)
        assertArrayEquals(byteArrayOf(0), tlv.getValue(TlvType.METHOD))
        assertArrayEquals(byteArrayOf(1), tlv.getValue(TlvType.STATE))
        assertEquals(setOf("_pd", "_pwTy"), m1.keys)
    }

    @Test
    fun m3CarriesPublicKeyAndProof() {
        val accessory = FakeAccessory()
        val setup = PairSetup(identity, SecureRandomSource)
        val m3 = setup.m3(accessory.handleSetupM1(setup.m1()), "3939")
        assertEquals(1L, m3["_pwTy"])
        val tlv = Tlv8.decode(m3["_pd"] as ByteArray)
        assertArrayEquals(byteArrayOf(3), tlv.getValue(TlvType.STATE))
        assertEquals(384, tlv.getValue(TlvType.PUBLIC_KEY).size)
        assertEquals(64, tlv.getValue(TlvType.PROOF).size)
    }

    @Test
    fun completesPairingAndReturnsAccessoryCredentials() {
        val accessory = FakeAccessory()
        val credentials = runSetup(accessory)
        assertArrayEquals(accessory.accessoryId, credentials.accessoryId)
        assertArrayEquals(accessory.publicKey, credentials.accessoryPublicKey)
        assertEquals(identity, credentials.controller)
        assertArrayEquals(identity.pairingId.toByteArray(), accessory.controllerPairingId)
        assertArrayEquals(identity.signingKey.publicKey, accessory.controllerPublicKey)
        assertEquals(true, accessory.controllerSignatureValid)
    }

    @Test
    fun m5IncludesDisplayNameInOpackExtraData() {
        val accessory = FakeAccessory()
        val setup = PairSetup(identity, SecureRandomSource)
        val m4 = accessory.handleSetupM3(setup.m3(accessory.handleSetupM1(setup.m1()), "3939"))
        val m5 = setup.m5(m4)
        val tlv = Tlv8.decode(m5["_pd"] as ByteArray)
        assertArrayEquals(byteArrayOf(5), tlv.getValue(TlvType.STATE))
        assertTrue(tlv.containsKey(TlvType.ENCRYPTED_DATA))
        assertEquals(1L, m5["_pwTy"])
    }

    @Test
    fun wrongPinSurfacesAsWrongPin() {
        val accessory = FakeAccessory(pin = "1111")
        val setup = PairSetup(identity, SecureRandomSource)
        val m4 = accessory.handleSetupM3(setup.m3(accessory.handleSetupM1(setup.m1()), "2222"))
        assertThrows(PairingException.WrongPin::class.java) { setup.m5(m4) }
    }

    @Test
    fun errorCodesMapToTypedExceptions() {
        fun errorFor(code: Int, retry: Int? = null): PairingException {
            val accessory = FakeAccessory().apply { errorOnM3 = code; retryDelayOnM3 = retry }
            val setup = PairSetup(identity, SecureRandomSource)
            val m4 = accessory.handleSetupM3(setup.m3(accessory.handleSetupM1(setup.m1()), "3939"))
            return assertThrows(PairingException::class.java) { setup.m5(m4) }
        }
        assertTrue(errorFor(2) is PairingException.WrongPin)
        val backoff = errorFor(3, retry = 30)
        assertTrue(backoff is PairingException.Backoff)
        assertEquals(30, (backoff as PairingException.Backoff).retryDelaySeconds)
        assertTrue(errorFor(4) is PairingException.MaxPeers)
        assertTrue(errorFor(5) is PairingException.MaxTries)
        assertTrue(errorFor(6) is PairingException.Unavailable)
        assertTrue(errorFor(7) is PairingException.Busy)
        assertTrue(errorFor(0x42) is PairingException.Unknown)
    }

    @Test
    fun tamperedServerProofIsRejected() {
        val accessory = FakeAccessory().apply { tamperM4Proof = true }
        val setup = PairSetup(identity, SecureRandomSource)
        val m4 = accessory.handleSetupM3(setup.m3(accessory.handleSetupM1(setup.m1()), "3939"))
        assertThrows(PairingException.ServerProofMismatch::class.java) { setup.m5(m4) }
    }

    @Test
    fun tamperedAccessorySignatureIsRejected() {
        val accessory = FakeAccessory().apply { tamperM6Signature = true }
        val setup = PairSetup(identity, SecureRandomSource)
        val m4 = accessory.handleSetupM3(setup.m3(accessory.handleSetupM1(setup.m1()), "3939"))
        val m6 = accessory.handleSetupM5(setup.m5(m4))
        assertThrows(PairingException.SignatureInvalid::class.java) { setup.finish(m6) }
    }

    @Test
    fun twoPairingsUseDifferentSrpExponents() {
        val accessory = FakeAccessory()
        val first = PairSetup(identity, SecureRandomSource)
        val second = PairSetup(identity, SecureRandomSource)
        val a1 = Tlv8.decode(first.m3(accessory.handleSetupM1(first.m1()), "3939")["_pd"] as ByteArray).getValue(TlvType.PUBLIC_KEY)
        val a2 = Tlv8.decode(second.m3(accessory.handleSetupM1(second.m1()), "3939")["_pd"] as ByteArray).getValue(TlvType.PUBLIC_KEY)
        assertFalse(a1.contentEquals(a2))
    }

    @Test
    fun missingPairingDataIsMalformed() {
        val setup = PairSetup(identity, SecureRandomSource)
        setup.m1()
        assertThrows(PairingException.Malformed::class.java) { setup.m3(mapOf("_x" to 1L), "3939") }
    }

    @Test
    fun unexpectedStateIsRejected() {
        val setup = PairSetup(identity, SecureRandomSource)
        setup.m1()
        val bogus = mapOf("_pd" to Tlv8.encode(listOf(TlvType.STATE to byteArrayOf(4), TlvType.SALT to ByteArray(16), TlvType.PUBLIC_KEY to ByteArray(384) { 1 })))
        assertThrows(PairingException.UnexpectedState::class.java) { setup.m3(bogus, "3939") }
    }

    @Test
    fun credentialsToStringHidesSecrets() {
        val credentials = runSetup(FakeAccessory())
        val text = credentials.toString()
        assertFalse(text.contains(identity.signingKey.seed.toHexString()))
        assertTrue(text.contains(identity.pairingId))
    }
}
