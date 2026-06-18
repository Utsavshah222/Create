package com.jinsolutions.smsforward

import android.content.Context

/**
 * Fixed settings are baked into the app and cannot be changed in the UI.
 * Only the keyword list, the chosen SIM, and the on/off switch are user-editable and saved
 * on this device (SharedPreferences). Nothing is stored on any server.
 *
 * The Authorization token is NOT in the source code. It is injected at build time from the
 * GitHub Actions secret GATEWAY_AUTH, so it never lives in git.
 */
data class AppConfig(
    val enabled: Boolean,
    val subId: Int,            // which SIM to watch, -1 = any
    val simLabel: String,
    val gatewayUrl: String,
    val auth: String,
    val deviceId: String,
    val groups: List<String>,
    val keywords: List<String>
)

object Config {
    private const val PREFS = "cfg"

    // ---- FIXED (not editable in the app) ----
    const val GATEWAY_URL = "https://wa.jinsolutions.in/send/message"
    const val DEVICE_ID = "savebirdskandivali"
    val GROUPS = listOf(
        "120363416877358281@g.us",
        "919664305350-1606905372@g.us"
    )
    // Token comes from the build secret (BuildConfig), never from source.
    val AUTH: String get() = BuildConfig.GATEWAY_AUTH

    // Covers common Indian bank wording for debit/credit (Kotak says "Sent"/"Received").
    val DEFAULT_KEYWORDS = listOf(
        "debited", "credited", "sent", "received", "debit", "credit", "spent", "withdrawn"
    )

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(c: Context): AppConfig {
        val p = prefs(c)
        return AppConfig(
            enabled = p.getBoolean("enabled", false),
            subId = p.getInt("subId", -1),
            simLabel = p.getString("simLabel", "") ?: "",
            gatewayUrl = GATEWAY_URL,
            auth = AUTH,
            deviceId = DEVICE_ID,
            groups = GROUPS,
            keywords = splitCsv(p.getString("keywords", DEFAULT_KEYWORDS.joinToString(",")))
        )
    }

    /** Only the editable bits are persisted. */
    fun save(c: Context, enabled: Boolean, subId: Int, simLabel: String, keywords: List<String>) {
        prefs(c).edit()
            .putBoolean("enabled", enabled)
            .putInt("subId", subId)
            .putString("simLabel", simLabel)
            .putString("keywords", keywords.joinToString(","))
            .apply()
    }

    private fun splitCsv(s: String?): List<String> =
        s.orEmpty().split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
}
