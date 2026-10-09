package com.jinsolutions.smsforward

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the always-on service after a reboot or an app update, if any feature is enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Config.anyEnabled(context)) {
            EventLog.add(context, "Boot detected — restarting service")
            ForwardService.start(context)
        }
    }
}
