package io.github.giansalvo.ggcalendar.feature.widget.month

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.util.SizeF
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import io.github.giansalvo.ggcalendar.core.model.EventInstance
import io.github.giansalvo.ggcalendar.core.model.isBar
import io.github.giansalvo.ggcalendar.feature.widget.LaneHeights
import io.github.giansalvo.ggcalendar.feature.widget.MonthGrid
import io.github.giansalvo.ggcalendar.feature.widget.MonthModel
import io.github.giansalvo.ggcalendar.feature.widget.R
import io.github.giansalvo.ggcalendar.feature.widget.WidgetColors
import io.github.giansalvo.ggcalendar.feature.widget.WidgetData
import io.github.giansalvo.ggcalendar.feature.widget.WidgetIntents
import kotlinx.coroutines.CancellationException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * Draws the Month widget as RemoteViews: the layouts are in XML and the bars of the events are views added to them by this code.
 * Each week of the grid is a layout with the lines of the grid behind and the numbers of the days over them; under the numbers there
 * is a row (lane) for each level of events, and in a row a bar has the weight of the days it covers, so a bar of several days is one view.
 * Every view of the grid has the size the XML gives it: keep the heights here in line with the layouts.
 */
internal object MonthWidgetRenderer {
    // Heights in dp, as in the layouts (widget_month: padding, header and weekdays; widget_month_week: the row of the numbers).
    private const val PADDING_DP = 8f
    private const val HEADER_DP = 36f
    private const val WEEKDAYS_DP = 16f
    private const val DAY_ROW_DP = 16f

    // The heights at which a drawing is made for each size of the widget (Android 12 and later): from a small widget to a tall one.
    private const val MIN_WIDTH_DP = 40f
    private const val MIN_HEIGHT_DP = 150f
    private const val MAX_HEIGHT_DP = 1000f
    private const val STEP_DP = 5f
    private const val MAX_RENDITIONS = 16 // the most Android takes

    /** A row with an all-day or multi-day bar (widget_month_chip_t*: 15dp and 1dp of margin) and one with events of the day (chip_s*: 13dp and 1dp). */
    private const val BAR_ROW_DP = 16f
    private const val SMALL_ROW_DP = 14f

    private val TALL = intArrayOf(
        R.layout.widget_month_chip_t1, R.layout.widget_month_chip_t2, R.layout.widget_month_chip_t3, R.layout.widget_month_chip_t4,
        R.layout.widget_month_chip_t5, R.layout.widget_month_chip_t6, R.layout.widget_month_chip_t7,
    )
    private val SMALL = intArrayOf(
        R.layout.widget_month_chip_s1, R.layout.widget_month_chip_s2, R.layout.widget_month_chip_s3, R.layout.widget_month_chip_s4,
        R.layout.widget_month_chip_s5, R.layout.widget_month_chip_s6, R.layout.widget_month_chip_s7,
    )
    private val GAPS = intArrayOf(
        R.layout.widget_month_gap1, R.layout.widget_month_gap2, R.layout.widget_month_gap3, R.layout.widget_month_gap4,
        R.layout.widget_month_gap5, R.layout.widget_month_gap6, R.layout.widget_month_gap7,
    )
    private val COLUMNS = intArrayOf(R.id.col0, R.id.col1, R.id.col2, R.id.col3, R.id.col4, R.id.col5, R.id.col6)
    private val WEEKDAYS = intArrayOf(R.id.weekday0, R.id.weekday1, R.id.weekday2, R.id.weekday3, R.id.weekday4, R.id.weekday5, R.id.weekday6)

