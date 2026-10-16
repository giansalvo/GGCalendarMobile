package io.github.giansalvo.ggcalendar.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** View preferences without a settings screen yet (milestone M6), with the keys of the desktop `AppSettings`. */
data class ViewPreferences(
    val lastView: String? = null,
    val hourHeight: Float = 48f,
    val dayStartHour: Int = 7,
    val dimPast: Boolean = true,
    /** The first day of the week; null follows the language of the phone. */
    val weekStart: java.time.DayOfWeek? = null,
)

/** How the app looks: light, dark or as the system, and the wallpaper colors of Android 12 and later. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Appearance(val theme: ThemeMode = ThemeMode.SYSTEM, val dynamicColor: Boolean = false)

/** The days covered by the `instances` table, the time zone they were computed in and when. */
data class InstanceWindow(val from: LocalDate, val to: LocalDate, val zoneId: String, val computedOn: LocalDate)

@Singleton
class AppPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val lastView = stringPreferencesKey("default_view")
        val hourHeight = floatPreferencesKey("hour_height")
        val dayStartHour = intPreferencesKey("day_start_hour")
        val dimPast = booleanPreferencesKey("dim_past")
        val windowFrom = longPreferencesKey("instances_from")
        val windowTo = longPreferencesKey("instances_to")
        val windowZone = stringPreferencesKey("instances_zone")
        val windowComputedOn = longPreferencesKey("instances_computed_on")
        val alarmMinutes = intPreferencesKey("alarm_minutes")
        val alarmSound = stringPreferencesKey("alarm_sound")
        val reminderLastCheck = longPreferencesKey("reminder_last_check")
        val askedNotifications = booleanPreferencesKey("asked_notifications")
        val bootCount = intPreferencesKey("handled_boot_count")
        val theme = stringPreferencesKey("theme")
        val birthdays = booleanPreferencesKey("birthdays_from_contacts")
        val weekStart = stringPreferencesKey("week_start")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val permissionsIntroSeen = booleanPreferencesKey("permissions_intro_seen")
    }

    /** The "before you start" screen about the permissions of alarms has been shown (or closed) once. */
    val permissionsIntroSeen: Flow<Boolean> = context.dataStore.data.map { it[Keys.permissionsIntroSeen] == true }

    suspend fun setPermissionsIntroSeen() {
        context.dataStore.edit { it[Keys.permissionsIntroSeen] = true }
    }

    /** How long an alarm rings before it stops by itself and becomes a "missed alarm", in minutes (1 to 10). */
    val alarmMinutes: Flow<Int> = context.dataStore.data.map { (it[Keys.alarmMinutes] ?: DEFAULT_ALARM_MINUTES).coerceIn(1, 10) }

    suspend fun setAlarmMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.alarmMinutes] = minutes.coerceIn(1, 10) }
    }

    /** The sound alarms ring with (the Uri of a system sound as text); null = the alarm sound of the phone. */
    val alarmSound: Flow<String?> = context.dataStore.data.map { it[Keys.alarmSound] }

    suspend fun setAlarmSound(uri: String?) {
        context.dataStore.edit { if (uri == null) it.remove(Keys.alarmSound) else it[Keys.alarmSound] = uri }
    }

    /** Reminders firing up to this time (epoch millis) have been handled; null before the first check. */
    suspend fun reminderLastCheck(): Long? = context.dataStore.data.first()[Keys.reminderLastCheck]

    suspend fun setReminderLastCheck(at: Long) {
        context.dataStore.edit { it[Keys.reminderLastCheck] = at }
    }

    /** The boot (Settings.Global.BOOT_COUNT) after which the reminders were last restored; null on a fresh install. */
    suspend fun handledBootCount(): Int? = context.dataStore.data.first()[Keys.bootCount]

    suspend fun setHandledBootCount(count: Int) {
        context.dataStore.edit { it[Keys.bootCount] = count }
    }

    /** The notification permission is asked once by itself; afterwards only from the "Notifications and alarms" screen. */
    suspend fun askedNotifications(): Boolean = context.dataStore.data.first()[Keys.askedNotifications] == true

    suspend fun setAskedNotifications() {
        context.dataStore.edit { it[Keys.askedNotifications] = true }
    }

    companion object {
        const val DEFAULT_ALARM_MINUTES = 2

        /** Snooze length offered first (5, 15 and 30 from a menu); a setting in M6. */
        const val DEFAULT_SNOOZE_MINUTES = 10
    }

    val view: Flow<ViewPreferences> = context.dataStore.data.map {
        val defaults = ViewPreferences()
        ViewPreferences(
            lastView = it[Keys.lastView],
            hourHeight = it[Keys.hourHeight] ?: defaults.hourHeight,
            dayStartHour = it[Keys.dayStartHour] ?: defaults.dayStartHour,
            dimPast = it[Keys.dimPast] ?: defaults.dimPast,
            weekStart = it[Keys.weekStart]?.let { name -> java.time.DayOfWeek.entries.firstOrNull { d -> d.name == name } },
        )
    }

    val appearance: Flow<Appearance> = context.dataStore.data.map {
        Appearance(
            theme = it[Keys.theme]?.let { name -> ThemeMode.entries.firstOrNull { m -> m.name == name } } ?: ThemeMode.SYSTEM,
            dynamicColor = it[Keys.dynamicColor] ?: false,
        )
    }

    /** The "Birthdays" calendar made from the phone's contacts; off until the user turns it on (it needs a permission). */
    val birthdaysEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.birthdays] ?: false }

    suspend fun setBirthdaysEnabled(on: Boolean) {
        context.dataStore.edit { it[Keys.birthdays] = on }
    }

    suspend fun setTheme(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.theme] = mode.name }
    }

    suspend fun setDynamicColor(on: Boolean) {
        context.dataStore.edit { it[Keys.dynamicColor] = on }
    }

    suspend fun setDayStartHour(hour: Int) {
        context.dataStore.edit { it[Keys.dayStartHour] = hour.coerceIn(0, 12) }
    }

    suspend fun setWeekStart(day: java.time.DayOfWeek) {
        context.dataStore.edit { it[Keys.weekStart] = day.name }
    }

    suspend fun setDimPast(on: Boolean) {
        context.dataStore.edit { it[Keys.dimPast] = on }
    }

    suspend fun setLastView(view: String) {
        context.dataStore.edit { it[Keys.lastView] = view }
    }

    suspend fun setHourHeight(height: Float) {
        context.dataStore.edit { it[Keys.hourHeight] = height }
    }

    suspend fun instanceWindow(): InstanceWindow? = context.dataStore.data.first().let {
        InstanceWindow(
            from = LocalDate.ofEpochDay(it[Keys.windowFrom] ?: return null),
            to = LocalDate.ofEpochDay(it[Keys.windowTo] ?: return null),
            zoneId = it[Keys.windowZone] ?: return null,
            computedOn = LocalDate.ofEpochDay(it[Keys.windowComputedOn] ?: return null),
        )
    }

    suspend fun setInstanceWindow(window: InstanceWindow) {
        context.dataStore.edit {
            it[Keys.windowFrom] = window.from.toEpochDay()
            it[Keys.windowTo] = window.to.toEpochDay()
            it[Keys.windowZone] = window.zoneId
            it[Keys.windowComputedOn] = window.computedOn.toEpochDay()
        }
    }
}
