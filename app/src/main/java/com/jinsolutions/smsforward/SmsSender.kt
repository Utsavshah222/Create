package com.jinsolutions.smsforward

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** Sends a real SMS from the phone's SIM using Android's SmsManager (free — uses the SIM plan). */
object SmsSender {

    fun hasPermission(c: Context): Boolean =
        ContextCompat.checkSelfPermission(c, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /**
     * Sends [message] to [number] from the SIM [subId] (-1 = default SIM). Returns:
     *  - true  = delivered to the network (or timed out after handing off — assume sent)
     *  - false = the SIM explicitly failed (no service / radio off) -> caller should retry
     */
    suspend fun send(context: Context, number: String, message: String, subId: Int): Boolean {
        if (!hasPermission(context)) return false
        val sm = smsManagerFor(context, subId)
        val parts = sm.divideMessage(message)
        val action = "com.jinsolutions.smsforward.SMS_SENT." + System.nanoTime()

        val result = withTimeoutOrNull(45_000L) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val remaining = AtomicInteger(parts.size)
                val failed = AtomicBoolean(false)
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        if (resultCode != Activity.RESULT_OK) failed.set(true)
                        if (remaining.decrementAndGet() <= 0) {
                            try { context.unregisterReceiver(this) } catch (_: Exception) {}
                            if (cont.isActive) cont.resume(!failed.get())
                        }
                    }
                }
                val filter = IntentFilter(action)
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter)
                }
                cont.invokeOnCancellation { try { context.unregisterReceiver(receiver) } catch (_: Exception) {} }

                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
                try {
                    if (parts.size <= 1) {
                        val pi = PendingIntent.getBroadcast(
                            context, 0, Intent(action).setPackage(context.packageName), flags)
                        sm.sendTextMessage(number, null, message, pi, null)
                    } else {
                        val sent = ArrayList<PendingIntent>()
                        for (idx in parts.indices) {
                            sent.add(PendingIntent.getBroadcast(
                                context, idx, Intent(action).setPackage(context.packageName), flags))
                        }
                        sm.sendMultipartTextMessage(number, null, parts, sent, null)
                    }
                } catch (e: Exception) {
                    try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(false)
                }
            }
        }
        // Timeout (null) -> the message was handed to the framework but no ack came back.
        // Treat as sent so we do NOT retry and risk a duplicate SMS to the caller.
        return result ?: true
    }

    @Suppress("DEPRECATION")
    private fun smsManagerFor(context: Context, subId: Int): SmsManager {
        if (Build.VERSION.SDK_INT >= 31) {
            val base = context.getSystemService(SmsManager::class.java)
            return if (subId != -1) base.createForSubscriptionId(subId) else base
        }
        return if (subId != -1) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
    }
}
