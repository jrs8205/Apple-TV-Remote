package com.jrs8205.appletvremote.discovery

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.jrs8205.appletvremote.protocol.companion.SocketConnector
import java.net.InetSocketAddress
import java.net.Socket

/** Binds sockets to the Wi-Fi network so a mobile-data default route never captures LAN traffic. */
class AndroidSocketConnector(private val connectivity: ConnectivityManager) : SocketConnector {

    override fun connect(host: String, port: Int, timeoutMs: Int): Socket {
        val network = wifiNetwork() ?: connectivity.activeNetwork
        val socket = network?.socketFactory?.createSocket() ?: Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        return socket
    }

    private fun wifiNetwork() = connectivity.allNetworks.firstOrNull { network ->
        connectivity.getNetworkCapabilities(network)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } == true
    }
}
