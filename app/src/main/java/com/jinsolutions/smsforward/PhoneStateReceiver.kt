package com.jinsolutions.smsforward

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.CallLog
import android.telephony.TelephonyManager

/**
 * Fires on every call-state change. When a call ends (IDLE) we check the call log: if the
 * latest call was MISSED (not picked up) or REJECTED (you cut it) — and matches the SIM and
 * conditions you chose — we queue a WhatsApp reply to that caller's number.
 */
class PhoneStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state != TelephonyManager.EXTRA_STATE_IDLE) return  // only when a call ends

        val cfg = Config.loadCall(context)
        if (!cfg.enabled) return

        // The call-log row may be written a moment after IDLE, so wait briefly off the main thread.
        val pending = goAsync()
        Thread {
            try {
                Thread.sleep(1500)
                process(context, cfg)
            } catch (e: Exception) {
                EventLog.add(context, "Call check error: ${e.message}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun process(context: Context, cfg: CallConfig) {
        val rec = CallUtils.latestCall(context) ?: return
        val now = System.currentTimeMillis()
        if (now - rec.date > 60_000) return  // stale — not the call that just ended

        // Don't act on the same call twice.
        val dp = context.getSharedPreferences("calldedup", Context.MODE_PRIVATE)
        if (rec.date <= dp.getLong("lastDate", 0L)) return

        val isMissed = rec.type == CallLog.Calls.MISSED_TYPE
        val isRejected = rec.type == CallLog.Calls.REJECTED_TYPE
        if (!isMissed && !isRejected) return  // answered / outgoing / etc.

        val typeOk = (isMissed && cfg.onMissed) || (isRejected && cfg.onRejected)
        if (!typeOk) {
            EventLog.add(context, "Call ignored — that condition is off")
            return
        }

        if (cfg.subId != -1 && rec.subId != -1 && rec.subId != cfg.subId) {
            EventLog.add(context, "Call SKIP — different SIM (watching subId=${cfg.subId})")
            return
        }

        val phone = CallUtils.normalize(rec.number, cfg.countryCode)
        if (phone == null) {
            EventLog.add(context, "Call SKIP — private/unknown number")
            return
        }

        dp.edit().putLong("lastDate", rec.date).apply()

        val kind = if (isRejected) "rejected" else "missed"
        QueueStore.add(context, phone, cfg.message)
        MessageStore.add(context, "CALL", rec.number, cfg.message, "auto-reply queued ($kind)")
        EventLog.add(context, "MISSED CALL ${rec.number} ($kind) -> queued reply to $phone")
        ForwardService.start(context)
    }
}
