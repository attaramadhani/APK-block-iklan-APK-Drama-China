package com.adblock.vpn.ai

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Mesin AI on-device (offline) untuk menilai apakah sebuah domain aman diblokir.
 *
 * Cara kerja (berlapis):
 *  1. Basis pengetahuan: daftar jaringan iklan, pelacak, dan domain inti Melolo
 *     yang diekstrak langsung dari APK Melolo (com.worldance.drama).
 *  2. Analisis struktur domain: ByteDance memakai pola subdomain yang konsisten
 *     (mon-*, rtlog-*, log-*, tnc-*, api-*), sehingga fungsi domain bisa ditebak dari label pertamanya.
 *  3. Model skor (logistic scoring): domain dipecah menjadi token, tiap token punya bobot
 *     (token iklan/pelacak menaikkan skor, token konten/video/API menurunkannya).
 *     Hasilnya probabilitas 0–100% bahwa domain tersebut adalah iklan/pelacak.
 *
 * Kelas ini murni Kotlin (tanpa dependensi Android) agar mudah diuji dengan unit test.
 */
object DomainAiAnalyzer {

    enum class Category(val label: String) {
        AD_NETWORK("Jaringan Iklan"),
        TRACKER("Pelacak / Analitik"),
        TELEMETRY("Telemetri Aplikasi"),
        HTTPDNS("DNS Bypass (HTTPDNS)"),
        CORE_APP("Inti Aplikasi Drama"),
        SYSTEM("Layanan Sistem / Umum"),
        UNKNOWN("Belum Dikenal")
    }

    enum class Action(val label: String) {
        BLOCK("AMAN DIBLOKIR"),
        REVIEW("PERLU DITINJAU"),
        ALLOW("JANGAN DIBLOKIR")
    }

    data class Verdict(
        val domain: String,
        val category: Category,
        /** Probabilitas (0–100) domain ini adalah iklan / pelacak. */
        val adScore: Int,
        /** Tingkat keyakinan AI terhadap keputusannya (0–100). */
        val confidence: Int,
        val action: Action,
        val purpose: String,
        val impact: String,
        val reasons: List<String>,
        val source: String = "AI Lokal"
    ) {
        val shortLabel: String get() = "${category.label} · ${adScore}%"
    }

    /** Ambang batas skor untuk domain yang tidak dikenal. */
    const val BLOCK_THRESHOLD = 75
    const val REVIEW_THRESHOLD = 55

    private data class Rule(val suffix: String, val category: Category, val name: String)

