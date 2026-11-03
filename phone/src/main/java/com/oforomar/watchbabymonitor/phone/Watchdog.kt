package com.oforomar.watchbabymonitor.phone

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.oforomar.watchbabymonitor.shared.Timing

/**
 * Fires "Monitor lost" if heartbeats stop. Every heartbeat pushes the deadline back,
 * so the alarm only ever goes off when the watch has gone quiet.
 */
object Watchdog {

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, WatchdogReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun arm(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + Timing.WATCHDOG_TIMEOUT_MS
        val pi = pendingIntent(context)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exactAllowed) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }

    fun disarm(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val s = PhoneState.load(context)
        if (!s.monitoring) return
        val silentFor = System.currentTimeMillis() - s.lastHeartbeatMs
        if (silentFor >= Timing.WATCHDOG_TIMEOUT_MS - 5_000) {
            AlarmController.raise(context, AlarmReason.LOST)
            // Keep checking: if heartbeats come back the next one re-arms normally.
            Watchdog.arm(context)
        } else {
            Watchdog.arm(context)
        }
    }
}
