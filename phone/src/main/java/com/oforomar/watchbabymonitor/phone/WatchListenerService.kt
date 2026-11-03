package com.oforomar.watchbabymonitor.phone

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.oforomar.watchbabymonitor.shared.Paths
import com.oforomar.watchbabymonitor.shared.Payload

/** Wakes the phone app for every message from the watch, even when the app is closed. */
class WatchListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        val payload = Payload.fromBytes(event.data)
        val now = System.currentTimeMillis()
        Log.i(TAG, "${event.path} $payload")

        fun seen(s: WatchStatus) = s.copy(
            lastHeartbeatMs = now,
            batteryPct = payload.batteryPct ?: s.batteryPct,
            charging = payload.charging ?: s.charging,
        )

        when (event.path) {
            Paths.MONITOR_START -> {
                PhoneState.update(this) { seen(it).copy(monitoring = true, crying = false, lastFault = "") }
                Watchdog.arm(this)
            }

            Paths.MONITOR_STOP -> {
                PhoneState.update(this) { seen(it).copy(monitoring = false, crying = false) }
                Watchdog.disarm(this)
            }

            Paths.HEARTBEAT -> {
                PhoneState.update(this) { seen(it).copy(monitoring = true) }
                Watchdog.arm(this)
            }

            Paths.CRY_START -> {
                if (payload.test) {
                    AlarmController.raise(this, AlarmReason.TEST)
                } else {
                    PhoneState.update(this) { seen(it).copy(monitoring = true, crying = true, lastCryMs = now) }
                    Watchdog.arm(this)
                    AlarmController.raise(this, AlarmReason.CRY)
                }
            }

            Paths.CRY_STOP -> {
                // The alarm keeps ringing until someone acknowledges it.
                PhoneState.update(this) { seen(it).copy(crying = false) }
                Watchdog.arm(this)
            }

            Paths.FAULT -> {
                val reason = payload.reason ?: "unknown"
                PhoneState.update(this) { seen(it).copy(lastFault = reason) }
                AlarmController.raise(this, AlarmReason.FAULT, reason.replace('_', ' '))
            }
        }
    }

    companion object {
        private const val TAG = "WatchListener"
    }
}
