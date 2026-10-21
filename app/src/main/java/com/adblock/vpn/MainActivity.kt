package com.adblock.vpn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.adblock.vpn.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_VPN_PERMISSION = 2024
        private const val REQUEST_NOTIFICATION_PERMISSION = 2025
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var dnsLogAdapter: DnsLogAdapter

    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            try {
                updateTimerDisplay()
                updateLogsDisplay()
                updateStatsDisplay()
                timerHandler.postDelayed(this, 1000)
            } catch (t: Throwable) {
                saveCrashLog(t)
                showErrorUi(t)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            saveCrashLog(throwable)
        }

        super.onCreate(savedInstanceState)

        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // Inisialisasi RecyclerView untuk log interaktif
            dnsLogAdapter = DnsLogAdapter(emptyList()) { logEntry ->
                toggleDomainStatusFromLog(logEntry)
            }
            binding.rvLogs.layoutManager = LinearLayoutManager(this)
            binding.rvLogs.adapter = dnsLogAdapter

            setupListeners()
            setupSettingsUi()
        } catch (t: Throwable) {
            saveCrashLog(t)
            showErrorUi(t)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        try {
            updateUi()
            updateLogsDisplay()
            updateStatsDisplay()
            timerHandler.post(timerRunnable)
        } catch (t: Throwable) {
            saveCrashLog(t)
            showErrorUi(t)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::binding.isInitialized) {
            timerHandler.removeCallbacks(timerRunnable)
        }
    }

    private fun setupListeners() {
        if (!::binding.isInitialized) return

        binding.btnToggleVpn.setOnClickListener {
            if (LocalVpnService.isRunning) {
                val intent = Intent(this, LocalVpnService::class.java).apply {
                    action = LocalVpnService.ACTION_STOP
                }
                startService(intent)

                binding.btnToggleVpn.postDelayed({
                    updateUi()
                    updateLogsDisplay()
                    updateStatsDisplay()
                }, 300)
            } else {
                val vpnPrepareIntent = VpnService.prepare(this)
                if (vpnPrepareIntent != null) {
                    startActivityForResult(vpnPrepareIntent, REQUEST_VPN_PERMISSION)
                } else {
                    onActivityResult(REQUEST_VPN_PERMISSION, RESULT_OK, null)
                }
            }
        }

        binding.btnCopyLogs.setOnClickListener {
            val logsText = DnsLogManager.getLogs().joinToString("\n") { it.toString() }
            if (logsText.isEmpty()) {
                Toast.makeText(this, "Log kosong.", Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("DNS Logs", logsText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Log berhasil disalin!", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearLogs.setOnClickListener {
            DnsLogManager.clearLogs()
            updateLogsDisplay()
            updateStatsDisplay()
            Toast.makeText(this, "Log dan statistik berhasil dibersihkan.", Toast.LENGTH_SHORT).show()
        }

        binding.btnAnalyzeLogs.setOnClickListener {
            analyzeLogsWithHeuristics()
        }

        // --- CUSTOM WHITELIST & BLACKLIST LISTENERS ---
        updateCustomWhitelistUi()
        updateCustomBlacklistUi()

        binding.btnCustomWhitelistAdd.setOnClickListener {
            val domainInput = binding.etCustomWhitelistDomain.text.toString().trim().lowercase()
            if (domainInput.isEmpty()) {
                Toast.makeText(this, "Masukkan nama domain terlebih dahulu.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!domainInput.contains(".") || domainInput.length < 4) {
                Toast.makeText(this, "Format domain tidak valid.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            addToWhitelist(domainInput)
            binding.etCustomWhitelistDomain.text.clear()
        }

        binding.btnCustomWhitelistClear.setOnClickListener {
            val prefs = getSharedPreferences("AdBlockPrefs", Context.MODE_PRIVATE)
            prefs.edit().putStringSet("custom_whitelist", emptySet()).apply()
            com.adblock.vpn.dns.DnsPacketParser.setCustomWhitelist(emptySet())

            updateCustomWhitelistUi()
            Toast.makeText(this, "Semua domain kustom yang diizinkan berhasil dihapus.", Toast.LENGTH_SHORT).show()
        }

        binding.btnCustomBlacklistAdd.setOnClickListener {
            val domainInput = binding.etCustomBlacklistDomain.text.toString().trim().lowercase()
            if (domainInput.isEmpty()) {
                Toast.makeText(this, "Masukkan nama domain terlebih dahulu.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!domainInput.contains(".") || domainInput.length < 4) {
                Toast.makeText(this, "Format domain tidak valid.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            addToBlacklist(domainInput)
            binding.etCustomBlacklistDomain.text.clear()
        }

        binding.btnCustomBlacklistClear.setOnClickListener {
            val prefs = getSharedPreferences("AdBlockPrefs", Context.MODE_PRIVATE)
            prefs.edit().putStringSet("custom_blacklist", emptySet()).apply()
            com.adblock.vpn.dns.DnsPacketParser.setCustomBlacklist(emptySet())

            updateCustomBlacklistUi()
            Toast.makeText(this, "Semua domain kustom yang diblokir berhasil dihapus.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSettingsUi() {
        val prefs = getSharedPreferences("AdBlockPrefs", Context.MODE_PRIVATE)

        // 1. Inisialisasi Scope Mode
        val scopeMode = prefs.getString("vpn_scope_mode", "GLOBAL") ?: "GLOBAL"
        if (scopeMode == "MELOLO_ONLY") {
            binding.rbScopeMelolo.isChecked = true
        } else {
            binding.rbScopeGlobal.isChecked = true
        }

        binding.rgScopeMode.setOnCheckedChangeListener { _, checkedId ->
            val newScope = if (checkedId == R.id.rbScopeMelolo) "MELOLO_ONLY" else "GLOBAL"
            prefs.edit().putString("vpn_scope_mode", newScope).apply()

            if (LocalVpnService.isRunning) {
                Toast.makeText(this, "Pengaturan cakupan tersimpan. Nyalakan ulang VPN agar aktif.", Toast.LENGTH_SHORT).show()
            } else {
                val label = if (newScope == "GLOBAL") "Semua Aplikasi" else "Hanya Melolo"
                Toast.makeText(this, "Cakupan diubah ke: $label", Toast.LENGTH_SHORT).show()
            }
        }

        // 2. Inisialisasi Upstream DNS
        val upstreamDns = prefs.getString("upstream_dns", "ADGUARD") ?: "ADGUARD"
        when (upstreamDns) {
            "CLOUDFLARE_ADBLOCK" -> binding.rbDnsCloudflare.isChecked = true
            "GOOGLE" -> binding.rbDnsGoogle.isChecked = true
            else -> binding.rbDnsAdGuard.isChecked = true
        }

        binding.rgUpstreamDns.setOnCheckedChangeListener { _, checkedId ->
            val newDns = when (checkedId) {
                R.id.rbDnsCloudflare -> "CLOUDFLARE_ADBLOCK"
                R.id.rbDnsGoogle -> "GOOGLE"
                else -> "ADGUARD"
            }
            prefs.edit().putString("upstream_dns", newDns).apply()

            if (LocalVpnService.isRunning) {
                Toast.makeText(this, "Pengaturan DNS tersimpan. Nyalakan ulang VPN agar aktif.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Upstream DNS dipilih: $newDns", Toast.LENGTH_SHORT).show()
            }
        }

        // 3. Tombol Buka Pengaturan DNS HP
        binding.btnOpenDnsSettings.setOnClickListener {
            openSystemDnsSettings()
        }
    }

    private fun openSystemDnsSettings() {
        val intents = listOf(
            Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS),
            Intent("android.settings.NETWORK_OPERATOR_SETTINGS"),
            Intent(android.provider.Settings.ACTION_SETTINGS)
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (ignored: Exception) {}
        }
        Toast.makeText(this, "Buka Pengaturan HP > Koneksi > DNS Pribadi, lalu pilih 'Mati'.", Toast.LENGTH_LONG).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (!::binding.isInitialized) return
        if (requestCode == REQUEST_VPN_PERMISSION && resultCode == RESULT_OK) {
            checkAndStartVpnService()
        } else {
            Toast.makeText(this, "Izin VPN ditolak. AdBlocker tidak dapat diaktifkan.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndStartVpnService() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    arrayOf("android.permission.POST_NOTIFICATIONS"),
                    REQUEST_NOTIFICATION_PERMISSION
                )
                return
            }
        }
        startVpnServiceDirectly()
    }

    private fun startVpnServiceDirectly() {
        val intent = Intent(this, LocalVpnService::class.java).apply {
            action = LocalVpnService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)

        binding.btnToggleVpn.postDelayed({
            updateUi()
            updateStatsDisplay()
        }, 300)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            startVpnServiceDirectly()
        }
    }

    private fun updateUi() {
        if (!::binding.isInitialized) return
        val isActive = LocalVpnService.isRunning

        if (isActive) {
            binding.tvStatusTitle.text = "Perlindungan Aktif"
            binding.tvStatusDesc.text = "DNS Anda disaring secara lokal & upstream. Iklan berhasil diblokir."
            binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            binding.imgShield.setImageResource(android.R.drawable.ic_lock_idle_lock)
            binding.imgShield.setColorFilter(ContextCompat.getColor(this, R.color.status_active))
            binding.statusRing.setBackgroundResource(R.drawable.ring_active)

            binding.btnToggleVpn.text = "Matikan AdBlocker"
            binding.btnToggleVpn.setBackgroundColor(ContextCompat.getColor(this, R.color.btn_active))
        } else {
            binding.tvStatusTitle.text = "Perlindungan Nonaktif"
            binding.tvStatusDesc.text = "Lalu lintas DNS Anda belum disaring. Iklan sistem dapat muncul."
            binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            binding.imgShield.setImageResource(android.R.drawable.ic_lock_lock)
            binding.imgShield.setColorFilter(ContextCompat.getColor(this, R.color.status_inactive))
            binding.statusRing.setBackgroundResource(R.drawable.ring_inactive)

            binding.btnToggleVpn.text = "Aktifkan AdBlocker"
            binding.btnToggleVpn.setBackgroundColor(ContextCompat.getColor(this, R.color.btn_inactive))
        }
    }

    private fun updateTimerDisplay() {
        if (!::binding.isInitialized) return
        if (LocalVpnService.isRunning && LocalVpnService.startTimeMillis > 0) {
            val elapsedMillis = System.currentTimeMillis() - LocalVpnService.startTimeMillis
            val seconds = (elapsedMillis / 1000) % 60
            val minutes = (elapsedMillis / (1000 * 60)) % 60
            val hours = (elapsedMillis / (1000 * 60 * 60))

            val timeString = String.format("%02d:%02d:%02d", hours, seconds / 60, seconds % 60).let {
                String.format("%02d:%02d:%02d", hours, minutes, seconds)
            }
            binding.tvTimer.text = timeString
            binding.tvTimer.visibility = View.VISIBLE
        } else {
            binding.tvTimer.visibility = View.GONE
        }
    }

    private fun updateStatsDisplay() {
        if (!::binding.isInitialized) return
        val (blocked, allowed) = DnsLogManager.getStats()
        binding.tvBlockedCount.text = blocked.toString()
        binding.tvAllowedCount.text = allowed.toString()
    }

    private fun updateLogsDisplay() {
        if (!::binding.isInitialized) return
        val logs = DnsLogManager.getLogs()
        if (logs.isEmpty()) {
            binding.tvNoLogs.visibility = View.VISIBLE
            binding.rvLogs.visibility = View.GONE
        } else {
            binding.tvNoLogs.visibility = View.GONE
            binding.rvLogs.visibility = View.VISIBLE
            dnsLogAdapter.updateLogs(logs.reversed())
        }
    }

    private fun saveCrashLog(t: Throwable) {
        try {
            val dir = getExternalFilesDir(null)
            if (dir != null) {
                val file = java.io.File(dir, "crash_log.txt")
                java.io.FileWriter(file, false).use { writer ->
                    writer.append("Crash Timestamp: ${java.util.Date()}\n")
                    writer.append(Log.getStackTraceString(t))
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun showErrorUi(t: Throwable) {
        try {
            val scrollView = android.widget.ScrollView(this)
            val textView = android.widget.TextView(this).apply {
                val errorMsg = "APLIKASI CRASH!\n\n" +
                        "Silakan screenshot layar ini dan kirimkan ke chat.\n\n" +
                        "Error Details:\n" + Log.getStackTraceString(t)
                text = errorMsg
                setTextIsSelectable(true)
                setPadding(32, 32, 32, 32)
                textSize = 12f
                setTextColor(android.graphics.Color.RED)
                setBackgroundColor(android.graphics.Color.BLACK)
            }
            scrollView.addView(textView)
            setContentView(scrollView)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun updateCustomWhitelistUi() {
        val prefs = getSharedPreferences("AdBlockPrefs", Context.MODE_PRIVATE)
        val customWhitelists = prefs.getStringSet("custom_whitelist", emptySet()) ?: emptySet()
        if (customWhitelists.isEmpty()) {
            binding.tvCustomWhitelistList.text = "Belum ada domain kustom yang diizinkan."
        } else {
            val listText = customWhitelists.sorted().mapIndexed { index, domain ->
                "${index + 1}. $domain"
            }.joinToString("\n")
            binding.tvCustomWhitelistList.text = listText
        }
    }

    private fun updateCustomBlacklistUi() {
        val prefs = getSharedPreferences("AdBlockPrefs", Context.MODE_PRIVATE)
        val customBlacklists = prefs.getStringSet("custom_blacklist", emptySet()) ?: emptySet()
        if (customBlacklists.isEmpty()) {
            binding.tvCustomBlacklistList.text = "Belum ada domain kustom yang diblokir."
        } else {
            val listText = customBlacklists.sorted().mapIndexed { index, domain ->
                "${index + 1}. $domain"
            }.joinToString("\n")
            binding.tvCustomBlacklistList.text = listText
        }
    }

    private fun addToWhitelist(domain: String) {
        val prefs = getSharedPreferences("AdBlockPrefs", Context.MODE_PRIVATE)
        val customWhitelists = prefs.getStringSet("custom_whitelist", emptySet())?.toMutableSet() ?: mutableSetOf()
        val customBlacklists = prefs.getStringSet("custom_blacklist", emptySet())?.toMutableSet() ?: mutableSetOf()

        if (customWhitelists.contains(domain)) {
            Toast.makeText(this, "Domain sudah terdaftar di whitelist.", Toast.LENGTH_SHORT).show()
            return
        }

        if (customBlacklists.contains(domain)) {
            customBlacklists.remove(domain)
            prefs.edit().putStringSet("custom_blacklist", customBlacklists).apply()
            com.adblock.vpn.dns.DnsPacketParser.setCustomBlacklist(customBlacklists)
            updateCustomBlacklistUi()
        }

        customWhitelists.add(domain)
        prefs.edit().putStringSet("custom_whitelist", customWhitelists).apply()
        com.adblock.vpn.dns.DnsPacketParser.setCustomWhitelist(customWhitelists)

        updateCustomWhitelistUi()
        Toast.makeText(this, "$domain berhasil diizinkan!", Toast.LENGTH_SHORT).show()
    }

    private fun addToBlacklist(domain: String) {
        val prefs = getSharedPreferences("AdBlockPrefs", Context.MODE_PRIVATE)
        val customWhitelists = prefs.getStringSet("custom_whitelist", emptySet())?.toMutableSet() ?: mutableSetOf()
        val customBlacklists = prefs.getStringSet("custom_blacklist", emptySet())?.toMutableSet() ?: mutableSetOf()

        if (customBlacklists.contains(domain)) {
            Toast.makeText(this, "Domain sudah terdaftar di blacklist.", Toast.LENGTH_SHORT).show()
            return
        }

        if (customWhitelists.contains(domain)) {
            customWhitelists.remove(domain)
            prefs.edit().putStringSet("custom_whitelist", customWhitelists).apply()
            com.adblock.vpn.dns.DnsPacketParser.setCustomWhitelist(customWhitelists)
            updateCustomWhitelistUi()
        }

        customBlacklists.add(domain)
        prefs.edit().putStringSet("custom_blacklist", customBlacklists).apply()
        com.adblock.vpn.dns.DnsPacketParser.setCustomBlacklist(customBlacklists)

        updateCustomBlacklistUi()
        Toast.makeText(this, "$domain berhasil diblokir!", Toast.LENGTH_SHORT).show()
    }

    private fun toggleDomainStatusFromLog(logEntry: DnsLogManager.LogEntry) {
        val domain = logEntry.domain.lowercase().trim()
        if (logEntry.isBlocked) {
            addToWhitelist(domain)
        } else {
            addToBlacklist(domain)
        }
        updateLogsDisplay()
    }

    private fun getDomainDetailedExplanation(domain: String): Triple<String, String, String> {
        val d = domain.lowercase().trim()
        return when {
            // Core Melolo / Fizzo Services
            d.contains("melolo.org") || d.contains("melolostatic.com") -> Triple(
                "Server Utama & Database API Melolo",
                "Aplikasi tidak bisa dibuka, terjadi kesalahan koneksi server, login gagal, dan novel tidak bisa dimuat.",
                "🔴 JANGAN DIBLOKIR (Koneksi Inti)"
            )
            d.contains("fizzo.org") || d.contains("fizzopic.org") -> Triple(
                "Server Gambar & Cover Buku Fizzo",
                "Cover buku novel, gambar profil, dan ilustrasi visual di dalam novel tidak akan muncul.",
                "🔴 JANGAN DIBLOKIR (Konten Novel)"
            )
            d.contains("tmtreader.com") -> Triple(
                "Mesin Pembaca & Pustaka Teks Novel",
                "Halaman membaca novel akan kosong (blank putih), teks isi bab novel tidak bisa muncul.",
                "🔴 JANGAN DIBLOKIR (Mesin Novel)"
            )
            d.contains("ibyteimg.com") -> Triple(
                "CDN Server Gambar & Aset Media",
                "Gambar novel, ilustrasi bab, dan aset grafis antarmuka tidak akan bisa ditampilkan.",
                "🔴 JANGAN DIBLOKIR (Aset Media)"
            )
            d.contains("pipopay.com") -> Triple(
                "Gerbang Transaksi Pembayaran Resmi",
                "Proses top-up koin dan transaksi VIP di aplikasi akan gagal.",
                "🟡 PILIHAN ANDA (Abaikan jika tidak belanja)"
            )

            // Known Ad Networks (High Priority Block)
            d.contains("pangle") || d.contains("pangolin") -> Triple(
                "Jaringan Iklan Pangle SDK (ByteDance / Melolo)",
                "Iklan banner pop-up dan video reward saat membaca novel akan hilang sepenuhnya.",
                "🟢 AMAN DIBLOKIR (Sangat Direkomendasikan)"
            )
            d.contains("toblog") || d.contains("rtlog") || d.contains("applog") || d.contains("mon.byte") -> Triple(
                "Server Pelacak & Telemetri Iklan ByteDance",
                "Menghentikan pengiriman data telemetri baca untuk target iklan di aplikasi Melolo.",
                "🟢 AMAN DIBLOKIR (Sangat Direkomendasikan)"
            )
            d.contains("api-ad") || d.contains("ads-api") || d.contains("ad-sg") -> Triple(
                "Server Distribusi Kampanye Iklan ByteDance",
                "Mencegah pengambilan materi iklan video dan banner dari server iklan ByteDance.",
                "🟢 AMAN DIBLOKIR (Sangat Direkomendasikan)"
            )
            d.contains("pagead") || d.contains("doubleclick") || d.contains("adservice") || d.contains("googleads") -> Triple(
                "Jaringan Iklan Google Ads / AdMob / DoubleClick",
                "Iklan banner dan selipan dari jaringan iklan Google akan terblokir tuntas.",
                "🟢 AMAN DIBLOKIR (Sangat Direkomendasikan)"
            )
            d.contains("applovin") || d.contains("applvn") -> Triple(
                "Jaringan Server Iklan AppLovin SDK",
                "Iklan video layar penuh yang muncul berkala saat mengganti halaman bab akan hilang.",
                "🟢 AMAN DIBLOKIR (Sangat Direkomendasikan)"
            )
            d.contains("unityads") || d.contains("unity3d") -> Triple(
                "Jaringan Iklan Game Unity Ads SDK",
                "Iklan video interaktif berdurasi 30 detik yang memotong aktivitas membaca akan hilang.",
                "🟢 AMAN DIBLOKIR (Sangat Direkomendasikan)"
            )
            d.contains("mintegral") || d.contains("rayjump") || d.contains("mtgglobals") -> Triple(
                "Jaringan Server Iklan Mintegral SDK",
                "Iklan interstisial (selipan) di sela-sela bab novel akan terblokir sempurna.",
                "🟢 AMAN DIBLOKIR (Sangat Direkomendasikan)"
            )
            d.contains("vungle") || d.contains("liftoff") -> Triple(
                "Jaringan Server Iklan Video Vungle / Liftoff",
                "Iklan promosi video game yang memotong bab membaca tidak akan muncul.",
                "🟢 AMAN DIBLOKIR (Sangat Direkomendasikan)"
            )
            d.contains("moloco") || d.contains("adsmoloco") -> Triple(
                "Jaringan Server Iklan Moloco SDK",
                "Iklan banner penawaran produk komersial di pojok bawah layar tidak akan tampil.",
                "🟢 AMAN DIBLOKIR (Sangat Direkomendasikan)"
            )
            d.contains("inmobi") || d.contains("inmobicdn") -> Triple(
                "Jaringan Server Iklan Seluler InMobi",
                "Iklan splash pembuka aplikasi akan dihilangkan.",
                "🟢 AMAN DIBLOKIR (Sangat Direkomendasikan)"
            )
            d.contains("ironsrc") || d.contains("supersonicads") -> Triple(
                "Jaringan Iklan IronSource / Supersonic",
                "Iklan penawaran reward koin dari jaringan pihak ketiga akan diblokir.",
                "🟢 AMAN DIBLOKIR (Sangat Direkomendasikan)"
            )
            d.contains("adcolony") -> Triple(
                "Jaringan Server Iklan Video AdColony",
                "Iklan video promosi yang memboroskan kuota internet akan diblokir.",
                "🟢 AMAN DIBLOKIR (Sangat Direkomendasikan)"
            )
            d.contains("adjust") || d.contains("appsflyer") || d.contains("branch.io") || d.contains("kochava") -> Triple(
                "Layanan Atribusi & Pelacakan Pengguna",
                "Aman diblokir. Mencegah jaringan iklan mencatat riwayat instalasi dan tautan Anda.",
                "🟢 AMAN DIBLOKIR (Rekomendasi Privasi)"
            )
            d.contains("crashlytics") || d.contains("bugsnag") -> Triple(
                "Sistem Pelacak Eror & Log Kerusakan",
                "Aman diblokir. Developer tidak menerima laporan crash otomatis.",
                "🟢 AMAN DIBLOKIR (Rekomendasi Sistem)"
            )

            // Google Core Services
            d.contains("googleapis.com") || d.contains("gstatic.com") -> Triple(
                "Server CDN Font & Library Google",
                "Tampilan jenis tulisan (font), ikon antarmuka, dan library bawaan sistem.",
                "🔴 JANGAN DIBLOKIR (Sistem Font & UI)"
            )
            d.contains("google.com") -> Triple(
                "Layanan Mesin Pencari & Core Google",
                "Integrasi fitur Google Play Service dan Login akun Google.",
                "🟡 PILIHAN ANDA (Aman diizinkan)"
            )

            // Video Streaming
            d.contains("video") || d.contains("stream") || d.contains("media") || d.contains("hls") || d.contains("m3u8") || d.contains("mp4") -> Triple(
                "Server Putar Video & Streaming Media",
                "Video drama pendek atau audio di dalam aplikasi tidak akan bisa diputar jika diblokir.",
                "🔴 JANGAN DIBLOKIR (Pemutar Media)"
            )

            // CDN Data
            d.contains("cdn") || d.contains("storage") || d.contains("cloud") || d.contains("byteoversea") -> Triple(
                "Server Distribusi File & Konten (CDN)",
                "Pemuatan bab novel baru atau pengunduhan sampul akan lambat jika diblokir.",
                "🔴 JANGAN DIBLOKIR (CDN Data)"
            )

            // Auth
            d.contains("auth") || d.contains("login") || d.contains("byteintl") -> Triple(
                "Sistem Autentikasi Akun & API Server",
                "Anda tidak bisa masuk (login) atau sinkronisasi rak buku novel jika diblokir.",
                "🔴 JANGAN DIBLOKIR (Autentikasi)"
            )

            else -> Triple(
                "Server Umum / Koneksi Tidak Dikenal",
                "Layanan lalu lintas data umum dari library pihak ketiga atau server CDN yang belum dikategorikan.",
                "🟡 PILIHAN ANDA (Blokir jika curiga, izinkan jika ada fitur app terganggu)"
            )
        }
    }

    private fun analyzeLogsWithHeuristics() {
        val logs = DnsLogManager.getLogs()
        if (logs.isEmpty()) {
            Toast.makeText(this, "Log aktivitas DNS masih kosong.", Toast.LENGTH_SHORT).show()
            return
        }

        val uniqueDomains = logs.map { it.domain.lowercase().trim() }.distinct()

        val currentlyBlocked = mutableListOf<String>()
        val currentlyAllowed = mutableListOf<String>()

        for (domain in uniqueDomains) {
            if (com.adblock.vpn.dns.DnsPacketParser.isAdDomain(domain)) {
                currentlyBlocked.add(domain)
            } else {
                currentlyAllowed.add(domain)
            }
        }

        showAiRecommendationsDialog(currentlyBlocked, currentlyAllowed)
    }

    private fun showAiRecommendationsDialog(blockedList: List<String>, allowedList: List<String>) {
        val mutableBlocked = blockedList.toMutableList()
        val mutableAllowed = allowedList.toMutableList()
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Analisis AI & Info Domain")

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }

        val scrollView = android.widget.ScrollView(this).apply {
            addView(container)
        }

        fun populateDialogViews() {
            container.removeAllViews()

            if (mutableAllowed.isNotEmpty()) {
                val tvHeaderAllowed = android.widget.TextView(this).apply {
                    text = "🟢 LALU LINTAS DIIZINKAN (Ketuk untuk Blokir)"
                    setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 16, 0, 8)
                }
                container.addView(tvHeaderAllowed)

                for (domain in mutableAllowed) {
                    val itemLayout = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding(12, 12, 12, 12)
                        setBackgroundColor(android.graphics.Color.parseColor("#2E2E3E"))
                        val params = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 6, 0, 6)
                        }
                        layoutParams = params
                    }

                    val tvDomain = android.widget.TextView(this).apply {
                        text = domain
                        textSize = 13f
                        setTextColor(android.graphics.Color.WHITE)
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }

                    val (purpose, impact, rating) = getDomainDetailedExplanation(domain)

                    val tvRating = android.widget.TextView(this).apply {
                        text = "Rekomendasi AI: $rating"
                        textSize = 11f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(when {
                            rating.contains("🔴") -> android.graphics.Color.parseColor("#FF6B6B")
                            rating.contains("🟢") -> android.graphics.Color.parseColor("#4CAF50")
                            else -> android.graphics.Color.parseColor("#FFC107")
                        })
                        setPadding(0, 4, 0, 2)
                    }

                    val tvExplanation = android.widget.TextView(this).apply {
                        text = "Fungsi: $purpose\nEfek jika diblokir: $impact"
                        textSize = 11f
                        setTextColor(android.graphics.Color.parseColor("#A0A0B0"))
                        setPadding(0, 2, 0, 8)
                    }

                    val btnBlock = com.google.android.material.button.MaterialButton(this).apply {
                        text = "Blokir"
                        textSize = 10f
                        setTextColor(android.graphics.Color.WHITE)
                        setBackgroundColor(android.graphics.Color.parseColor("#FF6B6B"))
                        setOnClickListener {
                            addToBlacklist(domain)
                            mutableAllowed.remove(domain)
                            mutableBlocked.add(domain)
                            populateDialogViews()
                        }
                    }

                    itemLayout.addView(tvDomain)
                    itemLayout.addView(tvRating)
                    itemLayout.addView(tvExplanation)
                    itemLayout.addView(btnBlock)
                    container.addView(itemLayout)
                }
            }

            if (mutableBlocked.isNotEmpty()) {
                val tvHeaderBlocked = android.widget.TextView(this).apply {
                    text = "🔴 LALU LINTAS DIBLOKIR (Ketuk untuk Izinkan)"
                    setTextColor(android.graphics.Color.parseColor("#FF6B6B"))
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, 24, 0, 8)
                }
                container.addView(tvHeaderBlocked)

                for (domain in mutableBlocked) {
                    val itemLayout = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding(12, 12, 12, 12)
                        setBackgroundColor(android.graphics.Color.parseColor("#2E2E3E"))
                        val params = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 6, 0, 6)
                        }
                        layoutParams = params
                    }

                    val tvDomain = android.widget.TextView(this).apply {
                        text = domain
                        textSize = 13f
                        setTextColor(android.graphics.Color.WHITE)
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }

                    val (purpose, impact, rating) = getDomainDetailedExplanation(domain)

                    val tvRating = android.widget.TextView(this).apply {
                        text = "Rekomendasi AI: $rating"
                        textSize = 11f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(when {
                            rating.contains("🔴") -> android.graphics.Color.parseColor("#FF6B6B")
                            rating.contains("🟢") -> android.graphics.Color.parseColor("#4CAF50")
                            else -> android.graphics.Color.parseColor("#FFC107")
                        })
                        setPadding(0, 4, 0, 2)
                    }

                    val tvExplanation = android.widget.TextView(this).apply {
                        text = "Fungsi: $purpose\nEfek jika diblokir: $impact"
                        textSize = 11f
                        setTextColor(android.graphics.Color.parseColor("#A0A0B0"))
                        setPadding(0, 2, 0, 8)
                    }

                    val btnAllow = com.google.android.material.button.MaterialButton(this).apply {
                        text = "Izinkan"
                        textSize = 10f
                        setTextColor(android.graphics.Color.WHITE)
                        setBackgroundColor(android.graphics.Color.parseColor("#03DAC6"))
                        setOnClickListener {
                            addToWhitelist(domain)
                            mutableBlocked.remove(domain)
                            mutableAllowed.add(domain)
                            populateDialogViews()
                        }
                    }

                    itemLayout.addView(tvDomain)
                    itemLayout.addView(tvRating)
                    itemLayout.addView(tvExplanation)
                    itemLayout.addView(btnAllow)
                    container.addView(itemLayout)
                }
            }

            if (mutableBlocked.isEmpty() && mutableAllowed.isEmpty()) {
                val tvEmpty = android.widget.TextView(this).apply {
                    text = "Tidak ada aktivitas koneksi terdeteksi."
                    setTextColor(android.graphics.Color.GRAY)
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, 32, 0, 32)
                }
                container.addView(tvEmpty)
            }
        }

        populateDialogViews()
        builder.setView(scrollView)
        builder.setPositiveButton("Selesai", null)
        builder.show()
    }
}
