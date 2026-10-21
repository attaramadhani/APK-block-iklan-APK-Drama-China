package com.adblock.vpn.dns

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class IpUdpPacketBuilderTest {

    @Test
    fun testBuildIpUdpPacket() {
        val sourceIp = byteArrayOf(10, 0, 0, 2)
        val destIp = byteArrayOf(192.toByte(), 168.toByte(), 1, 15)
        val sourcePort = 53
        val destPort = 54321
        val dnsPayload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)

        val packet = IpUdpPacketBuilder.buildIpUdpPacket(
            sourceIp = sourceIp,
            destIp = destIp,
            sourcePort = sourcePort,
            destPort = destPort,
            dnsPayload = dnsPayload
        )

        // Ukuran paket = 20 (IP) + 8 (UDP) + 5 (Payload) = 33 byte
        assertEquals(33, packet.size)

        val buffer = ByteBuffer.wrap(packet)

        // Verifikasi Header IP
        assertEquals(0x45.toByte(), packet[0]) // Version 4, IHL 5
        assertEquals(33.toShort(), buffer.getShort(2)) // Total Length
        assertEquals(17.toByte(), packet[9]) // Protocol (17 = UDP)

        // Verifikasi Source IP & Dest IP
        val actualSourceIp = ByteArray(4)
        val actualDestIp = ByteArray(4)
        System.arraycopy(packet, 12, actualSourceIp, 0, 4)
        System.arraycopy(packet, 16, actualDestIp, 0, 4)
        assertArrayEquals(sourceIp, actualSourceIp)
        assertArrayEquals(destIp, actualDestIp)

        // Verifikasi Header UDP
        assertEquals(sourcePort.toShort(), buffer.getShort(20))
        assertEquals(destPort.toShort(), buffer.getShort(22))
        assertEquals((8 + dnsPayload.size).toShort(), buffer.getShort(24))

        // Verifikasi IP Checksum Keabsahan secara matematis (RFC 791)
        // Jika checksum benar, total komplemen satu dari seluruh 16-bit word di header IP harus menghasilkan 0xFFFF (atau 0 setelah negasi)
        val ipHeader = ByteArray(20)
        System.arraycopy(packet, 0, ipHeader, 0, 20)
        assertTrue("Checksum IP header tidak valid menurut standar RFC 791", verifyIpChecksum(ipHeader))
    }

    private fun verifyIpChecksum(ipHeader: ByteArray): Boolean {
        var sum = 0
        var i = 0
        while (i < ipHeader.size) {
            val word = ((ipHeader[i].toInt() and 0xFF) shl 8) or (ipHeader[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum == 0xFFFF
    }
}