    // --- 1. BASIS PENGETAHUAN: JARINGAN IKLAN (ditemukan di dalam APK Melolo) ---
    private val adRules = listOf(
        // Google Ads / AdMob / IMA
        Rule("doubleclick.net", Category.AD_NETWORK, "Google DoubleClick"),
        Rule("googlesyndication.com", Category.AD_NETWORK, "Google AdSense/AdMob"),
        Rule("googleadservices.com", Category.AD_NETWORK, "Google Ads"),
        Rule("admob.com", Category.AD_NETWORK, "Google AdMob"),
        Rule("pagead2.google.com", Category.AD_NETWORK, "Google Ads"),
        Rule("adservice.google.com", Category.AD_NETWORK, "Google Ads"),
        Rule("mobileads.google.com", Category.AD_NETWORK, "Google Mobile Ads"),
        Rule("imasdk.googleapis.com", Category.AD_NETWORK, "Google IMA (iklan video)"),
        Rule("fundingchoicesmessages.google.com", Category.AD_NETWORK, "Google Funding Choices"),
        Rule("admob-gmats.uc.r.appspot.com", Category.AD_NETWORK, "Google AdMob"),
        // ByteDance Pangle (jaringan iklan utama Melolo)
        Rule("pangle.io", Category.AD_NETWORK, "Pangle (ByteDance)"),
        Rule("pangleglobal.com", Category.AD_NETWORK, "Pangle (ByteDance)"),
        Rule("pangle-ads.com", Category.AD_NETWORK, "Pangle (ByteDance)"),
        Rule("pangolin-sdk.com", Category.AD_NETWORK, "Pangle (ByteDance)"),
        Rule("pangolin.snssdk.com", Category.AD_NETWORK, "Pangle (ByteDance)"),
        Rule("i18n-pglstatp.com", Category.AD_NETWORK, "Pangle (materi iklan)"),
        Rule("pglstatp.com", Category.AD_NETWORK, "Pangle (materi iklan)"),
        Rule("pglstatp-toutiao.com", Category.AD_NETWORK, "Pangle (materi iklan)"),
        Rule("tiktokpangle-cdn-us.com", Category.AD_NETWORK, "Pangle (materi iklan)"),
        Rule("tiktokpangle.us", Category.AD_NETWORK, "Pangle"),
        Rule("tiktokpangle-b.us", Category.AD_NETWORK, "Pangle"),
        // AppLovin
        Rule("applovin.com", Category.AD_NETWORK, "AppLovin"),
        Rule("applvn.com", Category.AD_NETWORK, "AppLovin"),
        Rule("applovin.net", Category.AD_NETWORK, "AppLovin"),
        // Moloco
        Rule("moloco.com", Category.AD_NETWORK, "Moloco"),
        Rule("adsmoloco.com", Category.AD_NETWORK, "Moloco"),
        // InMobi
        Rule("inmobi.com", Category.AD_NETWORK, "InMobi"),
        Rule("inmobicdn.net", Category.AD_NETWORK, "InMobi"),
        // Vungle / Liftoff
        Rule("vungle.com", Category.AD_NETWORK, "Vungle/Liftoff"),
        Rule("liftoff.io", Category.AD_NETWORK, "Liftoff"),
        Rule("liftoff-creatives.io", Category.AD_NETWORK, "Liftoff"),
        // Unity Ads
        Rule("unityads.unity3d.com", Category.AD_NETWORK, "Unity Ads"),
        Rule("offerwall.unity3d.com", Category.AD_NETWORK, "Unity Offerwall"),
        Rule("unityads.unity.com", Category.AD_NETWORK, "Unity Ads"),
        // Mintegral / Mobvista
        Rule("mintegral.com", Category.AD_NETWORK, "Mintegral"),
        Rule("mintegral.net", Category.AD_NETWORK, "Mintegral"),
        Rule("rayjump.com", Category.AD_NETWORK, "Mintegral"),
        Rule("mtgglobals.com", Category.AD_NETWORK, "Mintegral"),
        Rule("mobvista.com", Category.AD_NETWORK, "Mobvista"),
        Rule("spadsync.com", Category.AD_NETWORK, "Mintegral"),
        // IronSource
        Rule("ironsrc.com", Category.AD_NETWORK, "IronSource"),
        Rule("ironsrc.net", Category.AD_NETWORK, "IronSource"),
        Rule("supersonicads.com", Category.AD_NETWORK, "IronSource"),
        // Lainnya
        Rule("an.facebook.com", Category.AD_NETWORK, "Facebook Audience Network"),
        Rule("adcolony.com", Category.AD_NETWORK, "AdColony"),
        Rule("chartboost.com", Category.AD_NETWORK, "Chartboost"),
        Rule("tapjoy.com", Category.AD_NETWORK, "Tapjoy"),
        Rule("tapjoyads.com", Category.AD_NETWORK, "Tapjoy"),
        Rule("bidmachine.io", Category.AD_NETWORK, "BidMachine"),
        Rule("fyber.com", Category.AD_NETWORK, "Fyber"),
        Rule("smaato.net", Category.AD_NETWORK, "Smaato"),
        Rule("yandexadexchange.net", Category.AD_NETWORK, "Yandex Ads")
    )

    // --- PELACAK / ATRIBUSI ---
    private val trackerRules = listOf(
        Rule("app-measurement.com", Category.TRACKER, "Firebase Analytics"),
        Rule("google-analytics.com", Category.TRACKER, "Google Analytics"),
        Rule("googletagmanager.com", Category.TRACKER, "Google Tag Manager"),
        Rule("appsflyer.com", Category.TRACKER, "AppsFlyer"),
        Rule("appsflyersdk.com", Category.TRACKER, "AppsFlyer"),
        Rule("adjust.com", Category.TRACKER, "Adjust"),
        Rule("branch.io", Category.TRACKER, "Branch"),
        Rule("kochava.com", Category.TRACKER, "Kochava"),
        Rule("singular.net", Category.TRACKER, "Singular"),
        Rule("adobedtm.com", Category.TRACKER, "Adobe Analytics"),
        Rule("omtrdc.net", Category.TRACKER, "Adobe Analytics"),
        Rule("analytics.tiktok.com", Category.TRACKER, "TikTok Analytics"),
        Rule("analytics-sg.tiktok.com", Category.TRACKER, "TikTok Analytics")
    )

