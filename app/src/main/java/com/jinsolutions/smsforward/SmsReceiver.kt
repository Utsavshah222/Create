package com.jinsolutions.smsforward

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Fires for every incoming SMS, even when the app is closed (manifest-registered receiver;
 * SMS_RECEIVED is delivered to background apps by the system).
 *
 * Keeps only messages that (a) arrived on the SIM you selected and (b) contain one of your
 * keywords, then hands each one to WorkManager to POST to the gateway.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val cfg = Config.load(context)
        if (!cfg.enabled) {
            EventLog.add(context, "SMS arrived but app is DISABLED — turn Enabled on + Save")
            return
        }

        val subId = intent.getIntExtra("subscription",
            intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1))

        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val sender = parts[0].displayOriginatingAddress ?: parts[0].originatingAddress ?: "?"
        val body = parts.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
        if (body.isBlank()) return

        EventLog.add(context, "SMS from $sender (subId=$subId): ${body.take(60)}")

        if (cfg.subId != -1 && subId != -1 && subId != cfg.subId) {
            EventLog.add(context, "SKIP — different SIM (watching subId=${cfg.subId})")
            return
        }

        val lower = body.lowercase()
        val matched = cfg.keywords.any { lower.contains(it) }
        if (!matched) {
            EventLog.add(context, "SKIP — no keyword match (looking for: ${cfg.keywords.joinToString(",")})")
            return
        }

        if (cfg.groups.isEmpty()) {
            EventLog.add(context, "MATCH but no groups configured")
            return
        }

        EventLog.add(context, "MATCH — queueing send to ${cfg.groups.size} group(s)")

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
}
