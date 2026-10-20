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
import android.view.View
import android.widget.RemoteViews
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
        // The same alarm while its own screen is open: the banner would cover the top of that screen.
        manager.createNotificationChannel(channel(CHANNEL_ALARMS_QUIET, R.string.channel_alarms_screen, NotificationManager.IMPORTANCE_LOW, silent = true))
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
    fun alarmNotification(alert: ReminderAlert, fullScreen: Boolean = true, quiet: Boolean = false): Notification {
        val screen = alarmScreen(alert)
        val builder = base(alert, if (quiet) CHANNEL_ALARMS_QUIET else CHANNEL_ALARMS)
            .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            // A group of its own: with a reminder already in the shade Android 16 folds two notifications of the app into an automatic
            // group ("Aggregate_AlertingSection") and the full-screen intent of the alarm is not launched (seen on the API 36 emulator).
            .setGroup("alarm_" + alert.notificationId)
            .setOngoing(true)
            .setContentIntent(screen)
            .addAction(0, context.getString(R.string.snooze_minutes, AppPreferences.DEFAULT_SNOOZE_MINUTES), snoozeIntent(alert, AppPreferences.DEFAULT_SNOOZE_MINUTES))
            .addAction(0, context.getString(R.string.dismiss), dismissIntent(alert))
        if (quiet) return builder.build() // the alarm screen is open: no banner over it
        if (fullScreen) builder.setFullScreenIntent(screen, true)
        builder.setCustomHeadsUpContentView(headsUp(alert))
        return builder.build()
    }

    /** The alarm banner that slides in while the phone is in use: bigger than the system one, with both answers on it. */
    private fun headsUp(alert: ReminderAlert): RemoteViews {
        val view = RemoteViews(context.packageName, R.layout.notification_alarm_heads_up)
        view.setTextViewText(R.id.hun_title, alert.title.ifBlank { context.getString(R.string.alarm_title) })
        view.setTextViewText(R.id.hun_when, whenText(context, alert))
        if (alert.location.isBlank()) view.setViewVisibility(R.id.hun_where, View.GONE) else view.setTextViewText(R.id.hun_where, alert.location)
        view.setTextViewText(R.id.hun_snooze, context.getString(R.string.snooze_minutes, AppPreferences.DEFAULT_SNOOZE_MINUTES))
        view.setTextViewText(R.id.hun_stop, context.getString(R.string.dismiss))
        view.setOnClickPendingIntent(R.id.hun_snooze, snoozeIntent(alert, AppPreferences.DEFAULT_SNOOZE_MINUTES))
        view.setOnClickPendingIntent(R.id.hun_stop, dismissIntent(alert))
        return view
    }

    @SuppressLint("MissingPermission")
    fun showQueuedAlarm(alert: ReminderAlert) {
        // An alarm waiting behind the one on screen: no banner over the alarm screen that is open.
        if (manager.areNotificationsEnabled()) manager.notify(alert.notificationId, alarmNotification(alert, fullScreen = false, quiet = AlarmActivity.visible))
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
        const val CHANNEL_ALARMS_QUIET = "alarms_quiet"
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
