package com.oforomar.watchbabymonitor.shared

import org.json.JSONObject

/**
 * Message paths exchanged over the Wearable Data Layer.
 *
 * Watch -> phone: monitoring state, cry events, heartbeats, faults.
 * Phone -> watch: acknowledge an alarm, run a test of the whole chain.
 */
object Paths {
    const val MONITOR_START = "/monitor/start"
    const val MONITOR_STOP = "/monitor/stop"
    const val CRY_START = "/cry/start"
    const val CRY_STOP = "/cry/stop"
    const val HEARTBEAT = "/heartbeat"
    const val FAULT = "/fault"

    const val ACK = "/ack"
    const val TEST = "/cmd/test"
}

object Timing {
    /** Watch sends a heartbeat this often while monitoring. */
    const val HEARTBEAT_INTERVAL_MS = 15_000L

    /** Phone raises "Monitor lost" if no heartbeat arrives for this long. */
    const val WATCHDOG_TIMEOUT_MS = 60_000L
}

/** Small JSON payload carried by every message. */
data class Payload(
    val timeMs: Long = System.currentTimeMillis(),
    val score: Float? = null,
    val batteryPct: Int? = null,
    val charging: Boolean? = null,
    val reason: String? = null,
    val test: Boolean = false,
) {
    fun toBytes(): ByteArray = JSONObject().apply {
        put("t", timeMs)
        score?.let { put("score", it.toDouble()) }
        batteryPct?.let { put("battery", it) }
        charging?.let { put("charging", it) }
        reason?.let { put("reason", it) }
        put("test", test)
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun fromBytes(bytes: ByteArray?): Payload {
            if (bytes == null || bytes.isEmpty()) return Payload()
            return try {
                val o = JSONObject(String(bytes, Charsets.UTF_8))
                Payload(
                    timeMs = o.optLong("t", System.currentTimeMillis()),
                    score = if (o.has("score")) o.getDouble("score").toFloat() else null,
                    batteryPct = if (o.has("battery")) o.getInt("battery") else null,
                    charging = if (o.has("charging")) o.getBoolean("charging") else null,
                    reason = if (o.has("reason")) o.getString("reason") else null,
                    test = o.optBoolean("test", false),
                )
            } catch (e: Exception) {
                Payload()
            }
        }
    }
}
