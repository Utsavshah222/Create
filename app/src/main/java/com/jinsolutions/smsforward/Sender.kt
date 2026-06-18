package com.jinsolutions.smsforward

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Performs one HTTP POST to the WhatsApp gateway.
 * Body:  {"phone":"<group-jid>","message":"<text>"}
 *
 * Returns an Outcome so the caller can tell apart:
 *  - success            -> remove from queue
 *  - networkError=true  -> internet down / unreachable -> KEEP and wait (never drop)
 *  - networkError=false -> gateway answered with an error -> count attempts
 */
data class Outcome(val success: Boolean, val networkError: Boolean, val info: String)

object Sender {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun send(url: String, auth: String, device: String, phone: String, message: String): Outcome {
        if (auth.isBlank()) return Outcome(false, false, "no token built in")
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
                Outcome(resp.isSuccessful, false, "HTTP ${resp.code} $body")
            }
        } catch (e: IOException) {
            // No connectivity / timeout / DNS failure -> transient, keep and wait.
            Outcome(false, true, "network: ${e.message}")
        } catch (e: Exception) {
            Outcome(false, true, "error: ${e.message}")
        }
    }
}
