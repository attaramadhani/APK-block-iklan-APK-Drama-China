package com.adblock.vpn.dns

import java.nio.ByteBuffer

object IpUdpPacketBuilder {

    /**
     * Membungkus DNS payload ke dalam paket IPv4 dan UDP yang valid.
     *
     * @param sourceIp IP asal (biasanya IP DNS Server, misal 10.0.0.2).
     * @param destIp IP tujuan (IP client lokal yang melakukan request).
     * @param sourcePort Port asal (port DNS, yaitu 53).
     * @param destPort Port tujuan (port dinamis client, misal 54321).
     * @param dnsPayload Data DNS payload mentah.
     * @return Byte array paket IP lengkap (Header IP + Header UDP + Payload DNS).
     */
    fun buildIpUdpPacket(
        sourceIp: ByteArray,
        destIp: ByteArray,
        sourcePort: Int,
        destPort: Int,
        dnsPayload: ByteArray
    ): ByteArray {
        val ipHeaderLength = 20
        val udpHeaderLength = 8
        val totalLength = ipHeaderLength + udpHeaderLength + dnsPayload.size
        
        val packetBytes = ByteArray(totalLength)
        val buffer = ByteBuffer.wrap(packetBytes)

        // --- 1. MEMBUAT HEADER IPv4 (20 Byte) ---
        // Byte 0: Version (4) & IHL (5 -> 20 byte) = 0x45
        buffer.put(0x45.toByte())
        // Byte 1: Type of Service (TOS) = 0
        buffer.put(0x00.toByte())
        // Byte 2-3: Total Length (IP + UDP + DNS Payload)
        buffer.putShort(totalLength.toShort())
        // Byte 4-5: Identification = 0
        buffer.putShort(0.toShort())
        // Byte 6-7: Flags (Don't Fragment = 0x4000) & Fragment Offset (0)
        buffer.putShort(0x4000.toShort())
        // Byte 8: Time to Live (TTL) = 64
        buffer.put(64.toByte())
        // Byte 9: Protocol (17 = UDP)
        buffer.put(17.toByte())
        // Byte 10-11: Checksum (Awal diisi 0, dihitung di akhir)
        buffer.putShort(0.toShort())
        // Byte 12-15: Source IP Address
        buffer.put(sourceIp)
        // Byte 16-19: Destination IP Address
        buffer.put(destIp)

        // Hitung Checksum Header IP dan masukkan kembali ke buffer pada byte ke 10-11
        val ipChecksum = calculateChecksum(packetBytes, ipHeaderLength)
        buffer.putShort(10, ipChecksum)

        // --- 2. MEMBUAT HEADER UDP (8 Byte) ---
        // Byte 20-21: Source Port
        buffer.putShort(sourcePort.toShort())
        // Byte 22-23: Destination Port
        buffer.putShort(destPort.toShort())
        // Byte 24-25: UDP Length (UDP Header + DNS Payload)
        buffer.putShort((udpHeaderLength + dnsPayload.size).toShort())
        // Byte 26-27: UDP Checksum (Diisi 0x0000 untuk menonaktifkan pemeriksaan checksum UDP di IPv4)
        buffer.putShort(0.toShort())

        // --- 3. MEMASUKKAN PAYLOAD DNS ---
        buffer.put(dnsPayload)

        return packetBytes
    }

    /**
     * Menghitung Checksum Internet (RFC 791) untuk Header IP.
     * Menggunakan penjumlahan komplemen satu dari seluruh word 16-bit di dalam buffer.
     */
    private fun calculateChecksum(buffer: ByteArray, length: Int): Short {
        var sum = 0
        var i = 0
        
        while (i < length) {
            // Gabungkan 2 byte menjadi 16-bit word
            val word = ((buffer[i].toInt() and 0xFF) shl 8) or (buffer[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        
        // Lipat carry 16-bit jika ada overflow
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        
        // Kembalikan negasi komplemen satu (one's complement negation)
        return (sum.inv() and 0xFFFF).toShort()
    }
}
