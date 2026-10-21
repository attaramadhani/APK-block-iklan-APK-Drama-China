package com.adblock.vpn.dns

import org.junit.Assert.*
import org.junit.Test

class DnsPacketParserTest {

    @Test
    fun testParseDomainName() {
        // Mock DNS QNAME untuk "ads.google.com" -> 3ads6google3com0
        val qnameBytes = byteArrayOf(
            0x03, 'a'.toByte(), 'd'.toByte(), 's'.toByte(),
            0x06, 'g'.toByte(), 'o'.toByte(), 'o'.toByte(), 'g'.toByte(), 'l'.toByte(), 'e'.toByte(),
            0x03, 'c'.toByte(), 'o'.toByte(), 'm'.toByte(),
            0x00
        )
        
        // Buat dummy DNS payload (12 byte header + QNAME + 4 byte Qtype/Qclass)
        val dnsPayload = ByteArray(12 + qnameBytes.size + 4)
        System.arraycopy(qnameBytes, 0, dnsPayload, 12, qnameBytes.size)

        val (domain, nextOffset) = DnsPacketParser.parseDomainName(dnsPayload, 12)
        assertEquals("ads.google.com", domain)
        assertEquals(12 + qnameBytes.size, nextOffset)
    }

    @Test
    fun testIsAdDomain() {
        // Cek domain iklan/tracker (harus diblokir karena tidak ada di whitelist)
        assertTrue(DnsPacketParser.isAdDomain("doubleclick.net"))
        
        // Cek subdomain iklan
        assertTrue(DnsPacketParser.isAdDomain("sub.doubleclick.net"))

        // Cek domain baru (playable ads & trackers)
        assertTrue(DnsPacketParser.isAdDomain("sg-gcp-ad-track-sdk-asia-southeast1-b.mtgglobals.com"))
        assertTrue(DnsPacketParser.isAdDomain("mtgglobals.com"))
        assertTrue(DnsPacketParser.isAdDomain("mobvista.com"))
        assertTrue(DnsPacketParser.isAdDomain("spadsync.com"))
        assertTrue(DnsPacketParser.isAdDomain("google-analytics.com"))
        assertTrue(DnsPacketParser.isAdDomain("gecko-sg.byteoversea.com"))
        assertTrue(DnsPacketParser.isAdDomain("sf-tk-sg.ibytedtos.com"))
        assertTrue(DnsPacketParser.isAdDomain("sf16-muse-va.ibytedtos.com"))
        assertTrue(DnsPacketParser.isAdDomain("mon-va.tiktokv.com"))
        assertTrue(DnsPacketParser.isAdDomain("d1tru86qrby720.cloudfront.net"))
        assertTrue(DnsPacketParser.isAdDomain("api16-access-ttp.tiktokpangle-b.us"))
        assertTrue(DnsPacketParser.isAdDomain("sdkapi.dsp-api.moloco.com"))

        // Cek domain luar yang bukan bagian dari whitelist Melolo (harus diblokir secara default)
        assertTrue(DnsPacketParser.isAdDomain("github.com"))

        // Cek domain Melolo/sistem yang ada di whitelist (harus diloloskan / false)
        assertFalse(DnsPacketParser.isAdDomain("google.com"))
        assertFalse(DnsPacketParser.isAdDomain("ads.google.com"))
        assertFalse(DnsPacketParser.isAdDomain("melolostatic.com"))
        assertFalse(DnsPacketParser.isAdDomain("v16-ml.melolostatic.com"))
        assertFalse(DnsPacketParser.isAdDomain("tnc16-alisg.isnssdk.com"))
        assertFalse(DnsPacketParser.isAdDomain(""))

        // Cek custom whitelist (setelah dimasukkan, harus diloloskan / false)
        DnsPacketParser.setCustomWhitelist(setOf("github.com"))
        assertFalse(DnsPacketParser.isAdDomain("github.com"))
        assertFalse(DnsPacketParser.isAdDomain("sub.github.com"))
        DnsPacketParser.setCustomWhitelist(emptySet())
        assertTrue(DnsPacketParser.isAdDomain("github.com"))

        // Cek custom blacklist (setelah dimasukkan, harus diblokir / true meskipun ada di whitelist)
        DnsPacketParser.setCustomWhitelist(setOf("google.com"))
        assertFalse(DnsPacketParser.isAdDomain("google.com")) // Diizinkan karena whitelist kustom
        
        DnsPacketParser.setCustomBlacklist(setOf("google.com"))
        assertTrue(DnsPacketParser.isAdDomain("google.com")) // Diblokir karena blacklist kustom memiliki prioritas lebih tinggi
        assertTrue(DnsPacketParser.isAdDomain("sub.google.com")) // Subdomain juga diblokir
        
        DnsPacketParser.setCustomBlacklist(emptySet())
        DnsPacketParser.setCustomWhitelist(emptySet())
    }

    @Test
    fun testCreateBlockResponse() {
        // Mock full DNS request untuk A record "ads.google.com"
        val qnameBytes = byteArrayOf(
            0x03, 'a'.toByte(), 'd'.toByte(), 's'.toByte(),
            0x06, 'g'.toByte(), 'o'.toByte(), 'o'.toByte(), 'g'.toByte(), 'l'.toByte(), 'e'.toByte(),
            0x03, 'c'.toByte(), 'o'.toByte(), 'm'.toByte(),
            0x00
        )
        val dnsRequest = ByteArray(12 + qnameBytes.size + 4)
        
        // Transaction ID = 0x1234
        dnsRequest[0] = 0x12
        dnsRequest[1] = 0x34
        
        // Questions Count = 1
        dnsRequest[4] = 0x00
        dnsRequest[5] = 0x01
        
        System.arraycopy(qnameBytes, 0, dnsRequest, 12, qnameBytes.size)
        
        // QTYPE = A (1)
        val nextOffset = 12 + qnameBytes.size
        dnsRequest[nextOffset] = 0x00
        dnsRequest[nextOffset + 1] = 0x01
        
        // QCLASS = IN (1)
        dnsRequest[nextOffset + 2] = 0x00
        dnsRequest[nextOffset + 3] = 0x01

        val response = DnsPacketParser.createBlockResponse(dnsRequest)
        
        // Cek apakah response berukuran request + 16 byte (karena ada Answer Section tambahan)
        assertEquals(dnsRequest.size + 16, response.size)
        
        // Cek Transaction ID harus sama
        assertEquals(0x12.toByte(), response[0])
        assertEquals(0x34.toByte(), response[1])
        
        // Cek Flags harus 0x8180 (Standard Query Response, No Error)
        val flags = ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF)
        assertEquals(0x8180, flags)
        
        // Cek Answers Count harus 1
        val answers = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
        assertEquals(1, answers)

        // Cek IP Address tujuan (0.0.0.0) di 4 byte terakhir
        assertEquals(0.toByte(), response[response.size - 4])
        assertEquals(0.toByte(), response[response.size - 3])
        assertEquals(0.toByte(), response[response.size - 2])
        assertEquals(0.toByte(), response[response.size - 1])
    }
}
