package com.jinsolutions.smsforward

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Posts one message to the WhatsApp gateway, with retry/backoff handled by WorkManager.
 * Logs the HTTP status and the gateway's reply so failures are visible on the main screen.
 *
 * Body:  {"phone":"<group-jid>","message":"<text>"}
 */
class SendWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val url = inputData.getString(K_URL) ?: return@withContext Result.failure()
        val auth = inputData.getString(K_AUTH).orEmpty()
        val deviceId = inputData.getString(K_DEVICE).orEmpty()
        val phone = inputData.getString(K_PHONE) ?: return@withContext Result.failure()
        val message = inputData.getString(K_MESSAGE) ?: return@withContext Result.failure()

        if (auth.isBlank()) {
            EventLog.add(ctx, "SEND $phone — NO Authorization token set (paste it + Save)")
            return@withContext Result.failure()
        }

        val json = JSONObject().put("phone", phone).put("message", message).toString()

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .addHeader("Authorization", auth)
            .addHeader("x-device-id", deviceId)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { resp ->
                val replyBody = resp.body?.string()?.take(250).orEmpty()
                val tag = if (resp.isSuccessful) "OK" else "FAIL"
                EventLog.add(ctx, "SEND $phone -> HTTP ${resp.code} $tag: $replyBody")
                if (resp.isSuccessful) {
                    Result.success()
                } else if (resp.code in 500..599 && runAttemptCount < 5) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            EventLog.add(ctx, "SEND $phone -> ERROR ${e.message}")
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val K_URL = "url"
        const val K_AUTH = "auth"
        const val K_DEVICE = "device"
        const val K_PHONE = "phone"
        const val K_MESSAGE = "message"

        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
