package com.oforomar.watchbabymonitor.watch

/**
 * Turns noisy per-window classifier decisions into clean cry start/stop events.
 *
 * Crying starts when at least [need] of the last [window] windows were positive.
 * Crying stops after [quietMs] with no positive window.
 */
class Debouncer(
    private val need: Int = 3,
    private val window: Int = 5,
    private val quietMs: Long = 20_000L,
) {
    enum class Event { START, STOP }

    private val history = ArrayDeque<Boolean>()
    private var lastPositiveMs = 0L

    var crying: Boolean = false
        private set

    fun update(positive: Boolean, nowMs: Long): Event? {
        history.addLast(positive)
        while (history.size > window) history.removeFirst()
        if (positive) lastPositiveMs = nowMs

        if (!crying && history.count { it } >= need) {
            crying = true
            return Event.START
        }
        if (crying && nowMs - lastPositiveMs >= quietMs) {
            crying = false
            history.clear()
            return Event.STOP
        }
        return null
    }

    fun reset() {
        history.clear()
        crying = false
        lastPositiveMs = 0L
    }
}
