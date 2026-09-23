package com.jrs8205.appletvremote.protocol.pairing

import com.jrs8205.appletvremote.protocol.crypto.ChaChaBox
import com.jrs8205.appletvremote.protocol.crypto.Ed25519
import com.jrs8205.appletvremote.protocol.crypto.Hkdf
import com.jrs8205.appletvremote.protocol.crypto.Nonce
import com.jrs8205.appletvremote.protocol.crypto.RandomSource
import com.jrs8205.appletvremote.protocol.opack.Opack
import com.jrs8205.appletvremote.protocol.srp.SrpClient
import com.jrs8205.appletvremote.protocol.srp.SrpException
import com.jrs8205.appletvremote.protocol.tlv8.Tlv8
import com.jrs8205.appletvremote.protocol.tlv8.TlvType

/**
 * Pair-Setup with a PIN (SRP-6a), producing [Credentials]. The caller sends the returned maps as
 * PS_START (M1) and PS_NEXT (M3, M5) frames and feeds the decoded replies back in order.
 */
class PairSetup(
    private val identity: ControllerIdentity,
    private val random: RandomSource,
) {
    private var srp: SrpClient? = null
    private var encryptionBox: ChaChaBox? = null

    fun m1(): Map<String, Any?> = message(TlvType.METHOD to byteArrayOf(0), TlvType.STATE to byteArrayOf(1))

    /** The PIN appears on the TV once it has answered M1, so it is supplied here rather than up front. */
    fun m3(m2: Map<*, *>, pin: String): Map<String, Any?> {
        val tlv = PairingData.read(m2, expectedState = 2)
        val salt = PairingData.require(tlv, TlvType.SALT, "salt")
        val serverPublic = PairingData.require(tlv, TlvType.PUBLIC_KEY, "server public key")
        val client = SrpClient(USERNAME, pin, random)
        srp = client
        val proofs = try {
            client.computeProofs(salt, serverPublic)
        } catch (e: SrpException) {
            throw PairingException.Malformed(e.message ?: "SRP failure")
        }
        return message(
            TlvType.STATE to byteArrayOf(3),
            TlvType.PUBLIC_KEY to proofs.clientPublic,
            TlvType.PROOF to proofs.clientProof,
        )
    }

    fun m5(m4: Map<*, *>): Map<String, Any?> {
        val tlv = PairingData.read(m4, expectedState = 4)
        val srp = checkNotNull(srp) { "m3 must run before m5" }
        val serverProof = PairingData.require(tlv, TlvType.PROOF, "server proof")
        if (!srp.verifyServerProof(serverProof)) throw PairingException.ServerProofMismatch()

        val sessionKey = srp.sessionKey
        val controllerX = Hkdf.sha512(sessionKey, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
        val pairingId = identity.pairingId.toByteArray(Charsets.UTF_8)
        val publicKey = identity.signingKey.publicKey
        val signature = identity.signingKey.sign(controllerX + pairingId + publicKey)
        val inner = Tlv8.encode(
            listOf(
                TlvType.IDENTIFIER to pairingId,
                TlvType.PUBLIC_KEY to publicKey,
                TlvType.SIGNATURE to signature,
                TlvType.NAME to Opack.encode(mapOf("name" to identity.displayName)),
            ),
        )
        val box = ChaChaBox(Hkdf.sha512(sessionKey, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info"))
        encryptionBox = box
        return message(
            TlvType.STATE to byteArrayOf(5),
            TlvType.ENCRYPTED_DATA to box.seal(Nonce.label("PS-Msg05"), inner),
        )
    }

    fun finish(m6: Map<*, *>): Credentials {
        val tlv = PairingData.read(m6, expectedState = 6)
        val box = checkNotNull(encryptionBox) { "m5 must run before finish" }
        val srp = checkNotNull(srp) { "m3 must run before finish" }
        val encrypted = PairingData.require(tlv, TlvType.ENCRYPTED_DATA, "encrypted data")
        val inner = try {
            Tlv8.decode(box.open(Nonce.label("PS-Msg06"), encrypted))
        } catch (e: RuntimeException) {
            throw PairingException.Malformed("could not decrypt M6: ${e.message}")
        }
        val accessoryId = PairingData.require(inner, TlvType.IDENTIFIER, "accessory identifier")
        val accessoryPublicKey = PairingData.require(inner, TlvType.PUBLIC_KEY, "accessory public key")
        val signature = PairingData.require(inner, TlvType.SIGNATURE, "accessory signature")
        val accessoryX = Hkdf.sha512(srp.sessionKey, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
        if (!Ed25519.verify(accessoryPublicKey, accessoryX + accessoryId + accessoryPublicKey, signature)) {
            throw PairingException.SignatureInvalid()
        }
        return Credentials(identity, accessoryId, accessoryPublicKey)
    }

    private fun message(vararg items: Pair<Int, ByteArray>): Map<String, Any?> =
        linkedMapOf("_pd" to Tlv8.encode(items.toList()), "_pwTy" to PIN_AUTH_TYPE)

    private companion object {
        const val USERNAME = "Pair-Setup"
        const val PIN_AUTH_TYPE = 1L
    }
}
