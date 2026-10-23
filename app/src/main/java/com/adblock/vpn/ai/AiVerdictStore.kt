package com.adblock.vpn.ai

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Menyimpan keputusan dari AI Online (Gemini) agar bisa dipakai ulang oleh mesin VPN
 * tanpa harus bertanya ke internet setiap kali ada query DNS.
 */
object AiVerdictStore {

    private const val PREFS = "AdBlockPrefs"
    private const val KEY = "ai_cloud_verdicts"

    data class CloudVerdict(val block: Boolean, val category: String, val reason: String)

    private val verdicts = ConcurrentHashMap<String, CloudVerdict>()

    fun get(domain: String): CloudVerdict? = verdicts[domain.lowercase().trim()]

    fun all(): Map<String, CloudVerdict> = verdicts.toMap()

    fun size(): Int = verdicts.size

    fun load(context: Context) {
        verdicts.clear()
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        try {
            val json = JSONObject(raw)
            val keys = json.keys()
            while (keys.hasNext()) {
                val domain = keys.next()
                val o = json.optJSONObject(domain) ?: continue
                verdicts[domain] = CloudVerdict(
                    o.optBoolean("block", false),
                    o.optString("category", ""),
                    o.optString("reason", "")
                )
            }
        } catch (ignored: Exception) {
        }
    }

    fun putAll(context: Context, newVerdicts: Map<String, CloudVerdict>) {
        newVerdicts.forEach { (d, v) -> verdicts[d.lowercase().trim()] = v }
        save(context)
    }

    fun clear(context: Context) {
        verdicts.clear()
        save(context)
    }

    private fun save(context: Context) {
        val json = JSONObject()
        verdicts.forEach { (d, v) ->
            json.put(d, JSONObject().apply {
                put("block", v.block)
                put("category", v.category)
                put("reason", v.reason)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, json.toString()).apply()
    }
}
