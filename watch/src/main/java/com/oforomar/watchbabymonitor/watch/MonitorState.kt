package com.oforomar.watchbabymonitor.watch

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Live state shared between [MonitorService] and the UI. */
object MonitorState {

    data class Snapshot(
        val running: Boolean = false,
        val levelDb: Float = -120f,
        val score: Float = 0f,
        val crying: Boolean = false,
        val phoneLinked: Boolean? = null,
        val lastCryMs: Long = 0L,
        val lastAckMs: Long = 0L,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun update(transform: (Snapshot) -> Snapshot) = _state.update(transform)
}

/** How eager the detector is. Higher sensitivity = quieter cries count, more false alarms. */
enum class Sensitivity(val label: String, val threshold: Float, val gateDbfs: Float) {
    LOW("Low", 0.50f, -50f),
    MEDIUM("Medium", 0.30f, -55f),
    HIGH("High", 0.15f, -62f);

    fun next(): Sensitivity = entries[(ordinal + 1) % entries.size]
}

object Settings {
    private const val PREFS = "settings"
    private const val KEY_SENSITIVITY = "sensitivity"

    fun sensitivity(context: Context): Sensitivity {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SENSITIVITY, Sensitivity.MEDIUM.name)
        return Sensitivity.entries.firstOrNull { it.name == name } ?: Sensitivity.MEDIUM
    }

    fun setSensitivity(context: Context, value: Sensitivity) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SENSITIVITY, value.name).apply()
    }
}
