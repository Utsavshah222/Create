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
 *  - networkError=true  -> internet down / gateway down (5xx, 429, 408) / no token
 *                          -> KEEP and wait (never drop)
 *  - networkError=false -> gateway rejected the request (4xx) -> count attempts
 */
data class Outcome(val success: Boolean, val networkError: Boolean, val info: String)

object Sender {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun send(url: String, auth: String, device: String, phone: String, message: String): Outcome {
        if (auth.isBlank()) return Outcome(false, true, "no token built in (add GATEWAY_AUTH secret and rebuild)")
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
                val transient = resp.code >= 500 || resp.code == 429 || resp.code == 408
                Outcome(resp.isSuccessful, !resp.isSuccessful && transient, "HTTP ${resp.code} $body")
            }
        } catch (e: IOException) {
            // No connectivity / timeout / DNS failure -> transient, keep and wait.
            Outcome(false, true, "network: ${e.message}")
        } catch (e: Exception) {
            Outcome(false, false, "error: ${e.message}")
        }
    }
}
