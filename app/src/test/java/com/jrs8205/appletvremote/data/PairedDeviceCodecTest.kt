package com.jrs8205.appletvremote.data

import com.jrs8205.appletvremote.protocol.crypto.Ed25519KeyPair
import com.jrs8205.appletvremote.protocol.crypto.SecureRandomSource
import com.jrs8205.appletvremote.protocol.pairing.ControllerIdentity
import com.jrs8205.appletvremote.protocol.pairing.Credentials
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairedDeviceCodecTest {

    private class PassThroughCipher : SecretCipher {
        override fun wrap(secret: ByteArray): String = "plain:" + secret.toHexString()
        override fun unwrap(wrapped: String): ByteArray = wrapped.removePrefix("plain:").hexToByteArray()
    }

    private class BrokenCipher : SecretCipher {
        override fun wrap(secret: ByteArray): String = "x"
        override fun unwrap(wrapped: String): ByteArray = throw IllegalStateException("key gone")
    }

    private val device = PairedDevice(
        name = "Living Room",
        host = "192.168.1.20",
        port = 49152,
        credentials = Credentials(
            controller = ControllerIdentity("3b1a0c2e-7d4f-4a2b-9c1d-5e6f7a8b9c0d", Ed25519KeyPair.generate(SecureRandomSource), "Apple TV Remote"),
            accessoryId = "AA:BB:CC:DD:EE:FF".toByteArray(),
            accessoryPublicKey = ByteArray(32) { it.toByte() },
        ),
    )

    @Test
    fun roundTripsThroughStoredFields() {
        val codec = PairedDeviceCodec(PassThroughCipher())
        val stored = codec.encode(device)
        val decoded = codec.decode(stored)!!
        assertEquals(device.name, decoded.name)
        assertEquals(device.host, decoded.host)
        assertEquals(device.port, decoded.port)
        assertEquals(device.credentials.controller.pairingId, decoded.credentials.controller.pairingId)
        assertEquals(device.credentials.controller.displayName, decoded.credentials.controller.displayName)
        assertArrayEquals(device.credentials.controller.signingKey.seed, decoded.credentials.controller.signingKey.seed)
        assertArrayEquals(device.credentials.accessoryId, decoded.credentials.accessoryId)
        assertArrayEquals(device.credentials.accessoryPublicKey, decoded.credentials.accessoryPublicKey)
    }

    @Test
    fun onlyTheSigningSeedIsWrapped() {
        val codec = PairedDeviceCodec(PassThroughCipher())
        val stored = codec.encode(device)
        assertTrue(stored.wrappedSeed.startsWith("plain:"))
        assertEquals(device.credentials.accessoryPublicKey.toHexString(), stored.accessoryPublicKeyHex)
        assertEquals(device.credentials.accessoryId.toHexString(), stored.accessoryIdHex)
    }

    @Test
    fun unwrapFailureMeansNoDevice() {
        val stored = PairedDeviceCodec(PassThroughCipher()).encode(device)
        assertNull(PairedDeviceCodec(BrokenCipher()).decode(stored))
    }

    @Test
    fun corruptHexMeansNoDevice() {
        val stored = PairedDeviceCodec(PassThroughCipher()).encode(device).copy(accessoryPublicKeyHex = "zz")
        assertNull(PairedDeviceCodec(PassThroughCipher()).decode(stored))
    }
}
