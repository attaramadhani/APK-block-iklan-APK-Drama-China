package com.adblock.vpn.dns

import android.util.Log
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

object DnsPacketParser {
    private const val TAG = "DnsPacketParser"

    // Default TTL untuk respon blokir (300 detik / 5 menit)
    // Mencegah SDK iklan melakukan spamming retry loop tanpa memblokir permanen
    private const val BLOCK_TTL_SECONDS = 300

    // Whitelist domain inti resmi untuk aplikasi pembaca novel / drama (Melolo, Fizzo, dsb.)
    // CATATAN: Domain di list ini HANYA diizinkan jika TIDAK cocok dengan pola iklan/pelacak!
    private val contentWhitelist = hashSetOf(
        "tmtreader.com",
        "melolo.org",
        "fizzo.org",
        "melolostatic.com",
        "ibyteimg.com",
        "fizzopic.org",
        "pipopay.com",
        "akamai.net",
        "miui.com",
        "xiaomi.com",
        "gstatic.com"
    )

    // Domain umum yang memiliki layanan inti dan layanan iklan terpisah
    // Misalnya google.com (pencarian/login diizinkan, tetapi pagead2.google.com diblokir)
    private val allowedBaseDomains = hashSetOf(
        "google.com",
        "googleapis.com",
        "byteintl.com",
        "byteintlapi.com"
    )

    // Suffix domain iklan dan pelacak populer yang PASTI diblokir
    private val knownAdSuffixes = hashSetOf(
        // Google & DoubleClick Ads
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "admob.com",
        "pagead2.google.com",
        "adservice.google.com",

        // ByteDance / Pangle / Pangolin Ads (Melolo, TikTok, Fizzo)
        "pangle.io",
        "pangolin-sdk.com",
        "pangle-ads.com",
        "pangolin.snssdk.com",
        "toblog.byteintl.com",
        "rtlog.byteintl.com",
        "mon.byteintl.com",
        "mcs.byteintl.com",
        "ads-api.byteintl.com",
        "api-ad-sg.byteintl.com",
        "ad.byteintl.com",
        "ads.byteintl.com",
        "applog.byteintl.com",
        "log.byteoversea.com",
        "sf16-fe-tos-sg.byteintl.com",
        "sf-tb-sg.ibytedtos.com",

        // AppLovin
        "applovin.com",
        "applvn.com",

        // Unity Ads
        "unityads.unity3d.com",
        "auction.unityads.unity3d.com",

        // Mintegral & Rayjump
        "mintegral.com",
        "rayjump.com",
        "mtgglobals.com",

        // Vungle & Liftoff
        "vungle.com",
        "liftoff.io",

        // InMobi
        "inmobi.com",
        "inmobicdn.net",

        // Moloco
        "moloco.com",
        "adsmoloco.com",

        // IronSource & Supersonic
        "ironsrc.com",
        "supersonicads.com",

        // AdColony, Chartboost, Tapjoy
        "adcolony.com",
        "chartboost.com",
        "tapjoy.com",

        // Trackers & Attribution
        "adjust.com",
        "appsflyer.com",
        "branch.io",
        "kochava.com",
        "singular.net"
    )

    // Pola kata kunci (substring) spesifik domain iklan yang langsung di-block
    private val knownAdKeywords = listOf(
        "pagead",
        "adservice",
        "googleads",
        "googleadservices",
        "pangle",
        "pangolin",
        "toblog",
        "rtlog",
        "applog",
        "api-ad",
        "ads-api",
        "-ad-",
        ".ad.",
        ".ads.",
        "ads-",
        "ad-sg",
        "unityads",
        "applovin",
        "applvn",
        "mintegral",
        "rayjump",
        "mtgglobals",
        "vungle",
        "liftoff",
        "inmobi",
        "inmobicdn",
        "adsmoloco",
        "ironsrc",
        "supersonicads",
        "adcolony"
    )

    private val customWhitelist = hashSetOf<String>()
    private val customBlacklist = hashSetOf<String>()

    fun setCustomWhitelist(domains: Set<String>) {
        synchronized(customWhitelist) {
            customWhitelist.clear()
            customWhitelist.addAll(domains.map { it.lowercase().trim() })
        }
    }

    fun setCustomBlacklist(domains: Set<String>) {
        synchronized(customBlacklist) {
            customBlacklist.clear()
            customBlacklist.addAll(domains.map { it.lowercase().trim() })
        }
    }