    // --- HTTPDNS: dipakai SDK ByteDance untuk melewati DNS sistem (iklan jadi bocor) ---
    private val httpDnsRules = listOf(
        Rule("dig.bdurl.net", Category.HTTPDNS, "ByteDance HTTPDNS"),
        Rule("httpdns.bytedance.com", Category.HTTPDNS, "ByteDance HTTPDNS"),
        Rule("httpdns-sg.byteoversea.com", Category.HTTPDNS, "ByteDance HTTPDNS"),
        Rule("dns.bytedance.net", Category.HTTPDNS, "ByteDance HTTPDNS")
    )

    // --- 2. DOMAIN INTI MELOLO / FIZZO (ditemukan di APK) ---
    private val coreRules = listOf(
        Rule("tmtreader.com", Category.CORE_APP, "API Melolo (tmtreader)"),
        Rule("melolo.org", Category.CORE_APP, "Server Melolo"),
        Rule("melolostatic.com", Category.CORE_APP, "CDN video & aset Melolo"),
        Rule("fizzo.org", Category.CORE_APP, "Server Fizzo/Melolo Drama"),
        Rule("fizzopic.org", Category.CORE_APP, "Gambar Fizzo"),
        Rule("fictum.org", Category.CORE_APP, "Server konten Fictum"),
        Rule("ibyteimg.com", Category.CORE_APP, "CDN gambar/cover ByteDance"),
        Rule("ibytedtos.com", Category.CORE_APP, "CDN statis & login ByteDance"),
        Rule("byteintlapi.com", Category.CORE_APP, "API URL video (VOD) ByteDance"),
        Rule("byteintl.net", Category.CORE_APP, "CDN ByteDance"),
        Rule("byteintl.com", Category.CORE_APP, "API ByteDance"),
        Rule("byteoversea.com", Category.CORE_APP, "Infrastruktur ByteDance"),
        Rule("byteoversea.net", Category.CORE_APP, "CDN ByteDance"),
        Rule("bytegecko-i18n.com", Category.CORE_APP, "Paket UI (Gecko) ByteDance"),
        Rule("g-p-static.com", Category.CORE_APP, "Paket UI (Gecko) ByteDance"),
        Rule("tiktokcdn.com", Category.CORE_APP, "CDN ByteDance"),
        Rule("tiktokcdn-us.com", Category.CORE_APP, "CDN ByteDance"),
        Rule("tiktokv.com", Category.CORE_APP, "Infrastruktur ByteDance"),
        Rule("tiktokv-us.com", Category.CORE_APP, "Infrastruktur ByteDance"),
        Rule("tiktok-row.org", Category.CORE_APP, "Penyimpanan ByteDance"),
        Rule("isnssdk.com", Category.CORE_APP, "Infrastruktur ByteDance"),
        Rule("sgsnssdk.com", Category.CORE_APP, "Infrastruktur ByteDance"),
        Rule("snssdk.com", Category.CORE_APP, "Infrastruktur ByteDance"),
        Rule("pstap.com", Category.CORE_APP, "CDN gambar ByteDance"),
        Rule("pstatp.com", Category.CORE_APP, "CDN gambar ByteDance"),
        Rule("pipopay.com", Category.CORE_APP, "Pembayaran koin/VIP")
    )

    // Domain keluarga ByteDance — dipakai untuk menganalisis pola subdomain telemetri.
    private val byteDanceFamily = listOf(
        "tmtreader.com", "byteintl.com", "byteintl.net", "byteoversea.com", "byteoversea.net",
        "tiktokv.com", "tiktokv-us.com", "isnssdk.com", "sgsnssdk.com", "snssdk.com",
        "ibytedtos.com", "bytedance.com", "bytedance.net", "melolo.org", "fizzo.org"
    )

