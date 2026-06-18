package com.jinsolutions.smsforward

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A tiny on-device log so you can SEE what the app is doing (SMS received, matched,
 * sent, failed + the gateway's reply). Shown on the main screen. Newest line first.
 */
object EventLog {
    private const val PREFS = "log"
    private const val KEY = "lines"
    private const val MAX = 50

    fun add(c: Context, msg: String) {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = p.getString(KEY, "").orEmpty()
        val lines = (listOf("[$ts] $msg") + existing.split("\n"))
            .filter { it.isNotBlank() }
            .take(MAX)
        p.edit().putString(KEY, lines.joinToString("\n")).apply()
    }

    fun get(c: Context): String =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
            .ifBlank { "(no activity yet)" }

    fun clear(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
