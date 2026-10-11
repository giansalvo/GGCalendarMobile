package io.github.giansalvo.ggcalendar.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.giansalvo.ggcalendar.core.model.EventInstance
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private sealed interface ScheduleItem {
    val key: String

    data class WeekHeader(val start: LocalDate) : ScheduleItem {
        override val key = "w$start"
    }

    data class Event(val day: LocalDate, val instance: EventInstance, val firstOfDay: Boolean) : ScheduleItem {
        override val key = "e$day${instance.eventId}${instance.begin}"
    }

    data class NowLine(val day: LocalDate, val firstOfDay: Boolean) : ScheduleItem {
        override val key = "now"
    }

    data class Empty(val day: LocalDate) : ScheduleItem {
        override val key = "empty$day"
    }
}

/** Programma: days with events from [from], as in Google Calendar's schedule (multi-day events on each of their days). */
@Composable
internal fun ScheduleView(from: LocalDate, instances: List<EventInstance>, modifier: Modifier = Modifier) {
    val today = LocalDate.now()
    val items = remember(from, instances) { buildItems(from, instances, today, Instant.now()) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
        items(items, key = { it.key }) { item ->
            when (item) {
                is ScheduleItem.WeekHeader -> WeekHeader(item.start)
                is ScheduleItem.Event -> DayRow(item.day, item.firstOfDay, today) { EventCard(item.instance, item.day) }
                is ScheduleItem.NowLine -> DayRow(item.day, item.firstOfDay, today) { NowLine() }
                is ScheduleItem.Empty -> DayRow(item.day, true, today) {
                    Text(
                        stringResource(R.string.no_events_today),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}

private fun buildItems(from: LocalDate, instances: List<EventInstance>, today: LocalDate, now: Instant): List<ScheduleItem> {
    val to = from.plusMonths(4)
    val byDay = sortedMapOf<LocalDate, MutableList<EventInstance>>()
    instances.forEach { i -> i.daysIn(from, to).forEach { byDay.getOrPut(it) { mutableListOf() } += i } }
    if (today in from..<to) byDay.getOrPut(today) { mutableListOf() }

    val firstDay = firstDayOfWeek()
    val out = mutableListOf<ScheduleItem>()
    var week: LocalDate? = null
    byDay.forEach { (day, list) ->
        val weekStart = day.minusDays(((day.dayOfWeek.value - firstDay.value + 7) % 7).toLong())
        if (week != null && weekStart != week) out += ScheduleItem.WeekHeader(weekStart)
        week = weekStart
        val sorted = list.sortedWith(compareByDescending<EventInstance> { it.allDay }.thenBy { it.begin })
        if (sorted.isEmpty()) {
            out += ScheduleItem.Empty(day)
            return@forEach
        }
        var first = true
        var nowShown = day != today
        sorted.forEach { i ->
            if (!nowShown && !i.allDay && i.begin > now) {
                out += ScheduleItem.NowLine(day, first)
                first = false
                nowShown = true
            }
            out += ScheduleItem.Event(day, i, first)
            first = false
        }
        if (!nowShown) out += ScheduleItem.NowLine(day, false)
    }
    return out
}

@Composable
private fun DayRow(day: LocalDate, showDate: Boolean, today: LocalDate, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp)) {
        Box(Modifier.width(56.dp).padding(top = 6.dp), contentAlignment = Alignment.TopCenter) {
            if (showDate) DateLabel(day, day == today)
        }
        Box(Modifier.weight(1f).padding(vertical = 3.dp)) { content() }
    }
}

@Composable
private fun DateLabel(day: LocalDate, isToday: Boolean) {
    val color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) },
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
        if (isToday) {
            Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
            }
        } else {
            Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.titleLarge, color = color)
        }
    }
}

@Composable
private fun EventCard(instance: EventInstance, day: LocalDate) {
    val background = parseColor(instance.colorHex)
    val content = contentColorOn(background)
    Row(
        Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(12.dp))
            .clickable { /* Event details: milestone M3 */ }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                instance.title.ifBlank { stringResource(R.string.untitled) },
                color = content,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                instance.timeRange(day) ?: stringResource(R.string.all_day),
                color = content,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (instance.hasAlarm) Icon(Icons.Outlined.Alarm, contentDescription = stringResource(R.string.alarm), tint = content)
        if (instance.hasMeet) Icon(Icons.Outlined.Videocam, contentDescription = "Google Meet", tint = content)
    }
}

@Composable
private fun NowLine() {
    val color = MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().height(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Box(Modifier.weight(1f).height(2.dp).background(color))
    }
}

private val weekFormat = DateTimeFormatter.ofPattern("d MMM")

@Composable
private fun WeekHeader(start: LocalDate) {
    val end = start.plusDays(6)
    val text = if (start.month == end.month) "${start.dayOfMonth}–${end.format(weekFormat)}" else "${start.format(weekFormat)}–${end.format(weekFormat)}"
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 72.dp, top = 20.dp, bottom = 6.dp),
    )
}