    /** Label subdomain ByteDance yang khusus untuk monitoring / profiling (aman diblokir). */
    private val byteDanceMonitorTokens = setOf(
        "mon", "rtlog", "toblog", "slardar", "apm", "apmplus", "xlog", "crash", "dpprofile", "mcs"
    )

    // --- LAYANAN SISTEM UMUM (agar mode Global tidak merusak aplikasi lain) ---
    private val systemRules = listOf(
        "google.com", "googleapis.com", "gstatic.com", "googleusercontent.com", "gvt1.com", "gvt2.com",
        "googlevideo.com", "youtube.com", "ytimg.com", "android.com", "firebaseio.com", "app.goo.gl",
        "facebook.com", "fbcdn.net", "facebook.net", "whatsapp.com", "whatsapp.net", "instagram.com",
        "akamai.net", "akamaized.net", "akamaihd.net", "cloudflare.com", "cloudfront.net", "fastly.net",
        "apple.com", "icloud.com", "microsoft.com", "live.com", "github.com", "githubusercontent.com",
        "xiaomi.com", "miui.com", "xiaomi.net", "samsung.com", "samsungcloud.com", "oppo.com", "heytap.com",
        "vivo.com", "vivo.com.cn", "hihonor.com", "huawei.com", "dbankcloud.com"
    ).map { Rule(it, Category.SYSTEM, "Layanan sistem/umum") }

    // --- 3. BOBOT TOKEN UNTUK MODEL SKOR ---
    /** Token yang hampir pasti menandakan iklan (diblokir walau berada di domain inti). */
    private val hardAdTokens = setOf(
        "ad", "ads", "adx", "adserver", "adservice", "adservices", "adsdk", "adsapi", "pagead", "pagead2",
        "googleads", "advert", "adverts", "advertising", "adtrack", "adlog", "adlogs", "openrtb",
        "rtb", "interstitial", "rewardedvideo", "adnet", "adnetwork", "admanager", "adsrv", "adtech"
    )

    private val tokenWeights: Map<String, Double> = buildMap {
        // Indikasi iklan
        listOf("mediation", "bidder", "bid", "bidding", "ssp", "dsp", "banner", "banners", "creative",
            "creatives", "playable", "rewarded", "reward", "offerwall", "monetization", "promo", "sponsor",
            "affiliate", "click", "clicks", "impression", "imp", "vast", "vpaid", "mraid", "pgl", "pangle",
            "adcdn", "adimg", "adstatic", "admaster", "adsense", "adform"
        ).forEach { put(it, 2.6) }
        // Indikasi pelacak / telemetri
        listOf("track", "tracking", "tracker", "analytics", "telemetry", "metrics", "metric", "beacon",
            "pixel", "collect", "collector", "stat", "stats", "statistics", "log", "logs", "logging",
            "logger", "event", "events", "report", "reporting", "crash", "crashlytics", "apm", "monitor",
            "mon", "rtlog", "applog", "toblog", "slardar", "sdkopmetrics", "measurement", "attribution",
            "fingerprint", "dpprofile", "insights"
        ).forEach { put(it, 1.9) }
        listOf("sdk", "sdkapi", "dsp-api").forEach { put(it, 0.5) }
        // Indikasi konten penting (menurunkan skor)
        listOf("api", "cdn", "img", "image", "images", "pic", "pics", "photo", "static", "video", "videos",
            "vod", "media", "stream", "streaming", "hls", "dash", "play", "player", "cover", "book", "books",
            "novel", "drama", "chapter", "episode", "upload", "download", "file", "files", "res", "resource",
            "assets", "passport", "account", "accounts", "login", "auth", "oauth", "verify", "verification",
            "pay", "payment", "billing", "checkout", "storage", "tos", "content", "www", "web", "m"
        ).forEach { put(it, -2.2) }
        listOf("tnc", "abtest", "config", "settings", "gateway", "frontier", "ws", "push", "im",
            "gecko", "update", "dns", "time", "ntp", "connectivitycheck"
        ).forEach { put(it, -1.2) }
    }

