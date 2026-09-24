package com.jrs8205.appletvremote.data

import com.jrs8205.appletvremote.protocol.crypto.Ed25519KeyPair
import com.jrs8205.appletvremote.protocol.pairing.ControllerIdentity
import com.jrs8205.appletvremote.protocol.pairing.Credentials

class PairedDevice(
    val name: String,
    val host: String,
    val port: Int,
    val credentials: Credentials,
) {
    /** True when both describe the same pairing at the same address, regardless of object identity. */
    fun sameAs(other: PairedDevice?): Boolean =
        other != null && host == other.host && port == other.port &&
            credentials.controller.pairingId == other.credentials.controller.pairingId &&
            credentials.accessoryId.contentEquals(other.credentials.accessoryId)
}

/** Wraps and unwraps the one real secret, the Ed25519 seed; Android uses the Keystore, tests a pass-through. */
interface SecretCipher {
    fun wrap(secret: ByteArray): String
    fun unwrap(wrapped: String): ByteArray
}

/** The persisted form: public fields as hex, the seed wrapped by a [SecretCipher]. */
data class StoredDevice(
    val name: String,
    val host: String,
    val port: Int,
    val pairingId: String,
    val displayName: String,
    val wrappedSeed: String,
    val accessoryIdHex: String,
    val accessoryPublicKeyHex: String,
)

class PairedDeviceCodec(private val cipher: SecretCipher) {

    fun encode(device: PairedDevice): StoredDevice = StoredDevice(
        name = device.name,
        host = device.host,
        port = device.port,
        pairingId = device.credentials.controller.pairingId,
        displayName = device.credentials.controller.displayName,
        wrappedSeed = cipher.wrap(device.credentials.controller.signingKey.seed),
        accessoryIdHex = device.credentials.accessoryId.toHexString(),
        accessoryPublicKeyHex = device.credentials.accessoryPublicKey.toHexString(),
    )

    /** Returns null when the stored data cannot be turned back into usable credentials. */
    fun decode(stored: StoredDevice): PairedDevice? = try {
        val identity = ControllerIdentity(
            pairingId = stored.pairingId,
            signingKey = Ed25519KeyPair(cipher.unwrap(stored.wrappedSeed)),
            displayName = stored.displayName,
        )
        PairedDevice(
            name = stored.name,
            host = stored.host,
            port = stored.port,
            credentials = Credentials(identity, stored.accessoryIdHex.hexToByteArray(), stored.accessoryPublicKeyHex.hexToByteArray()),
        )
    } catch (_: Exception) {
        null
    }
}
