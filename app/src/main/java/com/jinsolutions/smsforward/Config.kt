package com.jinsolutions.smsforward

import android.content.Context

/**
 * All settings live ONLY on the device, in SharedPreferences. Nothing is stored on any server.
 * The setup screen (MainActivity) writes this; the background SMS receiver reads it.
 */
data class AppConfig(
    val enabled: Boolean,
    val subId: Int,            // which SIM to watch (subscription id), -1 = any SIM
    val simLabel: String,      // human label of the chosen SIM (for the UI only)
    val gatewayUrl: String,    // WhatsApp gateway endpoint
    val auth: String,          // Authorization header value (Basic ...)
    val deviceId: String,      // x-device-id header value
    val groups: List<String>,  // destination chat ids (group JIDs)
    val keywords: List<String> // body must contain one of these to be forwarded
)

object Config {
    private const val PREFS = "cfg"

    // ---- Defaults, pre-filled from the values you provided ----
    const val DEFAULT_URL = "https://wa.jinsolutions.in/send/message"
    // SECRET — intentionally left blank so it is never committed to git.
    // Paste your "Basic ..." Authorization value once in the app's setup screen; it is
    // then stored only on this device (SharedPreferences).
    const val DEFAULT_AUTH = ""
    const val DEFAULT_DEVICE_ID = "savebirdskandivali"
    val DEFAULT_GROUPS = listOf(
        "120363416877358281@g.us",
        "919664305350-1606905372@g.us"
    )
    val DEFAULT_KEYWORDS = listOf("debited", "credited")

    private fun prefs(c: Context) =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(c: Context): AppConfig {
        val p = prefs(c)
        return AppConfig(
            enabled = p.getBoolean("enabled", false),
            subId = p.getInt("subId", -1),
            simLabel = p.getString("simLabel", "") ?: "",
            gatewayUrl = p.getString("url", DEFAULT_URL) ?: DEFAULT_URL,
            auth = p.getString("auth", DEFAULT_AUTH) ?: DEFAULT_AUTH,
            deviceId = p.getString("deviceId", DEFAULT_DEVICE_ID) ?: DEFAULT_DEVICE_ID,
            groups = splitLines(p.getString("groups", DEFAULT_GROUPS.joinToString("\n"))),
            keywords = splitCsv(p.getString("keywords", DEFAULT_KEYWORDS.joinToString(",")))
        )
    }

    fun save(c: Context, cfg: AppConfig) {
        prefs(c).edit()
            .putBoolean("enabled", cfg.enabled)
            .putInt("subId", cfg.subId)
            .putString("simLabel", cfg.simLabel)
            .putString("url", cfg.gatewayUrl.trim())
            .putString("auth", cfg.auth.trim())
            .putString("deviceId", cfg.deviceId.trim())
            .putString("groups", cfg.groups.joinToString("\n"))
            .putString("keywords", cfg.keywords.joinToString(","))
            .apply()
    }

    private fun splitLines(s: String?): List<String> =
        s.orEmpty().split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    private fun splitCsv(s: String?): List<String> =
        s.orEmpty().split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
}
