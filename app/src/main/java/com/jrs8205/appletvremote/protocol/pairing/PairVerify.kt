package com.jrs8205.appletvremote.protocol.pairing

import com.jrs8205.appletvremote.protocol.crypto.ChaChaBox
import com.jrs8205.appletvremote.protocol.crypto.Ed25519
import com.jrs8205.appletvremote.protocol.crypto.Hkdf
import com.jrs8205.appletvremote.protocol.crypto.Nonce
import com.jrs8205.appletvremote.protocol.crypto.RandomSource
import com.jrs8205.appletvremote.protocol.crypto.X25519KeyPair
import com.jrs8205.appletvremote.protocol.tlv8.Tlv8
import com.jrs8205.appletvremote.protocol.tlv8.TlvType

/**
 * Pair-Verify: proves both sides hold the long-term keys from Pair-Setup and derives the
 * per-connection ChaCha20 keys. The caller sends M1 as PV_START and M3 as PV_NEXT.
 */
class PairVerify(
    private val credentials: Credentials,
    random: RandomSource,
) {
    private val ephemeral = X25519KeyPair.generate(random)
    private var shared: ByteArray? = null

    fun m1(): Map<String, Any?> = linkedMapOf(
        "_pd" to Tlv8.encode(listOf(TlvType.STATE to byteArrayOf(1), TlvType.PUBLIC_KEY to ephemeral.publicKey)),
        "_auTy" to AUTH_TYPE,
    )

    fun m3(m2: Map<*, *>): Map<String, Any?> {
        val tlv = try {
            PairingData.read(m2, expectedState = 2)
        } catch (e: PairingException.Unknown) {
            throw PairingException.CredentialsRejected("error ${e.code} during verify")
        } catch (e: PairingException.WrongPin) {
            throw PairingException.CredentialsRejected("authentication error during verify")
        }
        val accessoryEphemeral = PairingData.require(tlv, TlvType.PUBLIC_KEY, "accessory ephemeral key")
        val encrypted = PairingData.require(tlv, TlvType.ENCRYPTED_DATA, "encrypted data")
        val sharedSecret = try {
            ephemeral.agree(accessoryEphemeral)
        } catch (e: RuntimeException) {
            throw PairingException.Malformed("bad accessory ephemeral key: ${e.message}")
        }
        val box = ChaChaBox(Hkdf.sha512(sharedSecret, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info"))
        val inner = try {
            Tlv8.decode(box.open(Nonce.label("PV-Msg02"), encrypted))
        } catch (e: RuntimeException) {
            throw PairingException.Malformed("could not decrypt verify M2: ${e.message}")
        }
        val accessoryId = PairingData.require(inner, TlvType.IDENTIFIER, "accessory identifier")
        val signature = PairingData.require(inner, TlvType.SIGNATURE, "accessory signature")
        if (!accessoryId.contentEquals(credentials.accessoryId)) {
            throw PairingException.CredentialsRejected("accessory identifier changed")
        }
        val signed = accessoryEphemeral + accessoryId + ephemeral.publicKey
        if (!Ed25519.verify(credentials.accessoryPublicKey, signed, signature)) throw PairingException.SignatureInvalid()
        shared = sharedSecret

        val pairingId = credentials.controller.pairingId.toByteArray(Charsets.UTF_8)
        val ourSignature = credentials.controller.signingKey.sign(ephemeral.publicKey + pairingId + accessoryEphemeral)
        val reply = Tlv8.encode(listOf(TlvType.IDENTIFIER to pairingId, TlvType.SIGNATURE to ourSignature))
        return linkedMapOf(
            "_pd" to Tlv8.encode(
                listOf(TlvType.STATE to byteArrayOf(3), TlvType.ENCRYPTED_DATA to box.seal(Nonce.label("PV-Msg03"), reply)),
            ),
        )
    }

    fun finish(m4: Map<*, *>): SessionKeys {
        val sharedSecret = checkNotNull(shared) { "m3 must run before finish" }
        try {
            PairingData.read(m4, expectedState = 4)
        } catch (e: PairingException.Unknown) {
            throw PairingException.CredentialsRejected("error ${e.code} finishing verify")
        } catch (e: PairingException.WrongPin) {
            throw PairingException.CredentialsRejected("authentication error finishing verify")
        }
        return SessionKeys(
            encryptKey = Hkdf.sha512(sharedSecret, "", "ClientEncrypt-main"),
            decryptKey = Hkdf.sha512(sharedSecret, "", "ServerEncrypt-main"),
        )
    }

    private companion object {
        const val AUTH_TYPE = 4L
    }
}
