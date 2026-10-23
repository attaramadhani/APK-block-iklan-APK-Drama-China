package com.adblock.vpn.dns

import android.util.Log
import com.adblock.vpn.ai.AiVerdictStore
import com.adblock.vpn.ai.DomainAiAnalyzer
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

object DnsPacketParser {
    private const val TAG = "DnsPacketParser"

    // Default TTL untuk respon blokir (300 detik / 5 menit)
    // Mencegah SDK iklan melakukan spamming retry loop tanpa memblokir permanen
    private const val BLOCK_TTL_SECONDS = 300

    /** AI otomatis: blokir domain tak dikenal yang dinilai iklan oleh AI (lokal & online). */
    @Volatile
    var aiAutoEnabled = true

    /** Mode agresif: blokir juga telemetri ByteDance & domain berstatus "perlu ditinjau". */
    @Volatile
    var aggressiveMode = false

    data class FilterDecision(
        val blocked: Boolean,
        val verdict: DomainAiAnalyzer.Verdict,
        val source: String
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

    private fun matchesAny(domain: String, set: HashSet<String>): Boolean {
        synchronized(set) {
            if (set.contains(domain)) return true
            for (entry in set) {
                if (domain.endsWith(".$entry")) return true
            }
        }
        return false
    }

    /**
     * Memutuskan apakah domain diblokir. Urutan prioritas:
     * 1. Blacklist kustom pengguna  -> BLOKIR
     * 2. Whitelist kustom pengguna  -> IZINKAN
     * 3. Domain inti Melolo / layanan sistem (AI lokal) -> IZINKAN (tidak bisa ditimpa AI online)
     * 4. Basis pengetahuan iklan/pelacak/HTTPDNS -> BLOKIR
     * 5. Keputusan AI Online (Gemini) yang sudah tersimpan -> sesuai keputusan
     * 6. Telemetri ByteDance -> BLOKIR hanya di Mode Agresif
     * 7. Domain tak dikenal -> skor AI lokal (>= 75% blokir, 55-74% blokir di Mode Agresif)
     *
     * Berbeda dengan versi lama, domain yang tidak dikenal TIDAK lagi diblokir otomatis,
     * sehingga video drama & aplikasi lain tidak rusak.
     */
    fun decide(domain: String): FilterDecision {
        val clean = domain.lowercase().trim().trimEnd('.')
        val verdict = DomainAiAnalyzer.analyze(clean)
        if (clean.isEmpty()) return FilterDecision(false, verdict, "-")

        if (matchesAny(clean, customBlacklist)) return FilterDecision(true, verdict, "Blacklist Anda")
        if (matchesAny(clean, customWhitelist)) return FilterDecision(false, verdict, "Whitelist Anda")

        when (verdict.category) {
            DomainAiAnalyzer.Category.CORE_APP,
            DomainAiAnalyzer.Category.SYSTEM -> return FilterDecision(false, verdict, "AI Lokal")
            DomainAiAnalyzer.Category.AD_NETWORK,
            DomainAiAnalyzer.Category.TRACKER,
            DomainAiAnalyzer.Category.HTTPDNS -> return FilterDecision(true, verdict, "AI Lokal")
            else -> {}
        }

        if (aiAutoEnabled) {
            AiVerdictStore.get(clean)?.let { cloud ->
                return FilterDecision(cloud.block, verdict, "AI Online")
            }
        }

        if (verdict.category == DomainAiAnalyzer.Category.TELEMETRY) {
            return FilterDecision(aggressiveMode, verdict, if (aggressiveMode) "Mode Agresif" else "AI Lokal")
        }

        if (!aiAutoEnabled) return FilterDecision(false, verdict, "AI nonaktif")
        val blocked = when (verdict.action) {
            DomainAiAnalyzer.Action.BLOCK -> true
            DomainAiAnalyzer.Action.REVIEW -> aggressiveMode
            DomainAiAnalyzer.Action.ALLOW -> false
        }
        return FilterDecision(blocked, verdict, if (blocked && verdict.action == DomainAiAnalyzer.Action.REVIEW) "Mode Agresif" else "AI Lokal")
    }

    fun isAdDomain(domain: String): Boolean = decide(domain).blocked

    /**
     * Mem-parse nama domain dari DNS query payload (QNAME).
     * Menggunakan Length-Value parsing dengan proteksi terhadap buffer overflow dan loop tak terbatas.
     */
    fun parseDomainName(dnsPayload: ByteArray, offset: Int, depth: Int = 0): Pair<String, Int> {
        if (depth > 10 || offset < 0 || offset >= dnsPayload.size) return Pair("", offset)
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
                val (subDomain, _) = parseDomainName(dnsPayload, pointerOffset, depth + 1)

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
