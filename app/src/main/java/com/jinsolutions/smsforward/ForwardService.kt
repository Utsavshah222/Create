package com.jinsolutions.smsforward

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * A tiny always-on foreground service. It does no work itself — its only job is to keep the
 * app process alive and "active" so the system reliably delivers SMS_RECEIVED to SmsReceiver,
 * even when the app is not on screen. This is what stops aggressive phones from killing it.
 */
class ForwardService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channelId = "forward"
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                channelId, "SMS forwarding",
                NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("SMS → WhatsApp is active")
            .setContentText("Watching for bank debit/credit SMS")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If the system kills us, restart as soon as possible.
        return START_STICKY
    }

    companion object {
        fun start(c: Context) {
            val i = Intent(c, ForwardService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, ForwardService::class.java))
        }
    }
}
