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
)

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
    }

    val view: Flow<ViewPreferences> = context.dataStore.data.map {
        val defaults = ViewPreferences()
        ViewPreferences(
            lastView = it[Keys.lastView],
            hourHeight = it[Keys.hourHeight] ?: defaults.hourHeight,
            dayStartHour = it[Keys.dayStartHour] ?: defaults.dayStartHour,
            dimPast = it[Keys.dimPast] ?: defaults.dimPast,
        )
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
