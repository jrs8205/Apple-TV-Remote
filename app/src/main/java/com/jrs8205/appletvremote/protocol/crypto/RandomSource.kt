package com.jrs8205.appletvremote.protocol.crypto

import java.security.SecureRandom

fun interface RandomSource {
    fun nextBytes(count: Int): ByteArray
}

object SecureRandomSource : RandomSource {
    private val random = SecureRandom()

    override fun nextBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }
}