    private const val BASE_LOG_ODDS = -1.0

    private val cache = ConcurrentHashMap<String, Verdict>()
    private const val MAX_CACHE = 2000

    fun analyze(rawDomain: String): Verdict {
        val domain = rawDomain.lowercase().trim().trimEnd('.')
        cache[domain]?.let { return it }
        val verdict = computeVerdict(domain)
        if (cache.size > MAX_CACHE) cache.clear()
        cache[domain] = verdict
        return verdict
    }

    fun clearCache() = cache.clear()

    private fun matchRule(domain: String, rules: List<Rule>): Rule? =
        rules.firstOrNull { domain == it.suffix || domain.endsWith("." + it.suffix) }

    private fun isByteDanceFamily(domain: String): Boolean =
        byteDanceFamily.any { domain == it || domain.endsWith(".$it") }

    /** Memecah domain menjadi token: "sg-gcp-ad-track16.mtg.com" -> [sg, gcp, ad, track, mtg]. */
    fun tokenize(domain: String): List<String> {
        val labels = domain.split('.')
        val withoutTld = if (labels.size > 1) labels.dropLast(1) else labels
        return withoutTld.flatMap { label ->
            label.split('-', '_')
                .map { it.trimEnd { c -> c.isDigit() }.trimStart { c -> c.isDigit() } }
                .filter { it.isNotEmpty() }
        }
    }

