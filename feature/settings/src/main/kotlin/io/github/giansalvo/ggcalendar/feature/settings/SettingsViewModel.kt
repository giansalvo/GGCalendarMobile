package io.github.giansalvo.ggcalendar.feature.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.giansalvo.ggcalendar.core.data.AppPreferences
import io.github.giansalvo.ggcalendar.core.data.BirthdaySync
import io.github.giansalvo.ggcalendar.core.data.ContactsSource
import io.github.giansalvo.ggcalendar.core.data.ThemeMode
import io.github.giansalvo.ggcalendar.core.notifications.DirectBootStore
import io.github.giansalvo.ggcalendar.feature.event.WeekSettings
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import javax.inject.Inject

/** The choices of the settings screen as one value, so that they can be edited on a copy and saved together. */
data class SettingsDraft(
    val theme: ThemeMode,
    val dynamicColor: Boolean,
    val weekStart: DayOfWeek,
    val dayStartHour: Int,
    val dimPast: Boolean,
    /** New events start as Busy (on, the default) or as Free (off). */
    val defaultBusy: Boolean,
    /** New events start as Public (off, the default) or as Private (on). */
    val defaultPrivate: Boolean,
    val birthdays: Boolean,
    /** How long an alarm rings before it stops by itself (1 to 10 minutes). */
    val alarmMinutes: Int,
    /** The sound of the alarms (a Uri as text); null = the alarm sound of the phone. */
    val alarmSound: String?,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val birthdays: BirthdaySync,
    private val contacts: ContactsSource,
    private val alarmStore: DirectBootStore,
) : ViewModel() {
    val contactsGranted: Boolean get() = contacts.granted

    suspend fun load(): SettingsDraft {
        val appearance = prefs.appearance.first()
        val view = prefs.view.first()
        return SettingsDraft(
            theme = appearance.theme,
            dynamicColor = appearance.dynamicColor,
            weekStart = weekOf(view.weekStart ?: WeekSettings.localeDefault()),
            dayStartHour = view.dayStartHour,
            dimPast = view.dimPast,
            defaultBusy = prefs.defaultBusy.first(),
            defaultPrivate = prefs.defaultPrivate.first(),
            birthdays = prefs.birthdaysEnabled.first() && contacts.granted,
            alarmMinutes = prefs.alarmMinutes.first(),
            alarmSound = prefs.alarmSound.first(),
        )
    }

    suspend fun save(draft: SettingsDraft) {
        prefs.setTheme(draft.theme)
        prefs.setDynamicColor(draft.dynamicColor)
        prefs.setWeekStart(draft.weekStart)
        prefs.setDayStartHour(draft.dayStartHour)
        prefs.setDimPast(draft.dimPast)
        prefs.setDefaultBusy(draft.defaultBusy)
        prefs.setDefaultPrivate(draft.defaultPrivate)
        prefs.setBirthdaysEnabled(draft.birthdays)
        // The copy in the device protected storage is what the alarm uses while the phone is locked after a reboot.
        alarmStore.setAlarmMinutes(draft.alarmMinutes)
        prefs.setAlarmMinutes(draft.alarmMinutes)
        alarmStore.setAlarmSound(draft.alarmSound)
        prefs.setAlarmSound(draft.alarmSound)
        // The calendar appears (or goes away) as soon as the choice is saved.
        birthdays.sync()
    }
}
