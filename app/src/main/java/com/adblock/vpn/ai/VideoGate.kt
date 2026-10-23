package com.adblock.vpn.ai

/**
 * GERBANG VIDEO (allowlist / default-deny) khusus aplikasi Melolo.
 *
 * Kebalikan dari ad-blocker biasa: alih-alih mencari domain iklan untuk diblokir,
 * gerbang ini HANYA membuka domain yang dibutuhkan untuk menampilkan drama
 * (API, URL video, server video, gambar cover, login, pembayaran).
 * Semua domain lain yang dihubungi Melolo — termasuk jaringan iklan yang belum
 * pernah dikenal — otomatis tertutup.
 *
 * Hanya dipakai saat VPN dibatasi ke aplikasi Melolo, sehingga aplikasi lain
 * di HP tidak terpengaruh.
 */
object VideoGate {

    data class GateResult(val allowed: Boolean, val reason: String)

    /** Domain yang seluruh subdomainnya boleh lewat. */
    private val openSuffixes = listOf(
        "melolostatic.com",   // CDN video & aset Melolo
        "melolo.org",
        "fizzo.org",
        "fizzopic.org",
        "fictum.org",
        "byteintlapi.com",    // vod-urls-*: pemberi alamat video
        "pipopay.com"         // pembayaran koin / VIP
    )

    /** Domain ByteDance yang dipakai bersama oleh konten DAN iklan — hanya subdomain tertentu yang dibuka. */
    private val byteDanceSuffixes = listOf(
        "tmtreader.com", "ibyteimg.com", "ibytedtos.com", "byteintl.com", "byteintl.net",
        "byteoversea.com", "byteoversea.net", "isnssdk.com", "sgsnssdk.com", "snssdk.com",
        "tiktokv.com", "tiktokv-us.com", "tiktokcdn.com", "tiktokcdn-us.com", "bytegecko-i18n.com",
        "g-p-static.com", "pstap.com", "pstatp.com", "tiktok-row.org", "akamaized.net"
    )

    /** Token label yang menandakan fungsi penting (bukan iklan). */
    private val essentialTokens = setOf(
        // API & infrastruktur aplikasi
        "api", "passport", "tnc", "ttnet", "abtest", "gab", "frontier", "verify", "verification",
        "mssdk", "ichannel", "share", "link", "settings", "config",
        // Registrasi perangkat (tanpa ini video bisa gagal diputar)
        "log", "applog",
        // Paket UI aplikasi (Gecko)
        "gecko", "sourcecdn", "geckocdn",
        // Konten: gambar cover & sinopsis, aset statis, penyimpanan
        "novel", "drama", "static", "scmcdn", "tos", "tosv", "cdn", "img", "image", "cover",
        // Video
        "video", "vod", "play", "hls", "dash", "stream", "media", "voice", "audio"
    )

    /** Token yang SELALU ditutup walau berada di domain ByteDance. */
    private val deniedTokens = setOf(
        "ad", "ads", "adx", "pangle", "pgl", "mon", "rtlog", "toblog", "slardar", "apm",
        "dpprofile", "mcs", "analytics", "track", "tracking", "pagead", "mediation", "creative"
    )

    /** Pola server video ByteDance: v16-xxx, v19-xxx, v45-xxx, v16m-xxx, dll. */
    private val videoEdgeRegex = Regex("^v\\d+[a-z]?(-.*)?$")

    /** Layanan Google yang dibutuhkan untuk login, Play Store, notifikasi & billing. */
    private val googleEssentials = setOf(
        "accounts.google.com", "oauth2.googleapis.com", "www.googleapis.com", "play.googleapis.com",
        "android.googleapis.com", "android.clients.google.com", "play.google.com", "mtalk.google.com",
        "firebaseinstallations.googleapis.com", "fcmregistrations.googleapis.com", "fcm.googleapis.com",
        "connectivitycheck.gstatic.com", "www.gstatic.com", "ssl.gstatic.com", "fonts.gstatic.com",
        "fonts.googleapis.com", "clients3.google.com", "clients4.google.com", "time.android.com",
        "firebaseremoteconfig.googleapis.com", "firebase-settings.crashlytics.com"
    )

    private fun endsWithSuffix(domain: String, suffix: String) =
        domain == suffix || domain.endsWith(".$suffix")

    fun evaluate(rawDomain: String): GateResult {
        val domain = rawDomain.lowercase().trim().trimEnd('.')
        if (domain.isEmpty()) return GateResult(true, "-")

        if (domain in googleEssentials) return GateResult(true, "Layanan Google penting (login/notifikasi)")

        val tokens = DomainAiAnalyzer.tokenize(domain)
        val denied = tokens.firstOrNull { it in deniedTokens }
        if (denied != null) return GateResult(false, "Ditutup: label '$denied' adalah iklan/pelacak")

        openSuffixes.firstOrNull { endsWithSuffix(domain, it) }?.let {
            return GateResult(true, "Dibuka: domain inti Melolo ($it)")
        }

        val bdSuffix = byteDanceSuffixes.firstOrNull { endsWithSuffix(domain, it) }
        if (bdSuffix != null) {
            val firstLabel = domain.substringBefore('.')
            if (videoEdgeRegex.matches(firstLabel)) {
                return GateResult(true, "Dibuka: server video ByteDance ($firstLabel)")
            }
            val subTokens = DomainAiAnalyzer.tokenize(domain.removeSuffix(bdSuffix).trimEnd('.') + ".x")
            val essential = subTokens.firstOrNull { it in essentialTokens }
            if (essential != null) {
                return GateResult(true, "Dibuka: fungsi penting '$essential'")
            }
            // Domain utama tmtreader.com tanpa subdomain juga dibuka
            if (domain == bdSuffix && bdSuffix == "tmtreader.com") {
                return GateResult(true, "Dibuka: domain inti Melolo")
            }
            return GateResult(false, "Ditutup: subdomain ByteDance bukan video/konten")
        }

        return GateResult(false, "Ditutup: bukan domain video/konten Melolo")
    }
}
