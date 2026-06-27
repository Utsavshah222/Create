package com.jinsolutions.smsforward

import android.content.Context

/** Settings for the SMS-forwarding feature. */
data class SmsConfig(
    val enabled: Boolean,
    val subId: Int,
    val simLabel: String,
    val keywords: List<String>
)

/** Settings for the missed-call auto-reply feature. */
data class CallConfig(
    val enabled: Boolean,
    val subId: Int,
    val simLabel: String,
    val onMissed: Boolean,      // didn't pick up
    val onRejected: Boolean,    // cut / declined
    val message: String,
    val countryCode: String
)

/**
 * All settings live on the device only. Fixed values (gateway/token/groups) are baked in;
 * the token is injected at build time from the GATEWAY_AUTH secret and is never in source.
 */
object Config {
    private const val PREFS = "cfg"

    // ---- FIXED (shared by both features) ----
    const val GATEWAY_URL = "https://wa.jinsolutions.in/send/message"
    const val DEVICE_ID = "savebirdskandivali-admin"
    val GROUPS = listOf(
        "120363416877358281@g.us",
        "919664305350-1606905372@g.us"
    )
    val AUTH: String get() = BuildConfig.GATEWAY_AUTH

    val DEFAULT_KEYWORDS = listOf(
        "debited", "credited", "sent", "received", "debit", "credit", "spent", "withdrawn"
    )
    const val DEFAULT_CALL_MESSAGE =
        "Sorry, we missed your call. We will get back to you shortly."
    const val DEFAULT_COUNTRY_CODE = "91"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- SMS ----
    fun loadSms(c: Context) = SmsConfig(
        enabled = p(c).getBoolean("sms_enabled", false),
        subId = p(c).getInt("sms_subId", -1),
        simLabel = p(c).getString("sms_simLabel", "") ?: "",
        keywords = csv(p(c).getString("sms_keywords", DEFAULT_KEYWORDS.joinToString(",")))
    )

    fun saveSms(c: Context, cfg: SmsConfig) {
        p(c).edit()
            .putBoolean("sms_enabled", cfg.enabled)
            .putInt("sms_subId", cfg.subId)
            .putString("sms_simLabel", cfg.simLabel)
            .putString("sms_keywords", cfg.keywords.joinToString(","))
            .apply()
    }

    // ---- CALL ----
    fun loadCall(c: Context) = CallConfig(
        enabled = p(c).getBoolean("call_enabled", false),
        subId = p(c).getInt("call_subId", -1),
        simLabel = p(c).getString("call_simLabel", "") ?: "",
        onMissed = p(c).getBoolean("call_missed", true),
        onRejected = p(c).getBoolean("call_rejected", true),
        message = p(c).getString("call_message", DEFAULT_CALL_MESSAGE) ?: DEFAULT_CALL_MESSAGE,
        countryCode = p(c).getString("call_cc", DEFAULT_COUNTRY_CODE) ?: DEFAULT_COUNTRY_CODE
    )

    fun saveCall(c: Context, cfg: CallConfig) {
        p(c).edit()
            .putBoolean("call_enabled", cfg.enabled)
            .putInt("call_subId", cfg.subId)
            .putString("call_simLabel", cfg.simLabel)
            .putBoolean("call_missed", cfg.onMissed)
            .putBoolean("call_rejected", cfg.onRejected)
            .putString("call_message", cfg.message)
            .putString("call_cc", cfg.countryCode)
            .apply()
    }

    fun anyEnabled(c: Context) = loadSms(c).enabled || loadCall(c).enabled

    private fun csv(s: String?): List<String> =
        s.orEmpty().split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
}
