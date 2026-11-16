package io.github.giansalvo.ggcalendar.feature.widget.month

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Month widget: the grid of the month with the events as colored bars, arrows to change month and a "+" for a new event.
 * It is made of RemoteViews from XML layouts (see [MonthWidgetRenderer]): Glance allows 500 views in all, too few for the bars of a month.
 * The class keeps the name it had as a Glance receiver, so that the widgets already on a home screen go on working.
 */
class MonthWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context) { MonthWidgetRenderer.update(context, appWidgetIds) }
    }

    /** The widget was resized: the rows of events that fit are not the same. */
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle) {
        render(context) { MonthWidgetRenderer.update(context, intArrayOf(appWidgetId)) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        MonthWidgetState.remove(context, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STEP, ACTION_TODAY -> {
                val id = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
                if (intent.action == ACTION_TODAY) MonthWidgetState.setOffset(context, id, 0)
                else MonthWidgetState.setOffset(context, id, MonthWidgetState.offset(context, id) + intent.getIntExtra(EXTRA_STEP, 0))
                render(context) { MonthWidgetRenderer.update(context, intArrayOf(id)) }
            }
            else -> super.onReceive(context, intent)
        }
    }

    /** A receiver has a few seconds: the data is read off the main thread and the broadcast is kept open until the widget is drawn. */
    private fun render(context: Context, block: suspend () -> Unit) {
        val pending = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                withTimeoutOrNull(RENDER_TIMEOUT_MS) { block() }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w(TAG, "month widget not drawn", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_STEP = "io.github.giansalvo.ggcalendar.widget.MONTH_STEP"
        const val ACTION_TODAY = "io.github.giansalvo.ggcalendar.widget.MONTH_TODAY"
        const val EXTRA_WIDGET_ID = "widget_id"
        const val EXTRA_STEP = "step"
        private const val RENDER_TIMEOUT_MS = 9_000L
        private const val TAG = "GGWidget"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

/** The month each widget shows, as months away from the current one: kept per widget, so each one can look at a different month. */
internal object MonthWidgetState {
    private const val FILE = "month_widget"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun offset(context: Context, id: Int): Int = prefs(context).getInt("offset_$id", 0)

    fun setOffset(context: Context, id: Int, offset: Int) {
        prefs(context).edit().putInt("offset_$id", offset).apply()
    }

    fun remove(context: Context, ids: IntArray) {
        prefs(context).edit().apply { ids.forEach { remove("offset_$it") } }.apply()
    }
}
