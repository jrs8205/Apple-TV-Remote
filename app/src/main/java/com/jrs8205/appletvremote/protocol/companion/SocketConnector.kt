package com.jrs8205.appletvremote.protocol.companion

import java.net.InetSocketAddress
import java.net.Socket

/** Opens the TCP socket; Android binds it to the Wi-Fi network, tests use the plain connector. */
fun interface SocketConnector {
    fun connect(host: String, port: Int, timeoutMs: Int): Socket
}

object PlainSocketConnector : SocketConnector {
    override fun connect(host: String, port: Int, timeoutMs: Int): Socket = Socket().apply {
        tcpNoDelay = true
        connect(InetSocketAddress(host, port), timeoutMs)
    }
}
