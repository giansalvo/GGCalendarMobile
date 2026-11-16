package io.github.giansalvo.ggcalendar.feature.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.glance.appwidget.GlanceAppWidget
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.giansalvo.ggcalendar.core.data.AppPreferences
import io.github.giansalvo.ggcalendar.core.data.CalendarRepository
import io.github.giansalvo.ggcalendar.core.data.InstanceWindows
import io.github.giansalvo.ggcalendar.core.model.AppLinks
import io.github.giansalvo.ggcalendar.core.model.EventInstance
import io.github.giansalvo.ggcalendar.feature.widget.agenda.AgendaWidget
import io.github.giansalvo.ggcalendar.feature.widget.month.MonthWidgetRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/** A widget is not an Android component Hilt can inject, so it asks the application graph for what it needs. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WidgetEntryPoint {
    fun repository(): CalendarRepository
    fun preferences(): AppPreferences
}

/** What the widgets read from the app: the same repository and settings as the screens, so they agree with them. */
internal object WidgetData {
    private fun entry(context: Context) = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

    /**
     * The instances of the days [from, to) of the visible calendars. Inside the window the app keeps expanded (a year back and
     * two ahead of today) the first answer of the database is the complete one. Outside it the repository's flow expands the
     * recurring series first and then emits again, so only then the widget waits a moment to see the second answer.
     */
    suspend fun instances(context: Context, from: LocalDate, to: LocalDate): List<EventInstance> {
        val repository = entry(context).repository()
        val kept = InstanceWindows.default(LocalDate.now(), ZoneId.systemDefault().id)
        if (from >= kept.from && to <= kept.to) {
            return withTimeoutOrNull(LOAD_TIMEOUT_MS) { repository.instances(from, to).first() } ?: emptyList()
        }
        return withTimeoutOrNull(LOAD_TIMEOUT_MS) {
            coroutineScope {
                val latest = MutableStateFlow<List<EventInstance>?>(null)
                val job = launch { repository.instances(from, to).collect { latest.value = it } }
                latest.filterNotNull().first()
                delay(SETTLE_MS)
                job.cancel()
                latest.value
            }
        } ?: emptyList()
    }

    /** The first day of the week chosen in the settings; the language's own one when nothing was chosen. */
    suspend fun firstDayOfWeek(context: Context): DayOfWeek =
        entry(context).preferences().view.first().weekStart ?: WeekFields.of(Locale.getDefault()).firstDayOfWeek

    private const val LOAD_TIMEOUT_MS = 8_000L
    private const val SETTLE_MS = 400L
}

/** The tap actions: always the app's own launch intent, with the extras `MainActivity` reads. */
internal object WidgetIntents {
    private fun launch(context: Context): Intent =
        (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent())
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun openApp(context: Context): Intent = launch(context).setData(Uri.parse("ggcalendar://widget/open"))

    fun openEvent(context: Context, event: EventInstance): Intent {
        val begin = event.begin.toEpochMilli()
        return launch(context)
            .putExtra(AppLinks.EXTRA_OPEN_EVENT, event.eventId)
            .putExtra(AppLinks.EXTRA_OPEN_BEGIN, begin)
            // Each widget row needs an intent of its own: the extras alone do not tell two pending intents apart.
            .setData(Uri.parse("ggcalendar://widget/event/" + Uri.encode(event.eventId) + "/" + begin))
    }

    /** The day view of the app on [day]. */
    fun openDay(context: Context, day: LocalDate): Intent {
        val epochDay = day.toEpochDay()
        return launch(context)
            .putExtra(AppLinks.EXTRA_OPEN_DAY, epochDay)
            .setData(Uri.parse("ggcalendar://widget/day/$epochDay"))
    }

    fun newEvent(context: Context, day: LocalDate?): Intent {
        val epochDay = day?.toEpochDay() ?: -1L
        return launch(context)
            .putExtra(AppLinks.EXTRA_NEW_EVENT_DAY, epochDay)
            .setData(Uri.parse("ggcalendar://widget/new/$epochDay"))
    }
}

/** Colors of the events (the calendar's hex) in the form Glance wants, with a text color that can be read on them. */
internal object WidgetColors {
    private val fallback = Color(0xFF7986CB)

    /** "#RRGGBB" or "#AARRGGBB" (with or without the #); the fallback color for anything else. Plain Kotlin, so it is tested without Android. */
    fun parse(hex: String): Color {
        val digits = hex.trim().removePrefix("#")
        val value = digits.toLongOrNull(16)
        return when {
            value == null || digits.any { !it.isLetterOrDigit() } -> fallback
            digits.length == 6 -> Color((0xFF000000L or value).toInt())
            digits.length == 8 -> Color(value.toInt())
            else -> fallback
        }
    }

    /** Dark text on a light color, white on a dark one (the same idea as the app's `contentColorOn`). */
    fun textOn(background: Color): Color {
        val luminance = 0.299f * background.red + 0.587f * background.green + 0.114f * background.blue
        return if (luminance > 0.55f) Color(0xFF1F1F1F) else Color.White
    }
}

/**
 * Why the widgets load their data inside the composition and not in `provideGlance`: Glance calls `provideGlance` once for each
 * session, and `update()` on a session that is still alive only recomposes it with the state of the widget (a session lives
 * on for a while after the last update). So whatever must change a widget goes through its state: [REFRESH] says "the data
 * changed", the month widget's own offset says which month, and the content is loaded again when they change.
 */
internal val REFRESH = longPreferencesKey("refresh")

/** Redraws the widgets when the data changed. Calls come in bursts (an import, a sync), so they are merged. */
internal object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests = Channel<Context>(Channel.CONFLATED)

    init {
        scope.launch {
            for (context in requests) {
                delay(DEBOUNCE_MS)
                updateAll(context)
            }
        }
    }

    fun request(context: Context) {
        requests.trySend(context.applicationContext)
    }

    private suspend fun updateAll(context: Context) {
        val manager = GlanceAppWidgetManager(context)
        // The Month widget is made of RemoteViews, drawn by its own code; the Schedule widget is Glance.
        try {
            MonthWidgetRenderer.updateAll(context)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "refresh of the month widget failed", e)
        }
        val widgets: List<GlanceAppWidget> = listOf(AgendaWidget())
        for (widget in widgets) {
            try {
                val ids = manager.getGlanceIds(widget.javaClass)
                ids.forEach { id ->
                    updateAppWidgetState(context, id) { it[REFRESH] = System.currentTimeMillis() }
                    widget.update(context, id)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "refresh of ${widget.javaClass.simpleName} failed", e)
            }
        }
    }

    private const val DEBOUNCE_MS = 500L
    private const val TAG = "GGWidget"
}
