package com.adblock.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.adblock.vpn.ai.AiVerdictStore
import com.adblock.vpn.dns.DnsPacketParser
import com.adblock.vpn.dns.IpUdpPacketBuilder
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class LocalVpnService : VpnService() {

    companion object {
        private const val TAG = "LocalVpnService"
        private const val CHANNEL_ID = "AdBlockVpnChannel"
        private const val NOTIFICATION_ID = 101

        const val ACTION_START = "com.adblock.vpn.START"
        const val ACTION_STOP = "com.adblock.vpn.STOP"

        @Volatile
        var isRunning = false

        @Volatile
        var startTimeMillis = 0L

        // Upstream DNS Server Config
        const val ADGUARD_PRIMARY = "94.140.14.14"
        const val ADGUARD_SECONDARY = "94.140.15.15"

        const val CLOUDFLARE_PRIMARY = "1.1.1.2"
        const val CLOUDFLARE_SECONDARY = "1.0.0.2"

        const val GOOGLE_PRIMARY = "8.8.8.8"
        const val GOOGLE_SECONDARY = "8.8.4.4"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnJob: Job? = null
    private val vpnScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null

    private var primaryDnsIp = ADGUARD_PRIMARY
    private var secondaryDnsIp = ADGUARD_SECONDARY

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        // ACTION_START, restart otomatis (intent null) dan Always-on VPN
        // (action "android.net.VpnService") semuanya memulai VPN.
        if (!isRunning) {
            startVpn()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun startVpn() {
        Log.i(TAG, "Memulai Layanan VPN...")
        isRunning = true
        startTimeMillis = System.currentTimeMillis()

        // Inisialisasi Log Manager
        DnsLogManager.init(this)

        // Load konfigurasi dari SharedPreferences
        val prefs = getSharedPreferences("AdBlockPrefs", Context.MODE_PRIVATE)

        // Whitelist & Blacklist Kustom
        val customDomains = prefs.getStringSet("custom_whitelist", emptySet()) ?: emptySet()
        DnsPacketParser.setCustomWhitelist(customDomains)

        val customBlockedDomains = prefs.getStringSet("custom_blacklist", emptySet()) ?: emptySet()
        DnsPacketParser.setCustomBlacklist(customBlockedDomains)

        // Pengaturan AI
        DnsPacketParser.aiAutoEnabled = prefs.getBoolean("ai_auto_enabled", true)
        DnsPacketParser.aggressiveMode = prefs.getBoolean("aggressive_mode", false)
        AiVerdictStore.load(this)
        dnsCache.clear()

        // Upstream DNS Mode
        val upstreamDnsType = prefs.getString("upstream_dns", "ADGUARD") ?: "ADGUARD"
        when (upstreamDnsType) {
            "CLOUDFLARE_ADBLOCK" -> {
                primaryDnsIp = CLOUDFLARE_PRIMARY
                secondaryDnsIp = CLOUDFLARE_SECONDARY
            }
            "GOOGLE" -> {
                primaryDnsIp = GOOGLE_PRIMARY
                secondaryDnsIp = GOOGLE_SECONDARY
            }
            else -> {
                // Default: AdGuard DNS (Filter Iklan Kuat)
                primaryDnsIp = ADGUARD_PRIMARY
                secondaryDnsIp = ADGUARD_SECONDARY
            }
        }
        Log.i(TAG, "Upstream DNS dikonfigurasi ke: $primaryDnsIp (Backup: $secondaryDnsIp)")

        // 1. Jalankan Foreground Service untuk kepatuhan OS Android
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // 2. Setup TUN Interface dan jalankan Packet Loop
        try {
            setupVpnInterface()
            startPacketLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menginisialisasi VPN: ${e.message}", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        if (!isRunning) return
        Log.i(TAG, "Menghentikan Layanan VPN...")
        isRunning = false
        startTimeMillis = 0L

        vpnJob?.cancel()
        closeResources()

        try {
            vpnInterface?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Gagal menutup interface TUN: ${e.message}")
        }
        vpnInterface = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun closeResources() {
        try {
            inputStream?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Gagal menutup inputStream: ${e.message}")
        }
        inputStream = null

        try {
            outputStream?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Gagal menutup outputStream: ${e.message}")
        }
        outputStream = null
    }

    private fun setupVpnInterface() {
        val prefs = getSharedPreferences("AdBlockPrefs", Context.MODE_PRIVATE)
        val scopeMode = prefs.getString("vpn_scope_mode", "GLOBAL") ?: "GLOBAL"

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            // Menetapkan IP lokal untuk interface TUN (alamat client)
            .addAddress("10.0.0.2", 24)
            // Konfigurasi IPv6 Dummy untuk mencegah "IPv6 DNS Leak"
            .addAddress("fd00:1:fd00:1:fd00:1:fd00:2", 120)

            // Menetapkan DNS server VIRTUAL (IP BERBEDA dari TUN address)
            .addDnsServer("10.0.0.1")
            .addDnsServer("fd00:1:fd00:1:fd00:1:fd00:1")

            // Rute DNS Virtual
            .addRoute("10.0.0.1", 32)
            .addRoute("fd00:1:fd00:1:fd00:1:fd00:1", 128)

            // --- INTERSEPSI DNS PUBLIK & DoH/DoT BLACKHOLE ---
            // Tangkap query DNS ke server DNS populer agar aplikasi tidak bisa membypass VPN
            // AdGuard DNS (Anti-Iklan)
            .addRoute("94.140.14.14", 32)
            .addRoute("94.140.15.15", 32)

            // Cloudflare DNS
            .addRoute("1.1.1.1", 32)
            .addRoute("1.0.0.1", 32)
            .addRoute("1.1.1.2", 32)
            .addRoute("1.0.0.2", 32)

            // Google DNS
            .addRoute("8.8.8.8", 32)
            .addRoute("8.8.4.4", 32)

            // Quad9
            .addRoute("9.9.9.9", 32)
            .addRoute("149.112.112.112", 32)

            // OpenDNS
            .addRoute("208.67.222.222", 32)
            .addRoute("208.67.220.220", 32)

            // AliDNS & DNSPod (Sering digunakan fallback oleh aplikasi ByteDance/TikTok/Melolo)
            .addRoute("223.5.5.5", 32)
            .addRoute("223.6.6.6", 32)
            .addRoute("119.29.29.29", 32)
            .addRoute("119.28.28.28", 32)
            .addRoute("180.76.76.76", 32)

            .setBlocking(true)

        // Konfigurasi Cakupan Aplikasi (Scope Mode)
        if (scopeMode == "MELOLO_ONLY") {
            // Mode Khusus Melolo: Batasi ke Melolo + Layanan Penyedia Iklan (GMS, WebView, Chrome)
            val packages = listOf(
                "com.worldance.drama",
                "com.google.android.gms",
                "com.google.android.webview",
                "com.android.chrome"
            )
            for (pkg in packages) {
                try {
                    builder.addAllowedApplication(pkg)
                    Log.i(TAG, "VPN dibatasi mencakup: $pkg")
                } catch (e: Exception) {
                    Log.d(TAG, "Aplikasi $pkg tidak terpasang di perangkat.")
                }
            }
        } else {
            // Mode GLOBAL (Default & Direkomendasikan): Lindungi seluruh sistem perangkat
            // Aplikasi ini sendiri dikecualikan agar permintaan AI Online tidak berputar di dalam VPN.
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.d(TAG, "Gagal mengecualikan aplikasi sendiri: ${e.message}")
            }
            Log.i(TAG, "VPN berjalan dalam Mode GLOBAL (Semua Aplikasi)")
        }

        // Pada Android 10+, kita dapat membatasi rute DNS secara sistematis
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        vpnInterface = builder.establish()
        Log.i(TAG, "Interface TUN berhasil dibentuk (Client: 10.0.0.2, DNS: 10.0.0.1, Mode: $scopeMode).")
    }

    private fun startPacketLoop() {
        vpnJob = vpnScope.launch {
            val fd = vpnInterface?.fileDescriptor
            if (fd == null) {
                Log.e(TAG, "FileDescriptor TUN null.")
                return@launch
            }

            try {
                inputStream = FileInputStream(fd)
                outputStream = FileOutputStream(fd)

                val readBuffer = ByteArray(32767)

                Log.i(TAG, "Packet reading loop dimulai...")
                while (isActive && isRunning) {
                    try {
                        val readBytes = inputStream?.read(readBuffer) ?: -1

                        if (readBytes > 0) {
                            val packetData = ByteArray(readBytes)
                            System.arraycopy(readBuffer, 0, packetData, 0, readBytes)

                            launch(Dispatchers.IO) {
                                handlePacket(packetData)
                            }
                        } else if (readBytes < 0) {
                            break
                        }
                    } catch (e: IOException) {
                        if (isRunning) {
                            Log.e(TAG, "Error saat membaca paket: ${e.message}")
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gagal menginisialisasi stream TUN: ${e.message}")
            } finally {
                closeResources()
            }
            Log.i(TAG, "Packet reading loop berakhir.")
        }
    }

    private suspend fun handlePacket(packet: ByteArray) {
        if (packet.isEmpty()) return
        when ((packet[0].toInt() and 0xF0) shr 4) {
            4 -> handleIpv4Packet(packet)
            6 -> handleIpv6Packet(packet)
        }
    }

    private suspend fun handleIpv4Packet(packet: ByteArray) {
        if (packet.size < 20) return
        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        if (packet.size < ipHeaderLength + 8) return
        if ((packet[9].toInt() and 0xFF) != 17) return // hanya UDP

        val srcIp = packet.copyOfRange(12, 16)
        val destIp = packet.copyOfRange(16, 20)
        handleUdp(packet, ipHeaderLength, srcIp, destIp, isIpv6 = false)
    }

    private suspend fun handleIpv6Packet(packet: ByteArray) {
        if (packet.size < 48) return
        if ((packet[6].toInt() and 0xFF) != 17) return // hanya UDP tanpa extension header
        val srcIp = packet.copyOfRange(8, 24)
        val destIp = packet.copyOfRange(24, 40)
        handleUdp(packet, 40, srcIp, destIp, isIpv6 = true)
    }

    private suspend fun handleUdp(
        packet: ByteArray,
        udpStart: Int,
        srcIp: ByteArray,
        destIp: ByteArray,
        isIpv6: Boolean
    ) {
        val srcPort = ((packet[udpStart].toInt() and 0xFF) shl 8) or (packet[udpStart + 1].toInt() and 0xFF)
        val destPort = ((packet[udpStart + 2].toInt() and 0xFF) shl 8) or (packet[udpStart + 3].toInt() and 0xFF)
        if (destPort != 53) return

        val dnsPayloadStart = udpStart + 8
        val dnsPayloadSize = packet.size - dnsPayloadStart
        if (dnsPayloadSize <= 12) return
        val dnsPayload = packet.copyOfRange(dnsPayloadStart, packet.size)

        // Ekstrak nama domain dan minta keputusan dari mesin filter + AI
        val (domain, qEnd) = DnsPacketParser.parseDomainName(dnsPayload, 12)
        val decision = DnsPacketParser.decide(domain)
        DnsLogManager.addLog(domain, decision.blocked, decision.verdict.shortLabel, decision.source)
        Log.d(TAG, "DNS Query: $domain (Blocked: ${decision.blocked}, ${decision.source})")

        val responsePayload: ByteArray? = if (decision.blocked) {
            DnsPacketParser.createBlockResponse(dnsPayload)
        } else {
            resolveUpstream(dnsPayload, domain, qEnd)
        }
        responsePayload ?: return

        val responsePacket = if (isIpv6) {
            IpUdpPacketBuilder.buildIpv6UdpPacket(destIp, srcIp, 53, srcPort, responsePayload)
        } else {
            IpUdpPacketBuilder.buildIpUdpPacket(destIp, srcIp, 53, srcPort, responsePayload)
        }
        writePacketToTun(responsePacket)
    }

    // --- CACHE DNS (mempercepat & mengurangi beban upstream) ---

    private class CachedResponse(val payload: ByteArray, val expiresAt: Long)

    private val dnsCache = java.util.concurrent.ConcurrentHashMap<String, CachedResponse>()
    private val cacheTtlMillis = 60_000L
    private val maxCacheEntries = 1000

    private suspend fun resolveUpstream(dnsPayload: ByteArray, domain: String, qEnd: Int): ByteArray? {
        val qType = if (qEnd + 2 <= dnsPayload.size) {
            ((dnsPayload[qEnd].toInt() and 0xFF) shl 8) or (dnsPayload[qEnd + 1].toInt() and 0xFF)
        } else -1
        val cacheKey = "${domain.lowercase()}|$qType"
        val now = System.currentTimeMillis()

        dnsCache[cacheKey]?.let { cached ->
            if (cached.expiresAt > now && cached.payload.size >= 2) {
                val copy = cached.payload.copyOf()
                copy[0] = dnsPayload[0] // salin Transaction ID dari query baru
                copy[1] = dnsPayload[1]
                return copy
            }
            dnsCache.remove(cacheKey)
        }

        val response = forwardDnsRequest(dnsPayload) ?: return null
        // Cache hanya respon sukses (RCODE 0) dan tidak terpotong (TC=0)
        if (response.size > 3 && (response[3].toInt() and 0x0F) == 0 && (response[2].toInt() and 0x02) == 0) {
            if (dnsCache.size >= maxCacheEntries) dnsCache.clear()
            dnsCache[cacheKey] = CachedResponse(response.copyOf(), now + cacheTtlMillis)
        }
        return response
    }

    /**
     * Meneruskan query DNS yang sah ke Upstream Server menggunakan DatagramSocket yang dilindungi.
     * Menggunakan mekanisme fallback jika server primer mengalami timeout.
     */
    private suspend fun forwardDnsRequest(dnsPayload: ByteArray): ByteArray? =
        withContext(Dispatchers.IO) {
            // Coba server primer
            var responsePayload = queryUpstreamDns(dnsPayload, primaryDnsIp)

            // Jika gagal/timeout, coba server sekunder
            if (responsePayload == null && primaryDnsIp != secondaryDnsIp) {
                Log.w(TAG, "Upstream primer ($primaryDnsIp) gagal, mencoba sekunder ($secondaryDnsIp)...")
                responsePayload = queryUpstreamDns(dnsPayload, secondaryDnsIp)
            }
            responsePayload
        }

    private fun queryUpstreamDns(dnsPayload: ByteArray, serverIp: String): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            if (!protect(socket)) {
                Log.e(TAG, "GAGAL melindungi socket! DNS forward dibatalkan.")
                return null
            }

            socket.soTimeout = 3000 // 3 detik timeout

            val serverAddress = InetAddress.getByName(serverIp)
            val sendPacket = DatagramPacket(dnsPayload, dnsPayload.size, serverAddress, 53)
            socket.send(sendPacket)

            val receiveBuffer = ByteArray(4096)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            socket.receive(receivePacket)

            val response = ByteArray(receivePacket.length)
            System.arraycopy(receiveBuffer, 0, response, 0, receivePacket.length)
            response
        } catch (e: Exception) {
            Log.d(TAG, "Upstream DNS Error ($serverIp): ${e.message}")
            null
        } finally {
            try { socket?.close() } catch (ignored: Exception) {}
        }
    }

    /**
     * Menulis paket IP respon kembali ke interface TUN secara thread-safe.
     */
    private fun writePacketToTun(packet: ByteArray) {
        synchronized(this) {
            try {
                outputStream?.write(packet)
                outputStream?.flush()
            } catch (e: IOException) {
                if (isRunning) {
                    Log.e(TAG, "Gagal menulis paket ke TUN: ${e.message}")
                }
            }
            Unit
        }
    }

    // --- MANAJEMEN NOTIFIKASI ANDROID ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AdBlock VPN Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifikasi status perlindungan iklan ad-blocker."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, LocalVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AdBlocker VPN Aktif")
            .setContentText("Perlindungan anti-iklan berlapis aktif.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_delete, "Matikan AdBlocker", stopPendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
