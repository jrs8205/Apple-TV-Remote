package com.jrs8205.appletvremote.protocol.pairing

import com.jrs8205.appletvremote.protocol.crypto.Ed25519KeyPair

/** This phone's long-term pairing identity: a UUID string and an Ed25519 signing key. */
data class ControllerIdentity(
    val pairingId: String,
    val signingKey: Ed25519KeyPair,
    val displayName: String,
) {
    override fun toString(): String = "ControllerIdentity(pairingId=$pairingId, displayName=$displayName)"
}

/** Everything needed to verify with a paired Apple TV again. */
class Credentials(
    val controller: ControllerIdentity,
    val accessoryId: ByteArray,
    val accessoryPublicKey: ByteArray,
) {
    override fun toString(): String =
        "Credentials(controller=$controller, accessoryId=${String(accessoryId, Charsets.UTF_8)})"
}

class SessionKeys(val encryptKey: ByteArray, val decryptKey: ByteArray)

sealed class PairingException(message: String) : RuntimeException(message) {
    class WrongPin : PairingException("incorrect PIN")
    class Backoff(val retryDelaySeconds: Int?) : PairingException("pairing is temporarily blocked")
    class MaxPeers : PairingException("the Apple TV has no room for another paired remote")
    class MaxTries : PairingException("too many incorrect PIN attempts")
    class Unavailable : PairingException("pairing is not available right now")
    class Busy : PairingException("the Apple TV is busy")
    class Unknown(val code: Int) : PairingException("pairing failed with error $code")
    class ServerProofMismatch : PairingException("the Apple TV could not prove it knows the PIN")
    class SignatureInvalid : PairingException("the Apple TV's signature did not verify")
    class UnexpectedState(expected: Int, actual: Int) : PairingException("expected pairing state $expected, got $actual")
    class CredentialsRejected(detail: String) : PairingException("the Apple TV rejected the stored pairing: $detail")
    class Malformed(detail: String) : PairingException("malformed pairing message: $detail")

    companion object {
        fun fromErrorCode(code: Int, retryDelaySeconds: Int? = null): PairingException = when (code) {
            2 -> WrongPin()
            3 -> Backoff(retryDelaySeconds)
            4 -> MaxPeers()
            5 -> MaxTries()
            6 -> Unavailable()
            7 -> Busy()
            else -> Unknown(code)
        }
    }
}
