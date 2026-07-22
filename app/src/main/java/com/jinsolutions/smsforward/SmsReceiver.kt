package com.jinsolutions.smsforward

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Fires for every incoming SMS, even when the app is closed (manifest-registered receiver;
 * SMS_RECEIVED is delivered to background apps by the system).
 *
 * It keeps only messages that (a) arrived on the SIM you selected and (b) contain one of your
 * keywords, then ADDS each one to the queue. The foreground service sends them out, one every
 * 2 seconds.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val cfg = Config.loadSms(context)
        if (!cfg.enabled) {
            return  // SMS forwarding is off
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

        // Guard against the same SMS being processed twice in a short window.
        val sig = (sender + "|" + body).hashCode()
        val dp = context.getSharedPreferences("dedup", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (sig == dp.getInt("sig", 0) && now - dp.getLong("time", 0L) < 15_000) {
            EventLog.add(context, "SKIP — duplicate within 15s")
            return
        }
        dp.edit().putInt("sig", sig).putLong("time", now).apply()

        // Add one queued message per group; the service sends them 2 seconds apart.
        val deviceId = Config.getSmsDeviceId(context)
        for (group in Config.GROUPS) {
            QueueStore.add(context, group, body, deviceId)
        }
        MessageStore.add(context, "SMS", sender, body, "auto-forwarded to ${Config.GROUPS.size} group(s)")
        EventLog.add(context, "QUEUED ${Config.GROUPS.size} message(s) — total waiting: ${QueueStore.size(context)}")

        // Make sure the sender service is running so the queue gets drained.
        ForwardService.start(context)
    }
}
