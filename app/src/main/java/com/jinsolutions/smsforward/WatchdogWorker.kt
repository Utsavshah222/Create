package com.jinsolutions.smsforward

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Runs periodically (every ~15 min, the OS minimum) and re-starts the always-on service if
 * the phone killed it. A safety net on top of START_STICKY and the boot restart.
 */
class WatchdogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        try {
            if (Config.anyEnabled(applicationContext)) {
                ForwardService.start(applicationContext)
            }
        } catch (e: Exception) {
            EventLog.add(applicationContext, "Watchdog could not start service: ${e.message}")
        }
        return Result.success()
    }
}
