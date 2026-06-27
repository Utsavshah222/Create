package com.jinsolutions.smsforward

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A persistent FIFO queue of messages waiting to be sent to WhatsApp.
 * Stored on disk (SharedPreferences) so nothing is lost if the app/phone restarts.
 * The foreground service drains it one message every 2 seconds, so a burst of 100 SMS
 * never floods WhatsApp (which would get the number banned).
 */
object QueueStore {
    private const val PREFS = "queue"
    private const val KEY = "items"
    private val lock = Any()

    data class Item(val phone: String, val message: String, val device: String, val attempts: Int)

    fun add(c: Context, phone: String, message: String, device: String) = synchronized(lock) {
        val arr = read(c)
        arr.put(JSONObject().put("p", phone).put("m", message).put("d", device).put("a", 0))
        write(c, arr)
    }

    fun peek(c: Context): Item? = synchronized(lock) {
        val arr = read(c)
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        Item(o.getString("p"), o.getString("m"), o.optString("d", ""), o.optInt("a", 0))
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

    private fun read(c: Context): JSONArray {
        val s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")
        return try { JSONArray(s) } catch (e: Exception) { JSONArray() }
    }

    private fun write(c: Context, arr: JSONArray) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
