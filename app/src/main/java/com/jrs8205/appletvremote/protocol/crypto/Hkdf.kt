package com.jrs8205.appletvremote.protocol.crypto

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

object Hkdf {

    fun sha512(ikm: ByteArray, salt: String, info: String, length: Int = 32): ByteArray =
        sha512(ikm, salt.toByteArray(Charsets.US_ASCII), info.toByteArray(Charsets.US_ASCII), length)

    fun sha512(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        require(length > 0) { "length must be positive" }
        val generator = HKDFBytesGenerator(SHA512Digest())
        generator.init(HKDFParameters(ikm, salt, info))
        val output = ByteArray(length)
        generator.generateBytes(output, 0, length)
        return output
    }
}
