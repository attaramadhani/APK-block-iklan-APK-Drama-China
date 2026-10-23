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
import com.adblock.vpn.ai.AiVerdictStore
import com.adblock.vpn.ai.CloudAiAnalyzer
import com.adblock.vpn.ai.DomainAiAnalyzer
import com.adblock.vpn.databinding.ActivityMainBinding
import com.adblock.vpn.dns.DnsPacketParser

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
            setupAiUi()
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
            analyzeLogsWithAi()
        }

        // --- CUSTOM WHITELIST & BLACKLIST LISTENERS ---
        val listPrefs = getSharedPreferences("AdBlockPrefs", Context.MODE_PRIVATE)
        DnsPacketParser.setCustomWhitelist(listPrefs.getStringSet("custom_whitelist", emptySet()) ?: emptySet())
        DnsPacketParser.setCustomBlacklist(listPrefs.getStringSet("custom_blacklist", emptySet()) ?: emptySet())
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
            DnsPacketParser.setCustomWhitelist(emptySet())

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
            DnsPacketParser.setCustomBlacklist(emptySet())

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

            val timeString = String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
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
            DnsPacketParser.setCustomBlacklist(customBlacklists)
            updateCustomBlacklistUi()
        }

        customWhitelists.add(domain)
        prefs.edit().putStringSet("custom_whitelist", customWhitelists).apply()
        DnsPacketParser.setCustomWhitelist(customWhitelists)

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
            DnsPacketParser.setCustomWhitelist(customWhitelists)
            updateCustomWhitelistUi()
        }

        customBlacklists.add(domain)
        prefs.edit().putStringSet("custom_blacklist", customBlacklists).apply()
        DnsPacketParser.setCustomBlacklist(customBlacklists)

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

    // =====================================================================
    //  FITUR AI: ANALISIS DOMAIN MANA YANG BOLEH DIBLOKIR & TIDAK
    // =====================================================================

    private fun updateAiStatus() {
        if (!::binding.isInitialized) return
        val prefs = getSharedPreferences("AdBlockPrefs", Context.MODE_PRIVATE)
        val hasKey = !prefs.getString("gemini_api_key", "").isNullOrBlank()
        val cloudCount = AiVerdictStore.size()
        val mode = when {
            !DnsPacketParser.aiAutoEnabled -> "AI Otomatis MATI (hanya daftar bawaan)"
            DnsPacketParser.aggressiveMode -> "AI Lokal aktif · Mode Agresif"
            else -> "AI Lokal aktif · Mode Seimbang"
        }
        val online = if (hasKey) "AI Online siap · $cloudCount keputusan tersimpan" else "AI Online belum diatur"
        binding.tvAiStatus.text = "$mode\n$online"
    }

    private fun setupAiUi() {
        val prefs = getSharedPreferences("AdBlockPrefs", Context.MODE_PRIVATE)
        AiVerdictStore.load(this)
        DnsPacketParser.aiAutoEnabled = prefs.getBoolean("ai_auto_enabled", true)
        DnsPacketParser.aggressiveMode = prefs.getBoolean("aggressive_mode", false)

        binding.swAiAuto.isChecked = DnsPacketParser.aiAutoEnabled
        binding.swAggressive.isChecked = DnsPacketParser.aggressiveMode

        binding.swAiAuto.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("ai_auto_enabled", checked).apply()
            DnsPacketParser.aiAutoEnabled = checked
            updateAiStatus()
        }
        binding.swAggressive.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("aggressive_mode", checked).apply()
            DnsPacketParser.aggressiveMode = checked
            if (checked) {
                Toast.makeText(this, "Mode Agresif aktif. Jika video Melolo gagal diputar, matikan mode ini.", Toast.LENGTH_LONG).show()
            }
            updateAiStatus()
        }

        val savedKey = prefs.getString("gemini_api_key", "") ?: ""
        if (savedKey.isNotEmpty()) binding.etGeminiKey.setText(savedKey)

        binding.btnSaveGeminiKey.setOnClickListener {
            val key = binding.etGeminiKey.text.toString().trim()
            prefs.edit().putString("gemini_api_key", key).apply()
            Toast.makeText(this, if (key.isEmpty()) "API key dihapus." else "API key Gemini tersimpan.", Toast.LENGTH_SHORT).show()
            updateAiStatus()
        }

        binding.btnCloudAnalyze.setOnClickListener { runCloudAnalysis() }

        binding.btnClearCloudAi.setOnClickListener {
            AiVerdictStore.clear(this)
            updateAiStatus()
            Toast.makeText(this, "Semua keputusan AI Online dihapus.", Toast.LENGTH_SHORT).show()
        }

        updateAiStatus()
    }

    /** Domain dari log yang belum pasti (bukan bagian basis pengetahuan) dikirim ke AI Online. */
    private fun runCloudAnalysis() {
        val prefs = getSharedPreferences("AdBlockPrefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Isi dan simpan API key Gemini terlebih dahulu.", Toast.LENGTH_LONG).show()
            return
        }
        val candidates = DnsLogManager.getLogs()
            .map { it.domain.lowercase().trim() }
            .filter { it.contains('.') }
            .distinct()
            .filter {
                val c = DomainAiAnalyzer.analyze(it).category
                c == DomainAiAnalyzer.Category.UNKNOWN || c == DomainAiAnalyzer.Category.TELEMETRY
            }
            .filter { AiVerdictStore.get(it) == null }
            .take(120)

        if (candidates.isEmpty()) {
            Toast.makeText(this, "Tidak ada domain baru yang perlu dianalisis online. Buka Melolo dulu agar log terisi.", Toast.LENGTH_LONG).show()
            return
        }

        binding.btnCloudAnalyze.isEnabled = false
        binding.btnCloudAnalyze.text = "Menganalisis..."
        Thread {
            val result = try {
                Result.success(CloudAiAnalyzer.analyze(apiKey, candidates))
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                if (!::binding.isInitialized || isFinishing) return@runOnUiThread
                binding.btnCloudAnalyze.isEnabled = true
                binding.btnCloudAnalyze.text = "Analisis Online"
                result.onSuccess { verdicts ->
                    AiVerdictStore.putAll(this, verdicts)
                    updateAiStatus()
                    val blocked = verdicts.count { it.value.block }
                    Toast.makeText(
                        this,
                        "AI Online menganalisis ${verdicts.size} domain: $blocked disarankan diblokir.",
                        Toast.LENGTH_LONG
                    ).show()
                    analyzeLogsWithAi()
                }.onFailure { e ->
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("AI Online gagal")
                        .setMessage(e.message ?: "Terjadi kesalahan.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun analyzeLogsWithAi() {
        val logs = DnsLogManager.getLogs()
        if (logs.isEmpty()) {
            Toast.makeText(this, "Log aktivitas DNS masih kosong. Aktifkan VPN lalu buka Melolo.", Toast.LENGTH_LONG).show()
            return
        }
        val domains = logs.map { it.domain.lowercase().trim() }.filter { it.isNotEmpty() }.distinct()
        showAiRecommendationsDialog(domains)
    }

    private fun showAiRecommendationsDialog(domains: List<String>) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        val scrollView = android.widget.ScrollView(this).apply { addView(container) }

        fun colorFor(action: DomainAiAnalyzer.Action) = android.graphics.Color.parseColor(
            when (action) {
                DomainAiAnalyzer.Action.BLOCK -> "#4CAF50"
                DomainAiAnalyzer.Action.REVIEW -> "#FFC107"
                DomainAiAnalyzer.Action.ALLOW -> "#FF6B6B"
            }
        )

        fun populate() {
            container.removeAllViews()
            val decisions = domains.map { it to DnsPacketParser.decide(it) }
            val conflicts = decisions.count { (_, d) ->
                (d.verdict.action == DomainAiAnalyzer.Action.BLOCK && !d.blocked) ||
                        (d.verdict.action == DomainAiAnalyzer.Action.ALLOW && d.blocked &&
                                d.verdict.category != DomainAiAnalyzer.Category.UNKNOWN)
            }

            container.addView(android.widget.TextView(this).apply {
                val adCount = decisions.count { it.second.verdict.action == DomainAiAnalyzer.Action.BLOCK }
                val coreCount = decisions.count { it.second.verdict.action == DomainAiAnalyzer.Action.ALLOW }
                val reviewCount = decisions.size - adCount - coreCount
                text = "AI menganalisis ${decisions.size} domain:\n" +
                        "🟢 $adCount aman diblokir · 🟡 $reviewCount perlu ditinjau · 🔴 $coreCount jangan diblokir\n" +
                        if (conflicts > 0) "⚠️ $conflicts domain belum sesuai rekomendasi AI." else "✅ Semua pengaturan sudah sesuai rekomendasi AI."
                setTextColor(android.graphics.Color.WHITE)
                textSize = 12f
                setPadding(0, dp(4), 0, dp(8))
            })

            if (conflicts > 0) {
                container.addView(com.google.android.material.button.MaterialButton(this).apply {
                    text = "Terapkan Semua Rekomendasi AI"
                    textSize = 11f
                    setBackgroundColor(android.graphics.Color.parseColor("#6200EE"))
                    setOnClickListener {
                        applyAiRecommendations(decisions)
                        populate()
                    }
                })
            }

            // Urutkan: perlu tindakan dulu, lalu iklan, ditinjau, inti
            val sorted = decisions.sortedWith(compareBy<Pair<String, DnsPacketParser.FilterDecision>> {
                when (it.second.verdict.action) {
                    DomainAiAnalyzer.Action.BLOCK -> if (!it.second.blocked) 0 else 2
                    DomainAiAnalyzer.Action.REVIEW -> 3
                    DomainAiAnalyzer.Action.ALLOW -> if (it.second.blocked) 1 else 4
                }
            }.thenByDescending { it.second.verdict.adScore })

            for ((domain, decision) in sorted.take(200)) {
                val v = decision.verdict
                val item = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setBackgroundColor(android.graphics.Color.parseColor("#2E2E3E"))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, dp(4), 0, dp(4)) }
                }
                item.addView(android.widget.TextView(this).apply {
                    text = domain
                    textSize = 13f
                    setTextColor(android.graphics.Color.WHITE)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
                item.addView(android.widget.TextView(this).apply {
                    text = "Rekomendasi AI: ${v.action.label}\n" +
                            "Kategori: ${v.category.label} · Skor iklan ${v.adScore}% · Keyakinan ${v.confidence}%"
                    textSize = 11f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(colorFor(v.action))
                    setPadding(0, dp(2), 0, dp(2))
                })
                val cloud = AiVerdictStore.get(domain)
                val cloudText = if (cloud != null) {
                    "\nAI Online: ${if (cloud.block) "blokir" else "izinkan"} (${cloud.category}) — ${cloud.reason}"
                } else ""
                item.addView(android.widget.TextView(this).apply {
                    text = "Fungsi: ${v.purpose}\nEfek jika diblokir: ${v.impact}\n" +
                            "Alasan: ${v.reasons.joinToString("; ")}$cloudText\n" +
                            "Status sekarang: ${if (decision.blocked) "DIBLOKIR" else "DIIZINKAN"} (${decision.source})"
                    textSize = 11f
                    setTextColor(android.graphics.Color.parseColor("#A0A0B0"))
                    setPadding(0, dp(2), 0, dp(6))
                })
                item.addView(com.google.android.material.button.MaterialButton(this).apply {
                    text = if (decision.blocked) "Izinkan" else "Blokir"
                    textSize = 10f
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(android.graphics.Color.parseColor(if (decision.blocked) "#03DAC6" else "#FF6B6B"))
                    setOnClickListener {
                        if (decision.blocked) addToWhitelist(domain) else addToBlacklist(domain)
                        populate()
                    }
                })
                container.addView(item)
            }
        }

        populate()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🤖 Analisis AI: Boleh Diblokir atau Tidak?")
            .setView(scrollView)
            .setPositiveButton("Selesai") { _, _ -> updateLogsDisplay() }
            .show()
    }

    /** Menyelaraskan daftar kustom dengan rekomendasi AI. */
    private fun applyAiRecommendations(decisions: List<Pair<String, DnsPacketParser.FilterDecision>>) {
        val prefs = getSharedPreferences("AdBlockPrefs", Context.MODE_PRIVATE)
        val whitelist = prefs.getStringSet("custom_whitelist", emptySet())?.toMutableSet() ?: mutableSetOf()
        val blacklist = prefs.getStringSet("custom_blacklist", emptySet())?.toMutableSet() ?: mutableSetOf()
        var changed = 0
        for ((domain, d) in decisions) {
            val v = d.verdict
            if (v.action == DomainAiAnalyzer.Action.BLOCK && !d.blocked) {
                whitelist.remove(domain); blacklist.add(domain); changed++
            } else if (v.action == DomainAiAnalyzer.Action.ALLOW && d.blocked &&
                v.category != DomainAiAnalyzer.Category.UNKNOWN
            ) {
                blacklist.remove(domain); whitelist.add(domain); changed++
            }
        }
        prefs.edit()
            .putStringSet("custom_whitelist", whitelist)
            .putStringSet("custom_blacklist", blacklist)
            .apply()
        DnsPacketParser.setCustomWhitelist(whitelist)
        DnsPacketParser.setCustomBlacklist(blacklist)
        updateCustomWhitelistUi()
        updateCustomBlacklistUi()
        Toast.makeText(this, "$changed domain disesuaikan dengan rekomendasi AI.", Toast.LENGTH_SHORT).show()
    }
}
