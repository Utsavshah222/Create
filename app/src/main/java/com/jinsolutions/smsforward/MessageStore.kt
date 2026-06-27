package com.jinsolutions.smsforward

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recent captured messages shown in the activity list (SMS bodies + missed-call replies),
 * each with its text and status. Newest first. Entries older than 48 hours are dropped.
 */
object MessageStore {
    private const val PREFS = "messages"
    private const val KEY = "items"
    private const val MAX = 40
    private const val MAX_AGE_MS = 48L * 60 * 60 * 1000
    private val lock = Any()

    data class Rec(val time: Long, val type: String, val from: String, val text: String, val status: String)

    fun add(c: Context, type: String, from: String, text: String, status: String) = synchronized(lock) {
        val now = System.currentTimeMillis()
        val o = JSONObject().put("t", now).put("ty", type).put("f", from).put("x", text).put("s", status)
        val old = prune(read(c))
        val n = JSONArray()
        n.put(o)                                   // newest first
        for (i in 0 until old.length()) if (i < MAX - 1) n.put(old.get(i))
        write(c, n)
    }

    fun list(c: Context): List<Rec> = synchronized(lock) {
        val arr = prune(read(c))
        val out = ArrayList<Rec>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Rec(o.optLong("t"), o.optString("ty"), o.optString("f"), o.optString("x"), o.optString("s")))
        }
        out
    }

    fun clear(c: Context) = synchronized(lock) { write(c, JSONArray()) }

    private fun prune(arr: JSONArray): JSONArray {
        val now = System.currentTimeMillis()
        val n = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (now - o.optLong("t") <= MAX_AGE_MS) n.put(o)
        }
        return n
    }

    private fun read(c: Context): JSONArray = try {
        JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
    } catch (e: Exception) { JSONArray() }

    private fun write(c: Context, arr: JSONArray) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
