package com.jinsolutions.smsforward

import android.content.Context
import android.util.Log
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
 * Posts one message to the WhatsApp gateway. Runs in the background with automatic
 * retry/backoff (handled by WorkManager) so a brief loss of network does not drop a message.
 *
 * Body shape (go-whatsapp-web-multidevice style):  {"phone":"<group-jid>","message":"<text>"}
 */
class SendWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(K_URL) ?: return@withContext Result.failure()
        val auth = inputData.getString(K_AUTH).orEmpty()
        val deviceId = inputData.getString(K_DEVICE).orEmpty()
        val phone = inputData.getString(K_PHONE) ?: return@withContext Result.failure()
        val message = inputData.getString(K_MESSAGE) ?: return@withContext Result.failure()

        val json = JSONObject()
            .put("phone", phone)
            .put("message", message)
            .toString()

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
                if (resp.isSuccessful) {
                    Log.d(TAG, "Sent to $phone (HTTP ${resp.code})")
                    Result.success()
                } else {
                    Log.w(TAG, "Gateway returned HTTP ${resp.code} for $phone")
                    // Retry a few times on server-side hiccups, then give up.
                    if (runAttemptCount < 5) Result.retry() else Result.failure()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Send failed for $phone: ${e.message}")
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "SendWorker"
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