    /**
     * Memeriksa apakah domain teridentifikasi sebagai domain iklan atau pelacak.
     * Menggunakan pendekatan berlapis (Multi-tier Filtering):
     * 1. Prioritas 1: Custom Blacklist dari pengguna.
     * 2. Prioritas 2: Custom Whitelist dari pengguna.
     * 3. Prioritas 3: Deteksi Pasti Iklan (High-Confidence Ad Patterns & Suffixes).
     * 4. Prioritas 4: Whitelist Konten Resmi Novel/Drama (Melolo, Fizzo, dsb.).
     * 5. Prioritas 5: Default Whitelist Policy (Unknown third-party domains diblokir).
     */
    fun isAdDomain(domain: String): Boolean {
        if (domain.isEmpty()) return false
        val cleanDomain = domain.lowercase().trim()

        // 1. Cek custom blacklist terlebih dahulu (pengguna secara spesifik ingin memblokir)
        synchronized(customBlacklist) {
            if (customBlacklist.contains(cleanDomain)) return true
            for (blocked in customBlacklist) {
                if (cleanDomain.endsWith(".$blocked")) {
                    return true
                }
            }
        }

        // 2. Cek custom whitelist terlebih dahulu (pengguna secara spesifik ingin mengizinkan)
        synchronized(customWhitelist) {
            if (customWhitelist.contains(cleanDomain)) return false
            for (allowed in customWhitelist) {
                if (cleanDomain.endsWith(".$allowed")) {
                    return false
                }
            }
        }

        // 3. DETEKSI TEGAS IKLAN & PELACAK (Mencegah kebocoran iklan dari subdomain google / bytedance)
        if (isKnownAdDomain(cleanDomain)) {
            return true
        }

        // 4. Izinkan lalu lintas dispatch routing & A/B testing resmi jika BUKAN iklan
        if ((cleanDomain.contains("tnc") || cleanDomain.contains("abtest")) &&
            !cleanDomain.contains("ad") && !cleanDomain.contains("log")
        ) {
            return false
        }

        // 5. Cek Content Whitelist (Melolo, Fizzo, mesin novel, gambar buku)
        if (contentWhitelist.contains(cleanDomain)) return false
        for (allowed in contentWhitelist) {
            if (cleanDomain.endsWith(".$allowed")) {
                return false
            }
        }

        // 6. Cek Base Domain Umum (Google & Bytedance untuk API legal yang bukan iklan)
        for (base in allowedBaseDomains) {
            if (cleanDomain == base || cleanDomain.endsWith(".$base")) {
                return false
            }
        }

        // 7. Jika tidak terdaftar di whitelist konten maupun base domain, blokir untuk keamanan
        return true
    }

    /**
     * Memeriksa apakah nama domain cocok dengan pola atau suffix domain iklan/pelacak yang diketahui.
     */
    fun isKnownAdDomain(domain: String): Boolean {
        // Cek suffix
        for (suffix in knownAdSuffixes) {
            if (domain == suffix || domain.endsWith(".$suffix")) {
                return true
            }
        }

        // Cek pola kata kunci
        for (keyword in knownAdKeywords) {
            if (domain.contains(keyword)) {
                return true
            }
        }

        return false
    }

    /**
     * Mem-parse nama domain dari DNS query payload (QNAME).
     * Menggunakan Length-Value parsing dengan proteksi terhadap buffer overflow dan loop tak terbatas.
     */
    fun parseDomainName(dnsPayload: ByteArray, offset: Int): Pair<String, Int> {
        val domain = StringBuilder()
        var ptr = offset
        var loopCount = 0
        val maxLoops = 128

        while (ptr < dnsPayload.size && loopCount < maxLoops) {
            val len = dnsPayload[ptr].toInt() and 0xFF

            if (len == 0) {
                ptr++
                break
            }

            // Kompresi DNS pointer (0xC0)
            if ((len and 0xC0) == 0xC0) {
                if (ptr + 1 >= dnsPayload.size) {
                    Log.w(TAG, "Parsing error: Pointer kompresi terpotong.")
                    break
                }
                val pointerOffset = ((len and 0x3F) shl 8) or (dnsPayload[ptr + 1].toInt() and 0xFF)
                val (subDomain, _) = parseDomainName(dnsPayload, pointerOffset)

                if (domain.isNotEmpty()) {
                    domain.append(".")
                }
                domain.append(subDomain)
                ptr += 2
                return Pair(domain.toString(), ptr)
            } else {
                if (ptr + 1 + len > dnsPayload.size) {
                    Log.w(TAG, "Parsing error: Ukuran label melebihi batas buffer.")
                    break
                }

                if (domain.isNotEmpty()) {
                    domain.append(".")
                }

                val label = String(dnsPayload, ptr + 1, len, StandardCharsets.US_ASCII)
                domain.append(label)
                ptr += 1 + len
            }
            loopCount++
        }

        return Pair(domain.toString(), ptr)
    }

    /**
     * Membuat paket DNS Response yang valid sesuai RFC untuk domain iklan yang diblokir.
     * - Query Tipe A (IPv4): Kembalikan IP 0.0.0.0 (TTL 300 detik)
     * - Query Tipe AAAA (IPv6): Kembalikan IP :: (TTL 300 detik)
     * - Query Tipe Lain (HTTPS, SVCB, dll): Kembalikan respon NODATA (ANCOUNT=0, NOERROR)
     */
    fun createBlockResponse(requestPayload: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(requestPayload)
        val transactionId = buffer.short

        // Parse QNAME
        val (_, nextOffset) = parseDomainName(requestPayload, 12)

        if (nextOffset + 4 > requestPayload.size) {
            return createSimpleNxDomainResponse(transactionId)
        }

        val questionLength = nextOffset + 4
        val qType = ((requestPayload[nextOffset].toInt() and 0xFF) shl 8) or
                (requestPayload[nextOffset + 1].toInt() and 0xFF)

        return when (qType) {
            1 -> buildAStubResponse(requestPayload, questionLength)       // Type A (IPv4)
            28 -> buildAaaaStubResponse(requestPayload, questionLength)   // Type AAAA (IPv6)
            else -> buildNoDataResponse(requestPayload, questionLength)   // Type Lainnya (HTTPS/SVCB/etc)
        }
    }

