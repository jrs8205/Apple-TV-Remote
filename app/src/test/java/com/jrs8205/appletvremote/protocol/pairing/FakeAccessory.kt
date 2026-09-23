package com.jrs8205.appletvremote.protocol.pairing

import com.jrs8205.appletvremote.protocol.crypto.ChaChaBox
import com.jrs8205.appletvremote.protocol.crypto.Ed25519
import com.jrs8205.appletvremote.protocol.crypto.Ed25519KeyPair
import com.jrs8205.appletvremote.protocol.crypto.Hkdf
import com.jrs8205.appletvremote.protocol.crypto.Nonce
import com.jrs8205.appletvremote.protocol.crypto.SecureRandomSource
import com.jrs8205.appletvremote.protocol.crypto.X25519KeyPair
import com.jrs8205.appletvremote.protocol.srp.TestSrpServer
import com.jrs8205.appletvremote.protocol.tlv8.Tlv8
import com.jrs8205.appletvremote.protocol.tlv8.TlvType

/** The Apple TV side of Pair-Setup and Pair-Verify, written independently of the client classes under test. */
class FakeAccessory(
    private val pin: String = "3939",
    val accessoryId: ByteArray = "AA:BB:CC:DD:EE:FF".toByteArray(),
    private val signingKey: Ed25519KeyPair = Ed25519KeyPair.generate(SecureRandomSource),
) {
    val publicKey: ByteArray get() = signingKey.publicKey

    var tamperM4Proof = false
    var tamperM6Signature = false
    var errorOnM3: Int? = null
    var retryDelayOnM3: Int? = null
    var verifyIdOverride: ByteArray? = null
    var tamperVerifySignature = false
    var errorOnVerify: Int? = null

    var controllerPairingId: ByteArray? = null
        private set
    var controllerPublicKey: ByteArray? = null
        private set
    var controllerSignatureValid: Boolean? = null
        private set
    var verifySignatureValid: Boolean? = null
        private set
    var lastVerifyKeys: SessionKeys? = null
        private set

    private lateinit var srp: TestSrpServer
    private lateinit var setupKey: ByteArray

    fun handleSetupM1(message: Map<*, *>): Map<String, Any?> {
        val tlv = pairingData(message)
        check(tlv.getValue(TlvType.STATE).contentEquals(byteArrayOf(1)))
        check(tlv.getValue(TlvType.METHOD).contentEquals(byteArrayOf(0)))
        check(message["_pwTy"] == 1L || message["_pwTy"] == 1)
        srp = TestSrpServer("Pair-Setup", pin, SecureRandomSource.nextBytes(16), SecureRandomSource.nextBytes(32))
        return reply(TlvType.STATE to byteArrayOf(2), TlvType.SALT to srp.salt, TlvType.PUBLIC_KEY to srp.publicKey)
    }

    fun handleSetupM3(message: Map<*, *>): Map<String, Any?> {
        val tlv = pairingData(message)
        check(tlv.getValue(TlvType.STATE).contentEquals(byteArrayOf(3)))
        errorOnM3?.let { code ->
            val items = mutableListOf(TlvType.STATE to byteArrayOf(4), TlvType.ERROR to byteArrayOf(code.toByte()))
            retryDelayOnM3?.let { items += TlvType.RETRY_DELAY to byteArrayOf((it and 0xFF).toByte(), (it shr 8).toByte()) }
            return reply(*items.toTypedArray())
        }
        val valid = srp.verifyClientProof(tlv.getValue(TlvType.PUBLIC_KEY), tlv.getValue(TlvType.PROOF))
        if (!valid) return reply(TlvType.STATE to byteArrayOf(4), TlvType.ERROR to byteArrayOf(2))
        setupKey = srp.sessionKey!!
        val proof = srp.serverProof!!.copyOf()
        if (tamperM4Proof) proof[0] = (proof[0].toInt() xor 0x55).toByte()
        return reply(TlvType.STATE to byteArrayOf(4), TlvType.PROOF to proof)
    }

    fun handleSetupM5(message: Map<*, *>): Map<String, Any?> {
        val tlv = pairingData(message)
        check(tlv.getValue(TlvType.STATE).contentEquals(byteArrayOf(5)))
        val box = ChaChaBox(Hkdf.sha512(setupKey, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info"))
        val inner = Tlv8.decode(box.open(Nonce.label("PS-Msg05"), tlv.getValue(TlvType.ENCRYPTED_DATA)))
        controllerPairingId = inner.getValue(TlvType.IDENTIFIER)
        controllerPublicKey = inner.getValue(TlvType.PUBLIC_KEY)
        val controllerX = Hkdf.sha512(setupKey, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
        controllerSignatureValid = Ed25519.verify(
            controllerPublicKey!!,
            controllerX + controllerPairingId!! + controllerPublicKey!!,
            inner.getValue(TlvType.SIGNATURE),
        )
        val accessoryX = Hkdf.sha512(setupKey, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
        val signature = signingKey.sign(accessoryX + accessoryId + signingKey.publicKey)
        if (tamperM6Signature) signature[10] = (signature[10].toInt() xor 0x01).toByte()
        val innerReply = Tlv8.encode(
            listOf(
                TlvType.IDENTIFIER to accessoryId,
                TlvType.PUBLIC_KEY to signingKey.publicKey,
                TlvType.SIGNATURE to signature,
            ),
        )
        val encrypted = box.seal(Nonce.label("PS-Msg06"), innerReply)
        return reply(TlvType.STATE to byteArrayOf(6), TlvType.ENCRYPTED_DATA to encrypted)
    }

    fun handleVerifyM1(message: Map<*, *>): Map<String, Any?> {
        val tlv = pairingData(message)
        check(tlv.getValue(TlvType.STATE).contentEquals(byteArrayOf(1)))
        check(message["_auTy"] == 4L || message["_auTy"] == 4)
        errorOnVerify?.let { return reply(TlvType.STATE to byteArrayOf(2), TlvType.ERROR to byteArrayOf(it.toByte())) }
        val controllerEphemeral = tlv.getValue(TlvType.PUBLIC_KEY)
        val ephemeral = X25519KeyPair.generate(SecureRandomSource)
        val shared = ephemeral.agree(controllerEphemeral)
        verifyShared = shared
        verifyControllerEphemeral = controllerEphemeral
        verifyAccessoryEphemeral = ephemeral.publicKey
        val id = verifyIdOverride ?: accessoryId
        val signature = signingKey.sign(ephemeral.publicKey + id + controllerEphemeral)
        if (tamperVerifySignature) signature[0] = (signature[0].toInt() xor 0x01).toByte()
        val inner = Tlv8.encode(listOf(TlvType.IDENTIFIER to id, TlvType.SIGNATURE to signature))
        val box = ChaChaBox(Hkdf.sha512(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info"))
        return reply(
            TlvType.STATE to byteArrayOf(2),
            TlvType.PUBLIC_KEY to ephemeral.publicKey,
            TlvType.ENCRYPTED_DATA to box.seal(Nonce.label("PV-Msg02"), inner),
        )
    }

    fun handleVerifyM3(message: Map<*, *>): Map<String, Any?> {
        val tlv = pairingData(message)
        check(tlv.getValue(TlvType.STATE).contentEquals(byteArrayOf(3)))
        val box = ChaChaBox(Hkdf.sha512(verifyShared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info"))
        val inner = Tlv8.decode(box.open(Nonce.label("PV-Msg03"), tlv.getValue(TlvType.ENCRYPTED_DATA)))
        val controllerId = inner.getValue(TlvType.IDENTIFIER)
        verifySignatureValid = controllerPairingId.contentEquals(controllerId) && Ed25519.verify(
            controllerPublicKey!!,
            verifyControllerEphemeral + controllerId + verifyAccessoryEphemeral,
            inner.getValue(TlvType.SIGNATURE),
        )
        if (verifySignatureValid != true) return reply(TlvType.STATE to byteArrayOf(4), TlvType.ERROR to byteArrayOf(2))
        lastVerifyKeys = SessionKeys(
            encryptKey = Hkdf.sha512(verifyShared, "", "ServerEncrypt-main"),
            decryptKey = Hkdf.sha512(verifyShared, "", "ClientEncrypt-main"),
        )
        return reply(TlvType.STATE to byteArrayOf(4))
    }

    private lateinit var verifyShared: ByteArray
    private lateinit var verifyControllerEphemeral: ByteArray
    private lateinit var verifyAccessoryEphemeral: ByteArray

    private fun pairingData(message: Map<*, *>): Map<Int, ByteArray> = Tlv8.decode(message["_pd"] as ByteArray)

    private fun reply(vararg items: Pair<Int, ByteArray>): Map<String, Any?> = mapOf("_pd" to Tlv8.encode(items.toList()))
}
