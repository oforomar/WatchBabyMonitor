package com.oforomar.watchbabymonitor.phone

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the phone knows about the watch. Persisted, because the listener service and
 * the watchdog receiver run in short-lived processes the UI may never see.
 */
data class WatchStatus(
    val monitoring: Boolean = false,
    val lastHeartbeatMs: Long = 0L,
    val batteryPct: Int = -1,
    val charging: Boolean = false,
    val crying: Boolean = false,
    val lastCryMs: Long = 0L,
    val alarmActive: Boolean = false,
    val alarmTitle: String = "",
    val lastFault: String = "",
)

object PhoneState {
    private const val PREFS = "watch_status"

    private val _status = MutableStateFlow(WatchStatus())
    val status: StateFlow<WatchStatus> = _status.asStateFlow()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): WatchStatus {
        val p = prefs(context)
        val s = WatchStatus(
            monitoring = p.getBoolean("monitoring", false),
            lastHeartbeatMs = p.getLong("lastHeartbeatMs", 0L),
            batteryPct = p.getInt("batteryPct", -1),
            charging = p.getBoolean("charging", false),
            crying = p.getBoolean("crying", false),
            lastCryMs = p.getLong("lastCryMs", 0L),
            alarmActive = p.getBoolean("alarmActive", false),
            alarmTitle = p.getString("alarmTitle", "") ?: "",
            lastFault = p.getString("lastFault", "") ?: "",
        )
        _status.value = s
        return s
    }

    @Synchronized
    fun update(context: Context, transform: (WatchStatus) -> WatchStatus): WatchStatus {
        val s = transform(load(context))
        prefs(context).edit()
            .putBoolean("monitoring", s.monitoring)
            .putLong("lastHeartbeatMs", s.lastHeartbeatMs)
            .putInt("batteryPct", s.batteryPct)
            .putBoolean("charging", s.charging)
            .putBoolean("crying", s.crying)
            .putLong("lastCryMs", s.lastCryMs)
            .putBoolean("alarmActive", s.alarmActive)
            .putString("alarmTitle", s.alarmTitle)
            .putString("lastFault", s.lastFault)
            .commit()
        _status.value = s
        return s
    }
}
