package com.oforomar.watchbabymonitor.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.oforomar.watchbabymonitor.shared.Messenger
import com.oforomar.watchbabymonitor.shared.Paths
import com.oforomar.watchbabymonitor.shared.R as SharedR

/** Why the phone is ringing. */
enum class AlarmReason(val title: String, val body: String) {
    CRY("Baby is crying", "The watch hears sustained crying."),
    LOST("Monitor lost", "No signal from the watch for a minute. Check the watch."),
    FAULT("Monitor problem", "The watch reported a problem."),
    TEST("Test alert", "The whole chain works: watch → phone → alarm."),
}

/**
 * Raises a loud, repeating alarm that plays on the alarm audio stream (heard in
 * "alarms only" Do Not Disturb) and opens [AlarmActivity] full-screen when locked.
 */
object AlarmController {
    private const val NOTIFICATION_ID = 42

    /**
     * Android only honours setBypassDnd() if DND access was already granted when the
     * channel was created, so a second channel is used once the user grants it.
     */
    private fun channelId(nm: NotificationManager): String =
        if (nm.isNotificationPolicyAccessGranted) "alarm_dnd_v1" else "alarm_v1"

    fun ensureChannel(context: Context): String {
        val nm = context.getSystemService(NotificationManager::class.java)
        val id = channelId(nm)
        if (nm.getNotificationChannel(id) != null) return id
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val channel = NotificationChannel(
            id,
            context.getString(R.string.channel_alarm),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_alarm_desc)
            setSound(
                sound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
            setBypassDnd(true) // only honoured once DND access is granted
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
        return id
    }

    fun raise(context: Context, reason: AlarmReason, detail: String? = null) {
        val channelId = ensureChannel(context)
        val body = if (detail.isNullOrBlank()) reason.body else "${reason.body} ($detail)"

        PhoneState.update(context) {
            it.copy(alarmActive = true, alarmTitle = reason.title)
        }

        val fullScreen = PendingIntent.getActivity(
            context, 0,
            Intent(context, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(AlarmActivity.EXTRA_TITLE, reason.title)
                .putExtra(AlarmActivity.EXTRA_BODY, body),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val ack = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, AckReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(SharedR.drawable.ic_stat_monitor)
            .setContentTitle(reason.title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(0, context.getString(R.string.ack), ack)
            .build()
        // Repeat the sound until the notification is cancelled ("I'm on it").
        notification.flags = notification.flags or Notification.FLAG_INSISTENT

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notification permission revoked: nothing we can ring. The UI shows a warning.
        }
    }

    /** Silence the alarm and let the watch know someone is coming. */
    suspend fun acknowledge(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        val before = PhoneState.load(context)
        val wasLost = before.alarmTitle == AlarmReason.LOST.title
        PhoneState.update(context) {
            // Acknowledging "Monitor lost" ends the session; the next heartbeat
            // from the watch starts it (and the watchdog) again by itself.
            it.copy(alarmActive = false, alarmTitle = "", monitoring = if (wasLost) false else it.monitoring)
        }
        if (wasLost) Watchdog.disarm(context)
        Messenger.send(context, Paths.ACK)
    }
}
