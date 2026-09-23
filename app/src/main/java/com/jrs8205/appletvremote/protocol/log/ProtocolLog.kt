package com.jrs8205.appletvremote.protocol.log

/** Debug logging hook for the protocol layer. Implementations must never log keys or frame contents. */
fun interface ProtocolLog {
    fun log(message: () -> String)

    companion object {
        val None = ProtocolLog { }
    }
}