    private fun computeVerdict(domain: String): Verdict {
        if (domain.isEmpty()) {
            return Verdict(domain, Category.UNKNOWN, 0, 0, Action.ALLOW, "-", "-", listOf("Domain kosong"))
        }

        val tokens = tokenize(domain)
        val firstLabel = domain.substringBefore('.')
        val firstTokens = firstLabel.split('-').map { it.trimEnd { c -> c.isDigit() } }

        // Lapis 1a: jaringan iklan yang dikenal
        matchRule(domain, adRules)?.let {
            return Verdict(
                domain, Category.AD_NETWORK, 99, 98, Action.BLOCK,
                "Server iklan ${it.name}",
                "Iklan video, banner, dan pop-up dari ${it.name} hilang. Aplikasi tetap berjalan normal.",
                listOf("Terdaftar sebagai jaringan iklan ${it.name}", "SDK ${it.name} ditemukan di dalam APK Melolo")
            )
        }
        // Lapis 1b: pelacak
        matchRule(domain, trackerRules)?.let {
            return Verdict(
                domain, Category.TRACKER, 95, 95, Action.BLOCK,
                "Pelacak & analitik ${it.name}",
                "Data aktivitas Anda tidak lagi dikirim ke ${it.name}. Tidak ada fitur yang rusak.",
                listOf("Terdaftar sebagai layanan pelacak ${it.name}")
            )
        }
        // Lapis 1c: HTTPDNS (bypass filter)
        matchRule(domain, httpDnsRules)?.let {
            return Verdict(
                domain, Category.HTTPDNS, 90, 90, Action.BLOCK,
                "${it.name} — resolver DNS internal aplikasi",
                "Aplikasi dipaksa memakai DNS sistem sehingga filter iklan tidak bisa dilewati. Koneksi tetap normal.",
                listOf("HTTPDNS dipakai SDK ByteDance untuk melewati DNS VPN", "Memblokirnya mencegah iklan bocor")
            )
        }

        // Lapis 1d: token iklan tegas (contoh: ads.tmtreader.com, lf16-ad-sg.ibyteimg.com)
        val hardHit = tokens.firstOrNull { it in hardAdTokens }
        if (hardHit != null) {
            return Verdict(
                domain, Category.AD_NETWORK, 93, 88, Action.BLOCK,
                "Subdomain penyaji iklan",
                "Materi iklan dari subdomain ini tidak dimuat. Konten utama tetap berjalan.",
                listOf("Mengandung label iklan '$hardHit'")
            )
        }

        // Lapis 2: analisis pola subdomain keluarga ByteDance
        if (isByteDanceFamily(domain)) {
            val head = firstTokens.firstOrNull() ?: ""
            val monitorHit = firstTokens.firstOrNull { it in byteDanceMonitorTokens }
            when {
                monitorHit != null -> {
                    return Verdict(
                        domain, Category.TRACKER, 92, 90, Action.BLOCK,
                        "Monitoring & log real-time ByteDance",
                        "Data performa & perilaku tidak dikirim. Aplikasi tetap berjalan normal.",
                        listOf("Pola subdomain '$monitorHit' = layanan monitoring/profil ByteDance")
                    )
                }
                head == "log" || head == "applog" || head == "logs" || firstLabel.matches(Regex("^log\\d+.*")) -> {
                    return Verdict(
                        domain, Category.TELEMETRY, 70, 75, Action.REVIEW,
                        "Telemetri & registrasi perangkat ByteDance",
                        "Mengurangi pelacakan, TETAPI pada sebagian versi aplikasi bisa membuat video gagal diputar " +
                                "karena registrasi perangkat ikut terblokir. Hanya diblokir di Mode Agresif.",
                        listOf("Pola subdomain '$head' = log/telemetri ByteDance", "Juga dipakai untuk device_register")
                    )
                }
            }
        }

        // Lapis 2b: domain inti aplikasi drama
        matchRule(domain, coreRules)?.let {
            return Verdict(
                domain, Category.CORE_APP, 5, 95, Action.ALLOW,
                it.name,
                "Jika diblokir: aplikasi gagal memuat drama, video, gambar, login, atau pembayaran.",
                listOf("Domain inti yang dipakai APK Melolo")
            )
        }
        // Lapis 2c: layanan sistem umum
        matchRule(domain, systemRules)?.let {
            return Verdict(
                domain, Category.SYSTEM, 8, 85, Action.ALLOW,
                "Layanan sistem / platform umum",
                "Jika diblokir: login Google, Play Store, notifikasi, atau aplikasi lain bisa terganggu.",
                listOf("Termasuk layanan sistem yang dibutuhkan banyak aplikasi")
            )
        }

        // Lapis 3: model skor untuk domain yang belum dikenal
        var logOdds = BASE_LOG_ODDS
        val reasons = mutableListOf<String>()
        val seen = HashSet<String>()
        for (t in tokens) {
            if (!seen.add(t)) continue
            val w = tokenWeights[t] ?: continue
            logOdds += w
            reasons.add(if (w > 0) "Token '$t' mengarah ke iklan/pelacak (+${fmt(w)})" else "Token '$t' mengarah ke konten penting (${fmt(w)})")
        }
        // Label acak panjang (mis. d1tru86qrby720) sering dipakai CDN iklan / tracker
        val randomLabel = domain.split('.').firstOrNull { l -> l.length >= 12 && l.count { it.isDigit() } >= 3 && l.any { it.isLetter() } }
        if (randomLabel != null) {
            logOdds += 0.6
            reasons.add("Label acak '$randomLabel' (pola CDN/tracker) (+0.6)")
        }
        if (reasons.isEmpty()) reasons.add("Tidak ada pola iklan maupun konten yang dikenali")

        val score = (100.0 / (1.0 + exp(-logOdds))).roundToInt().coerceIn(1, 99)
        val action = when {
            score >= BLOCK_THRESHOLD -> Action.BLOCK
            score >= REVIEW_THRESHOLD -> Action.REVIEW
            else -> Action.ALLOW
        }
        val confidence = (kotlin.math.abs(score - 50) * 2).coerceIn(20, 90)
        val (purpose, impact) = when (action) {
            Action.BLOCK -> "Kemungkinan besar server iklan/pelacak" to
                    "Kemungkinan tidak ada fitur yang rusak. Izinkan kembali jika ada yang terganggu."
            Action.REVIEW -> "Mencurigakan (bisa iklan atau layanan pendukung)" to
                    "Hanya diblokir di Mode Agresif. Coba blokir manual lalu cek apakah aplikasi masih normal."
            Action.ALLOW -> "Server umum / konten" to
                    "Jika diblokir: fitur aplikasi yang memakai server ini bisa gagal dimuat."
        }
        return Verdict(domain, Category.UNKNOWN, score, confidence, action, purpose, impact, reasons)
    }

    private fun fmt(d: Double): String = String.format(java.util.Locale.US, "%.1f", d)
}
