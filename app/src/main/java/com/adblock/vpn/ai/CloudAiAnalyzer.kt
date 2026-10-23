package com.adblock.vpn.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI Online (opsional) memakai Google Gemini API.
 * API key gratis bisa dibuat di https://aistudio.google.com/apikey
 *
 * Hanya NAMA DOMAIN yang dikirim (tanpa data pribadi, tanpa isi trafik).
 * Hasil AI online selalu dikombinasikan dengan AI lokal: domain inti Melolo
 * tidak akan pernah diblokir walaupun AI online menyarankan blokir.
 */
object CloudAiAnalyzer {

    /** Model dicoba berurutan; alias "latest" dipakai lebih dulu agar tidak usang. */
    private val MODELS = listOf("gemini-flash-latest", "gemini-2.5-flash", "gemini-2.0-flash")
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent"
    private const val MAX_DOMAINS_PER_REQUEST = 60

    class CloudAiException(message: String) : Exception(message)

    /**
     * Menganalisis daftar domain. Wajib dipanggil dari background thread.
     * @return map domain -> keputusan.
     */
    fun analyze(apiKey: String, domains: List<String>): Map<String, AiVerdictStore.CloudVerdict> {
        if (apiKey.isBlank()) throw CloudAiException("API key Gemini belum diisi.")
        val result = LinkedHashMap<String, AiVerdictStore.CloudVerdict>()
        domains.distinct().chunked(MAX_DOMAINS_PER_REQUEST).forEach { chunk ->
            result.putAll(analyzeChunk(apiKey.trim(), chunk))
        }
        return result
    }

    private fun buildPrompt(domains: List<String>): String = """
        Kamu adalah pakar keamanan jaringan & ad-blocking Android.
        Aplikasi target: Melolo (drama pendek China, package com.worldance.drama, milik ByteDance)
        yang memakai SDK iklan Pangle, AppLovin, Moloco, InMobi, Vungle, Unity Ads, AdMob, dan AppsFlyer.

        Untuk setiap domain di bawah, tentukan apakah AMAN DIBLOKIR (iklan, pelacak, analitik, telemetri)
        atau HARUS DIIZINKAN (API inti aplikasi, video/gambar/CDN konten, login, pembayaran, layanan sistem).
        Jika ragu, pilih block=false agar aplikasi tidak rusak.

        Jawab HANYA dengan JSON array, tanpa teks lain, format:
        [{"domain":"...","block":true,"category":"iklan|pelacak|telemetri|inti|cdn|sistem|lainnya","reason":"alasan singkat bahasa Indonesia"}]

        Domain:
    """.trimIndent() + "\n" + domains.joinToString("\n")

    private fun analyzeChunk(apiKey: String, domains: List<String>): Map<String, AiVerdictStore.CloudVerdict> {
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", buildPrompt(domains))))
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0)
                put("responseMimeType", "application/json")
            })
        }.toString()

        var lastError = "Tidak ada respons"
        for (model in MODELS) {
            val (code, text) = post(String.format(ENDPOINT, model), apiKey, body)
            when {
                code == 200 -> return parseResponse(text, domains)
                code == 404 -> { lastError = "Model $model tidak tersedia"; continue }
                code == 400 || code == 401 || code == 403 ->
                    throw CloudAiException("API key ditolak (HTTP $code). Periksa kembali API key Gemini Anda.")
                code == 429 -> throw CloudAiException("Kuota Gemini habis / terlalu sering (HTTP 429). Coba lagi nanti.")
                else -> lastError = "HTTP $code"
            }
        }
        throw CloudAiException("Gagal menghubungi Gemini: $lastError")
    }

    private fun post(url: String, apiKey: String, body: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        return try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { s -> BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() } } ?: ""
            code to text
        } catch (e: Exception) {
            throw CloudAiException("Koneksi gagal: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun parseResponse(raw: String, requested: List<String>): Map<String, AiVerdictStore.CloudVerdict> {
        val text = try {
            JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        } catch (e: Exception) {
            throw CloudAiException("Format respons Gemini tidak dikenali.")
        }
        val cleaned = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start < 0 || end <= start) throw CloudAiException("AI tidak mengembalikan daftar JSON.")
        val arr = JSONArray(cleaned.substring(start, end + 1))
        val allowed = requested.map { it.lowercase() }.toSet()
        val out = LinkedHashMap<String, AiVerdictStore.CloudVerdict>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val domain = o.optString("domain").lowercase().trim()
            if (domain !in allowed) continue
            out[domain] = AiVerdictStore.CloudVerdict(
                block = o.optBoolean("block", false),
                category = o.optString("category", "lainnya"),
                reason = o.optString("reason", "")
            )
        }
        return out
    }
}
