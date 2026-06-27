package com.jinsolutions.smsforward

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

object CallUtils {

    data class CallRec(val number: String, val type: Int, val subId: Int, val date: Long)

    /** Reads the most recent call from the call log. Needs READ_CALL_LOG. */
    fun latestCall(c: Context): CallRec? {
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) return null

        val cols = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.PHONE_ACCOUNT_ID
        )
        c.contentResolver.query(
            CallLog.Calls.CONTENT_URI, cols, null, null, CallLog.Calls.DATE + " DESC"
        )?.use { cur ->
            if (cur.moveToFirst()) {
                val number = cur.getString(0) ?: ""
                val type = cur.getInt(1)
                val date = cur.getLong(2)
                val acct = cur.getString(3)
                return CallRec(number, type, mapAccountToSub(c, acct), date)
            }
        }
        return null
    }

    /** Best-effort: maps a call-log PHONE_ACCOUNT_ID to a subscription id. -1 if unknown. */
    private fun mapAccountToSub(c: Context, accountId: String?): Int {
        if (accountId.isNullOrBlank()) return -1
        return try {
            if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) return -1
            val sm = c.getSystemService(SubscriptionManager::class.java) ?: return -1
            val subs = sm.activeSubscriptionInfoList ?: return -1
            for (info in subs) {
                if (accountId == info.subscriptionId.toString() || accountId == info.iccId) {
                    return info.subscriptionId
                }
            }
            // Some OEMs store the slot index as the account id.
            accountId.toIntOrNull()?.let { slot ->
                for (info in subs) if (info.simSlotIndex == slot) return info.subscriptionId
            }
            -1
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Normalizes a number to international digits (no '+'), assuming [countryCode] for a
     * local 10-digit number. Returns null for blank/private/unknown callers.
     */
    fun normalize(raw: String?, countryCode: String): String? {
        if (raw.isNullOrBlank()) return null
        var d = raw.filter { it.isDigit() }
        if (d.isEmpty()) return null
        if (d.startsWith("00")) d = d.substring(2)
        when {
            d.length == 10 -> d = countryCode + d
            d.length == 11 && d.startsWith("0") -> d = countryCode + d.substring(1)
        }
        return d
    }
}
