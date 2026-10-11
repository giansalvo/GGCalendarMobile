package io.github.giansalvo.ggcalendar.core.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.giansalvo.ggcalendar.core.data.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton

/** Builds and shows the reminder notifications: the popup, the alarm one, and the "missed alarm" one. */
@Singleton
class ReminderNotifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        fun channel(id: String, name: Int, importance: Int, silent: Boolean = false) =
            NotificationChannel(id, context.getString(name), importance).apply {
                if (silent) {
                    // The alarm sound is played by AlarmService as a system alarm: it must ring in silent mode too.
                    setSound(null, null)
                    enableVibration(false)
                }
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        manager.createNotificationChannel(channel(CHANNEL_REMINDERS, R.string.channel_reminders, NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(channel(CHANNEL_ALARMS, R.string.channel_alarms, NotificationManager.IMPORTANCE_HIGH, silent = true))
        manager.createNotificationChannel(channel(CHANNEL_MISSED, R.string.channel_missed, NotificationManager.IMPORTANCE_DEFAULT))
    }

    @SuppressLint("MissingPermission") // areNotificationsEnabled is checked: without the permission nothing is shown
    fun showPopup(alert: ReminderAlert) {
        if (!manager.areNotificationsEnabled()) return
        val builder = base(alert, CHANNEL_REMINDERS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(alert))
            .addAction(0, context.getString(R.string.snooze_minutes, AppPreferences.DEFAULT_SNOOZE_MINUTES), snoozeIntent(alert, AppPreferences.DEFAULT_SNOOZE_MINUTES))
        alert.meetLink?.let { builder.addAction(0, context.getString(R.string.join_meet), viewIntent(alert, it)) }
        manager.notify(alert.notificationId, builder.build())
    }

    /** The alarm notification: full screen over the lock screen, a heads-up notification when the phone is in use. */
    fun alarmNotification(alert: ReminderAlert, fullScreen: Boolean = true): Notification {
        val screen = alarmScreen(alert)
        val builder = base(alert, CHANNEL_ALARMS)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(screen)
            .addAction(0, context.getString(R.string.snooze_minutes, AppPreferences.DEFAULT_SNOOZE_MINUTES), snoozeIntent(alert, AppPreferences.DEFAULT_SNOOZE_MINUTES))
            .addAction(0, context.getString(R.string.dismiss), dismissIntent(alert))
        if (fullScreen) builder.setFullScreenIntent(screen, true)
        return builder.build()
    }

    @SuppressLint("MissingPermission")
    fun showQueuedAlarm(alert: ReminderAlert) {
        if (manager.areNotificationsEnabled()) manager.notify(alert.notificationId, alarmNotification(alert, fullScreen = false))
    }

    @SuppressLint("MissingPermission")
    fun showMissed(alert: ReminderAlert) {
        if (!manager.areNotificationsEnabled()) return
        val notification = base(alert, CHANNEL_MISSED)
            .setContentTitle(context.getString(R.string.missed_alarm) + ": " + alert.title)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(alert))
            .build()
        manager.notify(alert.notificationId + 1, notification)
    }

    fun cancel(alert: ReminderAlert) = manager.cancel(alert.notificationId)

    private fun base(alert: ReminderAlert, channel: String): NotificationCompat.Builder {
        val whenText = whenText(context, alert)
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(alert.title.ifBlank { context.getString(R.string.alarm_title) })
            .setContentText(whenText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(whenText, alert.location).filter { it.isNotBlank() }.joinToString("\n")))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(alert.beginUtc)
            .setShowWhen(false)
    }

    /** The tap on a notification: the app opens on the occurrence. */
    fun openApp(alert: ReminderAlert): PendingIntent {
        val intent = (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(Reminders.EXTRA_OPEN_EVENT, alert.eventId)
            .putExtra(Reminders.EXTRA_OPEN_BEGIN, alert.beginUtc)
            .setData(Uri.parse("ggcalendar://open/" + Uri.encode(alert.key)))
        return PendingIntent.getActivity(context, 0, intent, FLAGS)
    }

    private fun alarmScreen(alert: ReminderAlert): PendingIntent {
        val intent = alert.putInto(Intent(context, AlarmActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            .setData(Uri.parse("ggcalendar://alarm/" + Uri.encode(alert.key)))
        return PendingIntent.getActivity(context, 0, intent, FLAGS)
    }

    // Pending intents differ only by extras otherwise, which Android ignores: the data makes each one distinct.
    private fun snoozeIntent(alert: ReminderAlert, minutes: Int): PendingIntent {
        val intent = alert.putInto(Intent(context, ReminderReceiver::class.java).setAction(Reminders.ACTION_SNOOZE))
            .putExtra(Reminders.EXTRA_SNOOZE_MINUTES, minutes)
            .setData(Uri.parse("ggcalendar://snooze/$minutes/" + Uri.encode(alert.key)))
        return PendingIntent.getBroadcast(context, 0, intent, FLAGS)
    }

    private fun dismissIntent(alert: ReminderAlert): PendingIntent {
        val intent = alert.putInto(Intent(context, ReminderReceiver::class.java).setAction(Reminders.ACTION_DISMISS))
            .setData(Uri.parse("ggcalendar://dismiss/" + Uri.encode(alert.key)))
        return PendingIntent.getBroadcast(context, 0, intent, FLAGS)
    }

    private fun viewIntent(alert: ReminderAlert, link: String): PendingIntent =
        PendingIntent.getActivity(context, alert.notificationId, Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), FLAGS)

    companion object {
        const val CHANNEL_REMINDERS = "reminders"
        const val CHANNEL_ALARMS = "alarms"
        const val CHANNEL_MISSED = "missed"
        private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        /** "in 10 min. · 10:00 – 11:00", with the time left counted when the reminder is shown. */
        fun whenText(context: Context, alert: ReminderAlert): String {
            val now = System.currentTimeMillis()
            val range = if (alert.allDay) context.getString(R.string.all_day)
            else DateUtils.formatDateRange(context, alert.beginUtc, alert.endUtc, DateUtils.FORMAT_SHOW_TIME)
            return relative(context, alert.beginUtc, now) + " · " + range
        }

        fun relative(context: Context, begin: Long, now: Long): String =
            if (kotlin.math.abs(begin - now) < DateUtils.MINUTE_IN_MILLIS) context.getString(R.string.now)
            else DateUtils.getRelativeTimeSpanString(begin, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }
}
