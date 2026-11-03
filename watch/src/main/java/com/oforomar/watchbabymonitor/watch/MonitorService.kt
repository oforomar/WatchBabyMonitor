package com.oforomar.watchbabymonitor.watch

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.oforomar.watchbabymonitor.shared.Messenger
import com.oforomar.watchbabymonitor.shared.Paths
import com.oforomar.watchbabymonitor.shared.Payload
import com.oforomar.watchbabymonitor.shared.Timing
import com.oforomar.watchbabymonitor.shared.R as SharedR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Foreground service that listens on the microphone, runs cry detection and
 * reports to the phone. The watch itself stays silent: it sits next to the baby.
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var audioJob: Job? = null
    private var heartbeatJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopRequested = false
    @Volatile private var destroyed = false
    private var lowBatteryReported = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                return START_NOT_STICKY
            }
            else -> startMonitoring()
        }
        // Not sticky: Android 14 forbids restarting a microphone service from the
        // background. If we die, the phone's watchdog notices the missing heartbeats.
        return START_NOT_STICKY
    }

    private fun startMonitoring() {
        if (audioJob?.isActive == true) return

        // startForegroundService() obliges us to call startForeground() even if we bail out.
        goForeground()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            MonitorState.update { it.copy(error = "Microphone permission missing") }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WatchBabyMonitor:listen")
            .apply { acquire() }

        stopRequested = false
        lowBatteryReported = false
        MonitorState.update { it.copy(running = true, crying = false, error = null) }

        scope.launch { Messenger.send(this@MonitorService, Paths.MONITOR_START, statusPayload()) }
        heartbeatJob = scope.launch { heartbeatLoop() }
        audioJob = scope.launch(Dispatchers.IO) {
            try {
                audioLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // MediaPipe exceptions, native link errors, anything: report, never die silently.
                Log.e(TAG, "audio loop crashed", t)
                reportFault("detector_crash_${t.javaClass.simpleName}")
            } finally {
                // If listening ended without the user pressing Stop, stop everything so
                // the watch never claims to be listening (heartbeats) while it isn't.
                if (!stopRequested && !destroyed) {
                    withContext(NonCancellable + Dispatchers.Main) { stopAfterFault() }
                }
            }
        }
    }

    /**
     * Listening failed. Stop heartbeats without sending MONITOR_STOP: the phone keeps
     * expecting heartbeats, so after the fault alarm its watchdog rings "Monitor lost" too.
     */
    private fun stopAfterFault() {
        heartbeatJob?.cancel()
        MonitorState.update { it.copy(running = false, crying = false, levelDb = -120f, score = 0f) }
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopMonitoring() {
        stopRequested = true
        audioJob?.cancel()
        heartbeatJob?.cancel()
        scope.launch(NonCancellable) {
            Messenger.send(this@MonitorService, Paths.MONITOR_STOP, statusPayload())
        }
        MonitorState.update { it.copy(running = false, crying = false, levelDb = -120f, score = 0f) }
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        if (!stopRequested && MonitorState.state.value.running) {
            // Killed without the user pressing Stop: tell the phone right away.
            scope.launch(NonCancellable) {
                Messenger.send(
                    this@MonitorService, Paths.FAULT,
                    statusPayload().copy(reason = "watch_service_stopped"),
                )
            }
            MonitorState.update { it.copy(running = false, error = "Monitoring stopped unexpectedly") }
        }
        audioJob?.cancel()
        heartbeatJob?.cancel()
        releaseWakeLock()
        // Give the last message a moment, then tear down.
        scope.launch {
            delay(3_000)
            scope.cancel()
        }
        super.onDestroy()
    }

    // ---- Audio + detection ------------------------------------------------------------

    @SuppressLint("MissingPermission") // checked in startMonitoring()
    private suspend fun audioLoop() {
        val detector: CryDetector = try {
            YamnetCryDetector(this)
        } catch (t: Throwable) { // includes UnsatisfiedLinkError from the native lib
            Log.e(TAG, "detector init failed", t)
            reportFault("model_load_failed")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            YamnetCryDetector.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            YamnetCryDetector.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf, HOP_SAMPLES * 2 * 2),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            detector.close()
            reportFault("mic_init_failed")
            return
        }

        val hop = ShortArray(HOP_SAMPLES)
        val window = FloatArray(YamnetCryDetector.WINDOW_SAMPLES)
        var filled = 0
        val debouncer = Debouncer()
        var lastAlertMs = 0L

        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                reportFault("mic_start_failed")
                return
            }

            while (currentCoroutineContext().isActive) {
                // Read exactly one hop (0.5 s).
                var offset = 0
                while (offset < HOP_SAMPLES) {
                    val n = recorder.read(hop, offset, HOP_SAMPLES - offset)
                    if (n < 0) {
                        reportFault("mic_read_error_$n")
                        return
                    }
                    offset += n
                }

                // Slide the 0.975 s window left by one hop and append the new samples.
                val keep = window.size - HOP_SAMPLES
                System.arraycopy(window, HOP_SAMPLES, window, 0, keep)
                for (i in 0 until HOP_SAMPLES) window[keep + i] = hop[i] / 32768f
                filled = minOf(window.size, filled + HOP_SAMPLES)

                val level = rmsDbfs(hop)
                if (filled < window.size) {
                    MonitorState.update { it.copy(levelDb = level) }
                    continue
                }

                val sensitivity = Settings.sensitivity(this)
                // Energy gate: don't run the model while the room is quiet.
                val score = if (level < sensitivity.gateDbfs) 0f else detector.cryScore(window)
                val positive = score >= sensitivity.threshold
                val now = System.currentTimeMillis()

                MonitorState.update { it.copy(levelDb = level, score = score) }

                // Sends run in their own coroutines so a slow Bluetooth link never
                // stalls the microphone reads.
                when (debouncer.update(positive, now)) {
                    Debouncer.Event.START -> {
                        Log.i(TAG, "crying started (score=$score)")
                        MonitorState.update { it.copy(crying = true, lastCryMs = now) }
                        lastAlertMs = now
                        sendCryStart(score)
                    }
                    Debouncer.Event.STOP -> {
                        Log.i(TAG, "crying stopped")
                        MonitorState.update { it.copy(crying = false) }
                        scope.launch { Messenger.send(this@MonitorService, Paths.CRY_STOP, statusPayload()) }
                    }
                    null -> {
                        // Still crying long after the alert (acknowledged or not): alert again.
                        if (debouncer.crying && now - lastAlertMs >= REALERT_MS) {
                            Log.i(TAG, "still crying, re-alerting")
                            lastAlertMs = now
                            MonitorState.update { it.copy(lastCryMs = now) }
                            sendCryStart(score)
                        }
                    }
                }
            }
        } finally {
            try {
                recorder.stop()
            } catch (_: IllegalStateException) {
            }
            recorder.release()
            detector.close()
        }
    }

    private fun sendCryStart(score: Float) {
        scope.launch {
            val sent = Messenger.send(this@MonitorService, Paths.CRY_START, statusPayload().copy(score = score))
            MonitorState.update { it.copy(phoneLinked = sent > 0) }
        }
    }

    private suspend fun reportFault(reason: String) {
        Log.e(TAG, "fault: $reason")
        MonitorState.update { it.copy(error = reason) }
        Messenger.send(this, Paths.FAULT, statusPayload().copy(reason = reason))
    }

    // ---- Heartbeat ------------------------------------------------------------------

    private suspend fun heartbeatLoop() {
        while (currentCoroutineContext().isActive) {
            val payload = statusPayload()
            val sent = Messenger.send(this, Paths.HEARTBEAT, payload)
            MonitorState.update { it.copy(phoneLinked = sent > 0) }

            val battery = payload.batteryPct
            if (battery != null && battery < LOW_BATTERY_PCT && payload.charging == false) {
                if (!lowBatteryReported) {
                    lowBatteryReported = true
                    Messenger.send(this, Paths.FAULT, payload.copy(reason = "watch_battery_low"))
                }
            } else {
                lowBatteryReported = false
            }
            delay(Timing.HEARTBEAT_INTERVAL_MS)
        }
    }

    private fun statusPayload(): Payload {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return Payload(
            batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            charging = bm.isCharging,
        )
    }

    // ---- Foreground notification ----------------------------------------------------

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_monitor),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setSound(null, null); enableVibration(false) },
        )

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(SharedR.drawable.ic_stat_monitor)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_listening))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp)

        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(SharedR.drawable.ic_stat_monitor)
            .setTouchIntent(openApp)
            .setStatus(Status.Builder().addTemplate(getString(R.string.notif_listening)).build())
            .build()
            .apply(applicationContext)

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            builder.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "MonitorService"
        private const val CHANNEL_ID = "monitor"
        private const val NOTIFICATION_ID = 1
        private const val HOP_SAMPLES = 8_000 // 0.5 s at 16 kHz
        private const val LOW_BATTERY_PCT = 20
        private const val REALERT_MS = 3 * 60_000L

        const val ACTION_START = "com.oforomar.watchbabymonitor.START"
        const val ACTION_STOP = "com.oforomar.watchbabymonitor.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MonitorService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MonitorService::class.java).setAction(ACTION_STOP))
        }
    }
}
