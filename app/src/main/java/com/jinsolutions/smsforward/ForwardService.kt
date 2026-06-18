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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Always-on foreground service. Two jobs:
 *  1) Stay alive so the system reliably delivers incoming SMS to SmsReceiver.
 *  2) Drain the message queue ONE AT A TIME with a 2-second gap, so a burst of messages
 *     never floods WhatsApp.
 */
class ForwardService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        startQueueProcessor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        val channelId = "forward"
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(channelId, "SMS forwarding", NotificationManager.IMPORTANCE_MIN)
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

    private fun startQueueProcessor() {
        scope.launch {
            val ctx = applicationContext
            while (isActive) {
                val item = QueueStore.peek(ctx)
                if (item == null) {
                    delay(1500)        // queue empty — check again shortly
                    continue
                }
                val (ok, info) = Sender.send(
                    Config.GATEWAY_URL, Config.AUTH, Config.DEVICE_ID, item.phone, item.message
                )
                if (ok) {
                    QueueStore.removeHead(ctx)
                    EventLog.add(ctx, "SENT ${item.phone} — ${QueueStore.size(ctx)} left in queue")
                    delay(2000)        // <-- 2-second gap between messages (anti-ban)
                } else if (item.attempts >= 5) {
                    QueueStore.removeHead(ctx)
                    EventLog.add(ctx, "DROPPED ${item.phone} after retries: $info")
                } else {
                    QueueStore.bumpHeadAttempts(ctx)
                    EventLog.add(ctx, "RETRY ${item.phone}: $info")
                    delay(5000)        // back off, then try the same message again
                }
            }
        }
    }

    companion object {
        fun start(c: Context) {
            try {
                val i = Intent(c, ForwardService::class.java)
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (e: Exception) {
                // e.g. background-start restriction on some OS versions; the message is already
                // safely in the queue and will be drained when the service next runs.
                EventLog.add(c, "Service start deferred: ${e.message}")
            }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, ForwardService::class.java))
        }
    }
}
