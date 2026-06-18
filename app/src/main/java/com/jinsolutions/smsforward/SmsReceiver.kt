package com.jinsolutions.smsforward

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Fires for every incoming SMS, even when the app is closed (it is a manifest-registered
 * receiver and SMS_RECEIVED is delivered to background apps by the system).
 *
 * It keeps only messages that (a) arrived on the SIM you selected and (b) look like a
 * debit/credit bank alert, then hands each one to WorkManager to POST to the gateway.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val cfg = Config.load(context)
        if (!cfg.enabled) return

        // Which SIM delivered this message?
        val subId = intent.getIntExtra("subscription",
            intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1))
        if (cfg.subId != -1 && subId != -1 && subId != cfg.subId) {
            Log.d(TAG, "Ignoring SMS from subId=$subId (watching ${cfg.subId})")
            return
        }

        // Stitch multipart SMS back into one body, grouped by sender.
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val sender = parts[0].displayOriginatingAddress ?: parts[0].originatingAddress ?: ""
        val body = parts.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
        if (body.isBlank()) return

        // Only forward debit/credit style alerts.
        val lower = body.lowercase()
        val matches = cfg.keywords.any { lower.contains(it) }
        if (!matches) {
            Log.d(TAG, "SMS does not match keywords, skipping")
            return
        }

        Log.d(TAG, "Forwarding SMS from $sender to ${cfg.groups.size} group(s)")

        // Send the message exactly as received, to each configured group.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        for (group in cfg.groups) {
            val data = workDataOf(
                SendWorker.K_URL to cfg.gatewayUrl,
                SendWorker.K_AUTH to cfg.auth,
                SendWorker.K_DEVICE to cfg.deviceId,
                SendWorker.K_PHONE to group,
                SendWorker.K_MESSAGE to body
            )
            val req = OneTimeWorkRequestBuilder<SendWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
