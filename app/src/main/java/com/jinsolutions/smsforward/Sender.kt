package com.jinsolutions.smsforward

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Performs one HTTP POST to the WhatsApp gateway.
 * Body:  {"phone":"<group-jid>","message":"<text>"}
 * Returns (success, humanReadableInfo).
 */
object Sender {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun send(url: String, auth: String, device: String, phone: String, message: String): Pair<Boolean, String> {
        if (auth.isBlank()) return false to "no token built in"
        return try {
            val json = JSONObject().put("phone", phone).put("message", message).toString()
            val req = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .addHeader("Authorization", auth)
                .addHeader("x-device-id", device)
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()?.take(180).orEmpty()
                resp.isSuccessful to "HTTP ${resp.code} $body"
            }
        } catch (e: Exception) {
            false to "ERROR ${e.message}"
        }
    }
}
