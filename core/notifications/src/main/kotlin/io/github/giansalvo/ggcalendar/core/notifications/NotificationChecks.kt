package io.github.giansalvo.ggcalendar.core.notifications

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** What Android lets the app do for reminders, and the system screens that change it. */
data class ReminderPermissions(
    val notifications: Boolean,
    /** Notification reminders at the exact minute (always allowed from Android 13: calendar apps hold USE_EXACT_ALARM). */
    val exactAlarms: Boolean,
    /** The alarm screen over the lock screen (Android 14 and later ask the user). */
    val fullScreen: Boolean,
    val batteryUnrestricted: Boolean,
) {
    /** Reminders would not arrive at all, or late. */
    val blocksReminders: Boolean get() = !notifications || !exactAlarms
}

class NotificationChecks(private val context: Context) {
    fun status(): ReminderPermissions {
        val notifications = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        val fullScreen = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        val battery = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        return ReminderPermissions(notifications, exact, fullScreen, battery)
    }

    private fun appUri() = Uri.parse("package:" + context.packageName)

    fun notificationSettings() = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun exactAlarmSettings() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, appUri()) else appDetails()

    fun fullScreenSettings() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, appUri()) else appDetails()

    fun batterySettings() = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, appUri())

    /** The list of apps with battery optimization, where Samsung's "sleeping apps" are reached from. */
    fun batteryList() = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri())
}
