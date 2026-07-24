package com.jinsolutions.smsforward

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent FIFO queue of outgoing SMS (missed-call replies sent from the SIM).
 * Separate from the WhatsApp queue so it never disturbs the WhatsApp send pacing.
 * Survives reboots; drained by the foreground service with retry.
 */
object SmsQueueStore {
    private const val PREFS = "sms_out_queue"
    private const val KEY = "items"
    private val lock = Any()

    data class Item(val number: String, val message: String, val subId: Int, val attempts: Int)

    fun add(c: Context, number: String, message: String, subId: Int) = synchronized(lock) {
        val arr = read(c)
        arr.put(JSONObject().put("n", number).put("m", message).put("s", subId).put("a", 0))
        write(c, arr)
    }

    fun peek(c: Context): Item? = synchronized(lock) {
        val arr = read(c)
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        Item(o.getString("n"), o.getString("m"), o.optInt("s", -1), o.optInt("a", 0))
    }

    fun removeHead(c: Context) = synchronized(lock) {
        val arr = read(c)
        if (arr.length() > 0) {
            val n = JSONArray()
            for (i in 1 until arr.length()) n.put(arr.get(i))
            write(c, n)
        }
    }

    fun bumpHeadAttempts(c: Context) = synchronized(lock) {
        val arr = read(c)
        if (arr.length() > 0) {
            val o = arr.getJSONObject(0)
            o.put("a", o.optInt("a", 0) + 1)
            write(c, arr)
        }
    }

    fun size(c: Context): Int = synchronized(lock) { read(c).length() }

    private fun read(c: Context): JSONArray = try {
        JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
    } catch (e: Exception) { JSONArray() }

    private fun write(c: Context, arr: JSONArray) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
