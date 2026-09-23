package com.jrs8205.appletvremote.discovery

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class WakeOnLanTest {

    @Test
    fun magicPacketIsSixFfBytesThenMacSixteenTimes() {
        val packet = WakeOnLan.magicPacket("AA:BB:CC:DD:EE:FF")
        assertEquals(102, packet.size)
        assertArrayEquals(ByteArray(6) { 0xFF.toByte() }, packet.copyOfRange(0, 6))
        val mac = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
        for (i in 0 until 16) assertArrayEquals("repeat $i", mac, packet.copyOfRange(6 + i * 6, 12 + i * 6))
    }

    @Test
    fun acceptsCommonMacSpellings() {
        assertEquals("AA:BB:CC:DD:EE:FF", WakeOnLan.normalizeMac("aa-bb-cc-dd-ee-ff"))
        assertEquals("AA:BB:CC:DD:EE:FF", WakeOnLan.normalizeMac(" aabbccddeeff "))
        assertEquals("0A:1B:2C:3D:4E:5F", WakeOnLan.normalizeMac("0a:1b:2c:3d:4e:5f"))
        assertTrue(WakeOnLan.isValidMac("0a:1b:2c:3d:4e:5f"))
    }

    @Test
    fun rejectsMalformedMacs() {
        assertNull(WakeOnLan.normalizeMac("AA:BB:CC:DD:EE"))
        assertNull(WakeOnLan.normalizeMac("GG:BB:CC:DD:EE:FF"))
        assertFalse(WakeOnLan.isValidMac("not a mac"))
        assertThrows(IllegalArgumentException::class.java) { WakeOnLan.magicPacket("nope") }
    }

    @Test
    fun sendsThePacketToEveryTargetAndPort() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            receiver.soTimeout = 2000
            WakeOnLan.send("AA:BB:CC:DD:EE:FF", listOf(InetAddress.getLoopbackAddress()), ports = listOf(receiver.localPort))
            val buffer = ByteArray(200)
            val datagram = DatagramPacket(buffer, buffer.size)
            receiver.receive(datagram)
            assertEquals(102, datagram.length)
            assertArrayEquals(WakeOnLan.magicPacket("AA:BB:CC:DD:EE:FF"), buffer.copyOf(datagram.length))
        }
    }
}
