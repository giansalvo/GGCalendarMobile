package io.github.giansalvo.ggcalendar.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.giansalvo.ggcalendar.core.data.AppPreferences
import io.github.giansalvo.ggcalendar.core.data.Appearance
import io.github.giansalvo.ggcalendar.core.data.ThemeMode
import io.github.giansalvo.ggcalendar.core.data.ViewPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val prefs: AppPreferences) : ViewModel() {
    val appearance: Flow<Appearance> = prefs.appearance
    val view: Flow<ViewPreferences> = prefs.view

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { prefs.setTheme(mode) }
    fun setDynamicColor(on: Boolean) = viewModelScope.launch { prefs.setDynamicColor(on) }
    fun setDayStartHour(hour: Int) = viewModelScope.launch { prefs.setDayStartHour(hour) }
    fun setDimPast(on: Boolean) = viewModelScope.launch { prefs.setDimPast(on) }
}
