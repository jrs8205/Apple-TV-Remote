package com.jrs8205.appletvremote.protocol.companion

import com.jrs8205.appletvremote.protocol.crypto.RandomSource
import com.jrs8205.appletvremote.protocol.pairing.ControllerIdentity
import com.jrs8205.appletvremote.protocol.pairing.Credentials
import com.jrs8205.appletvremote.protocol.pairing.PairSetup

/** Runs Pair-Setup over an open connection: [start] makes the TV show its PIN, [finish] completes with it. */
class PairingSession(
    private val connection: CompanionConnection,
    identity: ControllerIdentity,
    random: RandomSource,
) {
    private val setup = PairSetup(identity, random)
    private var m2: Map<*, *>? = null

    suspend fun start() {
        m2 = connection.pairingExchange(FrameType.PS_START, setup.m1(), FrameType.PS_NEXT)
    }

    suspend fun finish(pin: String): Credentials {
        val m2 = checkNotNull(m2) { "start must run before finish" }
        val m4 = connection.pairingExchange(FrameType.PS_NEXT, setup.m3(m2, pin), FrameType.PS_NEXT)
        val m6 = connection.pairingExchange(FrameType.PS_NEXT, setup.m5(m4), FrameType.PS_NEXT)
        return setup.finish(m6)
    }
}
