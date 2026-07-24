package com.jinsolutions.smsforward

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CallLog
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.TimeZone

/**
 * Handles two things off incoming calls:
 *  1) Working hours: outside the hours you set (Indian time), auto-reject the call.
 *  2) Reply: when a call was auto-rejected, missed, or rejected (per your settings), queue a
 *     WhatsApp reply (and an SMS from the SIM) to the caller.
 * Both are additive — if a feature is off, its part does nothing.
 */
class PhoneStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> handleRinging(context, intent)
            TelephonyManager.EXTRA_STATE_IDLE -> handleIdle(context)
        }
    }

    // --- 1) Auto-reject outside working hours ---
    private fun handleRinging(context: Context, intent: Intent) {
        val wh = Config.loadWorkingHours(context)
        if (!wh.enabled) return
        if (wh.isWithin(currentIstMinute())) return  // inside hours — leave the call alone

        val number = try {
            intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        } catch (e: Exception) { null }

        val rejected = rejectCall(context)
        context.getSharedPreferences("autoreject", Context.MODE_PRIVATE).edit()
            .putLong("time", System.currentTimeMillis())
            .putString("number", number ?: "")
            .apply()
        EventLog.add(context, "Outside working hours — auto-reject ${if (rejected) "done" else "attempted"} ${number ?: ""}")
    }

    // --- 2) Reply after the call ends ---
    private fun handleIdle(context: Context) {
        val call = Config.loadCall(context)
        val wh = Config.loadWorkingHours(context)
        if (!call.enabled && !wh.enabled) return

        val pending = goAsync()
        Thread {
            try {
                Thread.sleep(1500)   // let the call-log row get written
                process(context, call, wh)
            } catch (e: Exception) {
                EventLog.add(context, "Call check error: ${e.message}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun process(context: Context, call: CallConfig, wh: WorkingHours) {
        val rec = CallUtils.latestCall(context) ?: return
        val now = System.currentTimeMillis()
        if (now - rec.date > 60_000) return

        val dp = context.getSharedPreferences("calldedup", Context.MODE_PRIVATE)
        if (rec.date <= dp.getLong("lastDate", 0L)) return

        val ar = context.getSharedPreferences("autoreject", Context.MODE_PRIVATE)
        val wasAutoRejected = wh.enabled && (now - ar.getLong("time", 0L) < 90_000)
        if (wasAutoRejected) ar.edit().remove("time").remove("number").apply()

        val isMissed = rec.type == CallLog.Calls.MISSED_TYPE
        val isRejected = rec.type == CallLog.Calls.REJECTED_TYPE

        val shouldReply: Boolean
        val applySimFilter: Boolean
        val reason: String
        when {
            wasAutoRejected -> { shouldReply = true; applySimFilter = false; reason = "auto-rejected, outside hours" }
            call.enabled && ((isMissed && call.onMissed) || (isRejected && call.onRejected)) -> {
                shouldReply = true; applySimFilter = true; reason = if (isRejected) "rejected" else "missed"
            }
            else -> return
        }

        if (applySimFilter && call.subId != -1 && rec.subId != -1 && rec.subId != call.subId) {
            EventLog.add(context, "Call SKIP — different SIM (watching subId=${call.subId})")
            return
        }

        val phone = CallUtils.normalize(rec.number, call.countryCode)
        if (phone == null) {
            EventLog.add(context, "Call SKIP — private/unknown number")
            return
        }

        dp.edit().putLong("lastDate", rec.date).apply()

        QueueStore.add(context, phone, call.message, Config.getCallDeviceId(context))
        MessageStore.add(context, "CALL", rec.number, call.message, "WhatsApp reply queued ($reason)")
        EventLog.add(context, "CALL ${rec.number} ($reason) -> queued WhatsApp reply to $phone")

        // SMS: auto-rejected calls always get one; normal missed/rejected respect the checkbox.
        if (wasAutoRejected || call.sendSms) {
            SmsQueueStore.add(context, rec.number, call.message, call.subId)
            EventLog.add(context, "CALL ${rec.number} -> queued SMS from SIM")
        }

        ForwardService.start(context)
    }

    @Suppress("DEPRECATION")
    private fun rejectCall(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
                    != PackageManager.PERMISSION_GRANTED) {
                    EventLog.add(context, "Cannot reject — grant the Phone (answer calls) permission")
                    return false
                }
                context.getSystemService(TelecomManager::class.java)?.endCall() ?: false
            } else {
                endCallReflection(context)
            }
        } catch (e: Exception) {
            EventLog.add(context, "Reject error: ${e.message}")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun endCallReflection(context: Context): Boolean {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val m = tm.javaClass.getDeclaredMethod("getITelephony")
            m.isAccessible = true
            val telephony = m.invoke(tm)
            telephony.javaClass.getDeclaredMethod("endCall").invoke(telephony)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun currentIstMinute(): Int {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }
}
