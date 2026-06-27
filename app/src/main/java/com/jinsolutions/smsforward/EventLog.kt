package com.jinsolutions.smsforward

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small technical log so you can see what the app is doing (skips, sends, gateway replies).
 * Each line is stamped with epoch millis; lines older than 48 hours are pruned automatically.
 * Newest line first.
 */
object EventLog {
    private const val PREFS = "log"
    private const val KEY = "lines"
    private const val MAX = 60
    private const val MAX_AGE_MS = 48L * 60 * 60 * 1000

    fun add(c: Context, msg: String) {
        val now = System.currentTimeMillis()
        val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(now))
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val kept = pruneAndSplit(p.getString(KEY, "").orEmpty(), now)
        val all = (listOf("$now|[$ts] $msg") + kept).take(MAX)
        p.edit().putString(KEY, all.joinToString("\n")).apply()
    }

    fun get(c: Context): String {
        val now = System.currentTimeMillis()
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val display = pruneAndSplit(p.getString(KEY, "").orEmpty(), now)
            .map { it.substringAfter('|', it) }
        return if (display.isEmpty()) "(no activity yet)" else display.joinToString("\n")
    }

    fun clear(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    /** Returns stored lines (epoch|text) that are within the age limit. */
    private fun pruneAndSplit(raw: String, now: Long): List<String> =
        raw.split("\n").filter { it.isNotBlank() }.filter { line ->
            val epoch = line.substringBefore('|', "").toLongOrNull() ?: return@filter true
            now - epoch <= MAX_AGE_MS
        }
}
