package com.adblock.vpn

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

object DnsLogManager {
    private const val TAG = "DnsLogManager"
    private const val MAX_LOGS = 300

    private val blockedCount = AtomicInteger(0)
    private val allowedCount = AtomicInteger(0)

    class LogEntry(
        val timestamp: String,
        val domain: String,
        val isBlocked: Boolean,
        val aiLabel: String = "",
        val source: String = ""
    ) {
        override fun toString(): String {
            val status = if (isBlocked) "[BLOCKED]" else "[ALLOWED]"
            val extra = if (aiLabel.isNotEmpty()) " ($aiLabel, $source)" else ""
            return "$timestamp $status $domain$extra"
        }
    }

    private val logs = ConcurrentLinkedQueue<LogEntry>()
    private var logFile: File? = null

    /**
     * Inisialisasi file log eksternal untuk backup
     */
    fun init(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null)
            if (dir != null) {
                logFile = File(dir, "dns_logs.txt")
                // Reset file setiap kali VPN dijalankan ulang agar tidak membengkak
                if (logFile?.exists() == true) {
                    logFile?.delete()
                }
                logFile?.createNewFile()
                Log.d(TAG, "Log file diinisialisasi di: ${logFile?.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menginisialisasi file log: ${e.message}")
        }
    }

    fun addLog(domain: String, isBlocked: Boolean, aiLabel: String = "", source: String = "") {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val time = sdf.format(Date())
        val entry = LogEntry(time, domain, isBlocked, aiLabel, source)

        if (isBlocked) {
            blockedCount.incrementAndGet()
        } else {
            allowedCount.incrementAndGet()
        }

        logs.add(entry)

        while (logs.size > MAX_LOGS) {
            logs.poll()
        }

        // Tulis ke file log eksternal
        writeEntryToFile(entry)
    }

    @Synchronized
    private fun writeEntryToFile(entry: LogEntry) {
        val file = logFile ?: return
        try {
            FileWriter(file, true).use { writer ->
                writer.append(entry.toString()).append("\n")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Gagal menulis ke file log: ${e.message}")
        }
    }

    fun getLogs(): List<LogEntry> {
        return logs.toList()
    }

    fun getStats(): Pair<Int, Int> {
        return Pair(blockedCount.get(), allowedCount.get())
    }

    fun clearLogs() {
        logs.clear()
        blockedCount.set(0)
        allowedCount.set(0)
        try {
            logFile?.let {
                if (it.exists()) {
                    it.delete()
                }
                it.createNewFile()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menghapus file log: ${e.message}")
        }
    }
}