    suspend fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        update(context, manager.getAppWidgetIds(ComponentName(context, MonthWidgetReceiver::class.java)))
    }

    suspend fun update(context: Context, ids: IntArray) {
        if (ids.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context)
        val firstDay = WidgetData.firstDayOfWeek(context)
        val loaded = HashMap<Int, List<EventInstance>>() // by month shown: widgets on the same month read the data once
        for (id in ids) {
            val offset = MonthWidgetState.offset(context, id)
            val month = YearMonth.now().plusMonths(offset.toLong())
            val instances = loaded.getOrPut(offset) {
                try {
                    val (from, to) = MonthModel.window(month, firstDay)
                    WidgetData.instances(context, from, to)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    emptyList()
                }
            }
            val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                responsive(context, id, month, instances, firstDay)
            } else {
                build(context, id, month, grid(month, instances, firstDay, sizeDp(context, manager, id).second), firstDay)
            }
            manager.updateAppWidget(id, views)
        }
    }

    /** The size of the widget in dp, from its options: the width and height it has with the phone as it is held now. */
    private fun sizeDp(context: Context, manager: AppWidgetManager, id: Int): Pair<Float, Float> {
        val options = manager.getAppWidgetOptions(id)
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val width = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val height = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        return (if (width > 0) width.toFloat() else 320f) to (if (height > 0) height.toFloat() else 400f)
    }

    /**
     * The grid for a widget [heightDp] high: the weeks that have a day of the month (4 to 6), with the days of the months before and
     * after in the first and the last one, each with the room of the grid divided among them and the rows of events that fit in it.
     */
    private fun grid(month: YearMonth, instances: List<EventInstance>, firstDay: DayOfWeek, heightDp: Float): MonthGrid {
        val weekHeight = (heightDp - 2 * PADDING_DP - HEADER_DP - WEEKDAYS_DP).coerceAtLeast(0f) / MonthModel.weeksFor(month, firstDay)
        return MonthModel.build(
            month, instances, firstDay,
            MonthModel.slotsFor(weekHeight, DAY_ROW_DP, SMALL_ROW_DP),
            LaneHeights(weekHeight - DAY_ROW_DP, BAR_ROW_DP, SMALL_ROW_DP),
        )
    }

    /**
     * From Android 12 the widget gives the launcher a drawing for each height at which the rows of events change, and the launcher picks
     * the one that fits the size it really gave the widget: the sizes a launcher reports are not always the real ones, and with them the
     * rows that fit could not be told.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun responsive(context: Context, id: Int, month: YearMonth, instances: List<EventInstance>, firstDay: DayOfWeek): RemoteViews {
        // The heights at which what is drawn changes (a drawing is some 30KB). Android takes at most MAX_RENDITIONS: if there are more,
        // they are spread over the heights (a smaller drawing than the best one fits too).
        val steps = ArrayList<Pair<Float, MonthGrid>>()
        var previous: List<Triple<Int, Int, Int>>? = null
        var height = MIN_HEIGHT_DP
        while (height <= MAX_HEIGHT_DP) {
            val grid = grid(month, instances, firstDay, height)
            // What is drawn, not the rows there are room for: a taller widget with nothing more to show is the same drawing.
            val drawn = grid.weeks.map { Triple(it.pieces.size, it.hidden.sum(), if (it.hidden.sum() > 0) it.rows else 0) }
            if (drawn != previous) {
                steps.add(height to grid)
                previous = drawn
            }
            height += STEP_DP
        }
        val chosen = if (steps.size <= MAX_RENDITIONS) steps else (0 until MAX_RENDITIONS).map { steps[it * (steps.size - 1) / (MAX_RENDITIONS - 1)] }
        val renditions = LinkedHashMap<SizeF, RemoteViews>()
        for ((h, grid) in chosen) renditions[SizeF(MIN_WIDTH_DP, h)] = build(context, id, month, grid, firstDay)
        return RemoteViews(renditions)
    }

    private fun build(context: Context, id: Int, month: YearMonth, grid: MonthGrid, firstDay: DayOfWeek): RemoteViews {
        val pkg = context.packageName
        val locale = Locale.getDefault()
        val today = LocalDate.now()

        val root = RemoteViews(pkg, R.layout.widget_month)
        // The launcher may apply this on the views it already has: without this the weeks would be added once more each time.
        root.removeAllViews(R.id.grid)
        val name = month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }
        root.setTextViewText(R.id.title, if (month.year == YearMonth.from(today).year) name else "$name ${month.year}")
        root.setOnClickPendingIntent(R.id.title, broadcast(context, id, MonthWidgetReceiver.ACTION_TODAY, 0))
        root.setOnClickPendingIntent(R.id.previous, broadcast(context, id, MonthWidgetReceiver.ACTION_STEP, -1))
        root.setOnClickPendingIntent(R.id.next, broadcast(context, id, MonthWidgetReceiver.ACTION_STEP, 1))
        root.setOnClickPendingIntent(R.id.add, activity(context, WidgetIntents.newEvent(context, null)))
        root.setContentDescription(R.id.previous, context.getString(R.string.widget_previous_month))
        root.setContentDescription(R.id.next, context.getString(R.string.widget_next_month))
        root.setContentDescription(R.id.add, context.getString(R.string.widget_add_event))
        grid.weeks.first().days.forEachIndexed { i, day ->
            root.setTextViewText(WEEKDAYS[i], day.dayOfWeek.getDisplayName(TextStyle.NARROW_STANDALONE, locale).uppercase(locale))
        }

        // A tap on a day, or on one of its bars, opens the day view of the app: one pending intent for each day.
        val dayIntents = HashMap<LocalDate, PendingIntent>()
        fun dayIntent(day: LocalDate) = dayIntents.getOrPut(day) { activity(context, WidgetIntents.openDay(context, day)) }
        val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)

        for (week in grid.weeks) {
            val rv = RemoteViews(pkg, R.layout.widget_month_week)
            week.days.forEachIndexed { i, day ->
                rv.setOnClickPendingIntent(COLUMNS[i], dayIntent(day))
                rv.setContentDescription(COLUMNS[i], dateFormat.format(day))
                val layout = when {
                    day == today -> R.layout.widget_month_day_today
                    day.month == month.month && day.year == month.year -> R.layout.widget_month_day_in
                    else -> R.layout.widget_month_day_out
                }
                val cell = RemoteViews(pkg, layout)
                cell.setTextViewText(R.id.day_text, day.dayOfMonth.toString())
                rv.addView(R.id.day_row, cell)
            }
            var empty = 0 // empty rows between rows with events take their height
            for (lane in 0 until week.rows) {
                val items = MonthModel.laneItems(week, lane)
                if (items.isEmpty()) {
                    empty++
                    continue
                }
                repeat(empty) { rv.addView(R.id.lanes, RemoteViews(pkg, R.layout.widget_month_empty_lane)) }
                empty = 0
                val laneViews = RemoteViews(pkg, R.layout.widget_month_lane)
                var column = 0
                for (item in items) {
                    if (item.start > column) laneViews.addView(R.id.lane_root, RemoteViews(pkg, GAPS[item.start - column - 1]))
                    laneViews.addView(R.id.lane_root, chip(pkg, item, dayIntent(week.days[item.start])))
                    column = item.end
                }
                rv.addView(R.id.lanes, laneViews)
            }
            root.addView(R.id.grid, rv)
        }
        return root
    }

    /** A bar of an event (the color of its calendar, taller for an all-day or multi-day one), or the "+N" of a day. */
    private fun chip(pkg: String, item: MonthModel.LaneItem, tap: PendingIntent): RemoteViews {
        val event = item.event
        if (event == null) {
            return RemoteViews(pkg, R.layout.widget_month_more).apply {
                setTextViewText(R.id.chip_text, "+${item.more}")
                setOnClickPendingIntent(R.id.chip_root, tap)
            }
        }
        val color = WidgetColors.parse(event.colorHex)
        val layouts = if (event.isBar) TALL else SMALL
        return RemoteViews(pkg, layouts[item.end - item.start - 1]).apply {
            setInt(R.id.chip_bg, "setColorFilter", color.toArgb())
            setTextViewText(R.id.chip_text, event.title.ifBlank { "–" })
            setTextColor(R.id.chip_text, WidgetColors.textOn(color).toArgb())
            setOnClickPendingIntent(R.id.chip_root, tap)
        }
    }

    private fun activity(context: Context, intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun broadcast(context: Context, id: Int, action: String, step: Int): PendingIntent {
        val intent = Intent(context, MonthWidgetReceiver::class.java).setAction(action)
            .putExtra(MonthWidgetReceiver.EXTRA_WIDGET_ID, id)
            .putExtra(MonthWidgetReceiver.EXTRA_STEP, step)
            .setData(Uri.parse("ggcalendar://widget/month/$id/$action/$step"))
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