    /**
     * Membuat DNS Response A-Record yang memetakan domain ke IP 0.0.0.0 (IPv4).
     */
    private fun buildAStubResponse(requestPayload: ByteArray, questionLength: Int): ByteArray {
        val answerLength = 16 // 2 (pointer) + 2 (type) + 2 (class) + 4 (ttl) + 2 (rdlen) + 4 (ip)
        val responseBytes = ByteArray(questionLength + answerLength)

        System.arraycopy(requestPayload, 0, responseBytes, 0, questionLength)

        val outBuffer = ByteBuffer.wrap(responseBytes)

        // Offset 2: Flags (0x8180 -> QR=1, Opcode=0, AA=0, TC=0, RD=1, RA=1, RCODE=0 NOERROR)
        outBuffer.putShort(2, 0x8180.toShort())

        // Offset 6: Answer Count = 1
        outBuffer.putShort(6, 1.toShort())
        // Offset 8: Authority Count = 0
        outBuffer.putShort(8, 0.toShort())
        // Offset 10: Additional Count = 0
        outBuffer.putShort(10, 0.toShort())

        // Answer Section:
        outBuffer.position(questionLength)
        outBuffer.putShort(0xC00C.toShort()) // Pointer ke QNAME offset 12
        outBuffer.putShort(1.toShort())      // Type A
        outBuffer.putShort(1.toShort())      // Class IN
        outBuffer.putInt(BLOCK_TTL_SECONDS)  // TTL 300 detik agar dicache
        outBuffer.putShort(4.toShort())      // RDLENGTH = 4 byte
        outBuffer.put(byteArrayOf(0, 0, 0, 0)) // 0.0.0.0

        return responseBytes
    }

    /**
     * Membuat DNS Response AAAA-Record yang memetakan domain ke IP :: (IPv6).
     */
    private fun buildAaaaStubResponse(requestPayload: ByteArray, questionLength: Int): ByteArray {
        val answerLength = 28 // 2 (pointer) + 2 (type) + 2 (class) + 4 (ttl) + 2 (rdlen) + 16 (ipv6)
        val responseBytes = ByteArray(questionLength + answerLength)

        System.arraycopy(requestPayload, 0, responseBytes, 0, questionLength)

        val outBuffer = ByteBuffer.wrap(responseBytes)

        // Flags: 0x8180 (NOERROR)
        outBuffer.putShort(2, 0x8180.toShort())
        // Answer Count = 1
        outBuffer.putShort(6, 1.toShort())
        outBuffer.putShort(8, 0.toShort())
        outBuffer.putShort(10, 0.toShort())

        // Answer Section:
        outBuffer.position(questionLength)
        outBuffer.putShort(0xC00C.toShort()) // Pointer ke QNAME offset 12
        outBuffer.putShort(28.toShort())     // Type AAAA (28)
        outBuffer.putShort(1.toShort())      // Class IN
        outBuffer.putInt(BLOCK_TTL_SECONDS)  // TTL 300 detik
        outBuffer.putShort(16.toShort())     // RDLENGTH = 16 byte
        outBuffer.put(ByteArray(16))         // :: (16 zero bytes)

        return responseBytes
    }

    /**
     * Membuat DNS Response NODATA (NOERROR dengan Answer Count = 0).
     * Digunakan untuk query HTTPS (Tipe 65), SVCB, dsb. agar klien tidak mencoba fallback.
     */
    private fun buildNoDataResponse(requestPayload: ByteArray, questionLength: Int): ByteArray {
        val responseBytes = ByteArray(questionLength)
        System.arraycopy(requestPayload, 0, responseBytes, 0, questionLength)

        val outBuffer = ByteBuffer.wrap(responseBytes)

        // Flags: 0x8180 (NOERROR)
        outBuffer.putShort(2, 0x8180.toShort())
        // Answer Count = 0
        outBuffer.putShort(6, 0.toShort())
        outBuffer.putShort(8, 0.toShort())
        outBuffer.putShort(10, 0.toShort())

        return responseBytes
    }

    /**
     * Fallback NXDOMAIN respon jika parser gagal membaca QNAME secara penuh.
     */
    private fun createSimpleNxDomainResponse(transactionId: Short): ByteArray {
        val header = ByteArray(12)
        val buffer = ByteBuffer.wrap(header)
        buffer.putShort(transactionId)
        buffer.putShort(0x8183.toShort()) // NXDOMAIN Response
        buffer.putShort(0.toShort())      // Questions = 0
        buffer.putShort(0.toShort())      // Answers = 0
        buffer.putShort(0.toShort())      // Authority = 0
        buffer.putShort(0.toShort())      // Additional = 0
        return header
    }
}
