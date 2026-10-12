package io.github.giansalvo.ggcalendar.feature.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.giansalvo.ggcalendar.core.data.AppPreferences
import io.github.giansalvo.ggcalendar.core.data.ThemeMode
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
)

@HiltViewModel
class SettingsViewModel @Inject constructor(private val prefs: AppPreferences) : ViewModel() {
    suspend fun load(): SettingsDraft {
        val appearance = prefs.appearance.first()
        val view = prefs.view.first()
        return SettingsDraft(
            theme = appearance.theme,
            dynamicColor = appearance.dynamicColor,
            weekStart = weekOf(view.weekStart ?: WeekSettings.localeDefault()),
            dayStartHour = view.dayStartHour,
            dimPast = view.dimPast,
        )
    }

    suspend fun save(draft: SettingsDraft) {
        prefs.setTheme(draft.theme)
        prefs.setDynamicColor(draft.dynamicColor)
        prefs.setWeekStart(draft.weekStart)
        prefs.setDayStartHour(draft.dayStartHour)
        prefs.setDimPast(draft.dimPast)
    }
}
