package com.jinsolutions.smsforward

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the always-on service after the phone reboots, if forwarding is enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Config.load(context).enabled) {
            EventLog.add(context, "Boot detected — restarting service")
            ForwardService.start(context)
        }
    }
}
