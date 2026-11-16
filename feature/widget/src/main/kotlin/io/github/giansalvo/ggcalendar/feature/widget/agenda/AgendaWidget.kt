package io.github.giansalvo.ggcalendar.feature.widget.agenda

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.Preferences
import androidx.glance.currentState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.giansalvo.ggcalendar.core.model.EventInstance
import io.github.giansalvo.ggcalendar.feature.widget.AgendaDay
import io.github.giansalvo.ggcalendar.feature.widget.AgendaModel
import io.github.giansalvo.ggcalendar.feature.widget.R
import io.github.giansalvo.ggcalendar.feature.widget.REFRESH
import io.github.giansalvo.ggcalendar.feature.widget.WidgetColors
import io.github.giansalvo.ggcalendar.feature.widget.WidgetData
import io.github.giansalvo.ggcalendar.feature.widget.WidgetIntents
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

/** The Agenda widget: today and the next events, a day per row, with a "+" for a new event. */
class AgendaWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val first = load(context, 0L)
        provideContent {
            // Loaded in the composition, keyed on the refresh token of the state: see REFRESH.
            val wanted = currentState<Preferences>()[REFRESH] ?: 0L
            var shown by remember { mutableStateOf(first) }
            LaunchedEffect(wanted) { if (shown.refresh != wanted) shown = load(context, wanted) }
            GlanceTheme { Content(shown.today, shown.days, shown.format) }
        }
    }

    /** The time as the app writes it in its own lists: the short format of the language of the app, not the 12/24 hour switch of the phone. */
    private fun timeFormat(): DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())

    private suspend fun load(context: Context, refresh: Long): AgendaData {
        val today = LocalDate.now()
        val days = try {
            val instances = WidgetData.instances(context, today, today.plusDays(AgendaModel.LOOKAHEAD_DAYS + 1))
            AgendaModel.build(instances, today, Instant.now())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AgendaModel.build(emptyList(), today, Instant.now())
        }
        return AgendaData(refresh, today, days, timeFormat())
    }
}

private class AgendaData(val refresh: Long, val today: LocalDate, val days: List<AgendaDay>, val format: DateTimeFormatter)

class AgendaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AgendaWidget()
}

@Composable
private fun Content(today: LocalDate, days: List<AgendaDay>, format: DateTimeFormatter) {
    val context = LocalContext.current
    val locale = Locale.getDefault()
    val title = today.month.getDisplayName(DateTextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }
    Column(
        GlanceModifier.fillMaxSize().appWidgetBackground().background(GlanceTheme.colors.widgetBackground).cornerRadius(24.dp).padding(12.dp),
    ) {
        LazyColumn(GlanceModifier.fillMaxSize()) {
            // The title is the first row and scrolls away with the days (a widget list cannot tell which row is on top),
            // so a day of another month brings its own month name.
            item(itemId = Long.MIN_VALUE) {
                Column(GlanceModifier.fillMaxWidth()) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                        Text(
                            title,
                            modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(WidgetIntents.openApp(context))),
                            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Medium),
                            maxLines = 1,
                        )
                        AddButton(context.getString(R.string.widget_add_event), WidgetIntents.newEvent(context, null))
                    }
                    Spacer(GlanceModifier.height(8.dp))
                }
            }
            val withMonth = days.mapIndexed { i, d -> d to (if (i == 0) false else YearMonth.from(d.day) != YearMonth.from(days[i - 1].day)) }
            items(withMonth, itemId = { it.first.day.toEpochDay() }) { (day, newMonth) ->
                Column(GlanceModifier.fillMaxWidth()) {
                    if (newMonth) MonthHeader(day.day, today, locale)
                    DayRow(day, format, locale)
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(day: LocalDate, today: LocalDate, locale: Locale) {
    val name = day.month.getDisplayName(DateTextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }
    Text(
        if (day.year != today.year) "$name ${day.year}" else name,
        modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        maxLines = 1,
    )
}

@Composable
private fun AddButton(description: String, intent: android.content.Intent) {
    Box(
        GlanceModifier.width(56.dp).height(32.dp).background(GlanceTheme.colors.primary).cornerRadius(16.dp)
            .semantics { contentDescription = description }
            .clickable(actionStartActivity(intent)),
        contentAlignment = Alignment.Center,
    ) {
        Text("+", style = TextStyle(color = GlanceTheme.colors.onPrimary, fontSize = 22.sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun DayRow(day: AgendaDay, format: DateTimeFormatter, locale: Locale) {
    val context = LocalContext.current
    Row(GlanceModifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Vertical.Top) {
        DayBadge(day, locale)
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.defaultWeight()) {
            if (day.events.isEmpty()) {
                Box(
                    GlanceModifier.fillMaxWidth().height(48.dp).background(GlanceTheme.colors.surfaceVariant).cornerRadius(16.dp)
                        .padding(horizontal = 14.dp).clickable(actionStartActivity(WidgetIntents.newEvent(context, day.day))),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        context.getString(R.string.widget_nothing_scheduled),
                        style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 15.sp),
                        maxLines = 1,
                    )
                }
            } else {
                day.events.forEachIndexed { index, event ->
                    if (index > 0) Spacer(GlanceModifier.height(4.dp))
                    EventCard(event, format)
                }
            }
        }
    }
}

@Composable
private fun DayBadge(day: AgendaDay, locale: Locale) {
    val number = day.day.dayOfMonth.toString()
    if (day.isToday) {
        Box(GlanceModifier.size(48.dp).background(GlanceTheme.colors.primary).cornerRadius(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
                Text(
                    day.day.dayOfWeek.getDisplayName(DateTextStyle.NARROW, locale).uppercase(locale),
                    style = TextStyle(color = GlanceTheme.colors.onPrimary, fontSize = 12.sp, textAlign = TextAlign.Center),
                )
                Text(number, style = TextStyle(color = GlanceTheme.colors.onPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center))
            }
        }
    } else {
        Column(GlanceModifier.width(48.dp), horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
            Text(
                day.day.dayOfWeek.getDisplayName(DateTextStyle.SHORT, locale).trimEnd('.'),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.Center),
                maxLines = 1,
            )
            Text(number, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center))
        }
    }
}

@Composable
private fun EventCard(event: EventInstance, format: DateTimeFormatter) {
    val context = LocalContext.current
    val color = WidgetColors.parse(event.colorHex)
    val text = ColorProvider(WidgetColors.textOn(color))
    val time = AgendaModel.timeRange(event, ZoneId.systemDefault(), format)
    Column(
        GlanceModifier.fillMaxWidth().background(color).cornerRadius(16.dp).padding(horizontal = 14.dp, vertical = 8.dp)
            .clickable(actionStartActivity(WidgetIntents.openEvent(context, event))),
    ) {
        Text(event.title.ifBlank { "–" }, style = TextStyle(color = text, fontSize = 15.sp), maxLines = 1)
        if (time != null) Text(time, style = TextStyle(color = text, fontSize = 13.sp), maxLines = 1)
    }
}
