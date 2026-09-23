package com.jrs8205.appletvremote.discovery

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Wake-on-LAN: a UDP "magic packet" (6 × 0xFF followed by the MAC address 16 times) sent to the
 * broadcast address. An Apple TV on Ethernet wakes from deep sleep on it; over Wi-Fi it only works
 * when the device keeps wake-on-wireless active.
 */
object WakeOnLan {

    private val macPattern = Regex("^([0-9A-Fa-f]{2})([:-]?)([0-9A-Fa-f]{2})(\\2[0-9A-Fa-f]{2}){4}$")

    fun isValidMac(text: String): Boolean = macPattern.matches(text.trim())

    /** Randomized addresses (second-least-significant bit of the first byte set) never belong to a physical port. */
    fun isLocallyAdministered(mac: String): Boolean {
        val first = normalizeMac(mac)?.substring(0, 2)?.toInt(16) ?: return false
        return first and 0x02 != 0
    }

    /** Accepts one or more MAC addresses separated by commas or spaces; null when any of them is malformed. */
    fun normalizeMacList(text: String): String? {
        val parts = text.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val normalized = parts.map { normalizeMac(it) ?: return null }
        return normalized.distinct().joinToString(",")
    }

    fun isValidMacList(text: String): Boolean = normalizeMacList(text) != null

    fun normalizeMac(text: String): String? {
        val cleaned = text.trim().replace("-", ":").uppercase()
        val hex = cleaned.replace(":", "")
        if (hex.length != 12 || !hex.all { it in '0'..'9' || it in 'A'..'F' }) return null
        return hex.chunked(2).joinToString(":")
    }

    fun magicPacket(mac: String): ByteArray {
        val normalized = normalizeMac(mac) ?: throw IllegalArgumentException("invalid MAC address: $mac")
        val macBytes = normalized.split(":").map { it.toInt(16).toByte() }.toByteArray()
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (repeat in 0 until 16) macBytes.copyInto(packet, 6 + repeat * 6)
        return packet
    }

    /** Sends the packet to every given target (broadcast addresses and the last known unicast address). */
    fun send(mac: String, targets: List<InetAddress>, ports: List<Int> = listOf(9, 7), bind: (DatagramSocket) -> Unit = {}) {
        val packet = magicPacket(mac)
        DatagramSocket().use { socket ->
            socket.broadcast = true
            bind(socket)
            for (target in targets) {
                for (port in ports) {
                    runCatching { socket.send(DatagramPacket(packet, packet.size, target, port)) }
                }
            }
        }
    }
}
