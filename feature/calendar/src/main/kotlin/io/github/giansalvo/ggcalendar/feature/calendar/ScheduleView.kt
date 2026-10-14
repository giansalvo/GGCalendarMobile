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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.giansalvo.ggcalendar.core.model.EventInstance
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private sealed interface ScheduleItem {
    val key: String
    val day: LocalDate

    data class WeekHeader(val start: LocalDate) : ScheduleItem {
        override val key = "w$start"
        override val day = start
    }

    data class Event(override val day: LocalDate, val instance: EventInstance, val firstOfDay: Boolean) : ScheduleItem {
        override val key = "e$day${instance.eventId}${instance.begin}"
    }

    data class NowLine(override val day: LocalDate, val firstOfDay: Boolean) : ScheduleItem {
        override val key = "now"
    }

    data class Empty(override val day: LocalDate) : ScheduleItem {
        override val key = "empty$day"
    }
}

/**
 * Programma: days with events in [from, to), as in Google Calendar's schedule (multi-day events on each of their
 * days). It opens on [date] and scrolls endlessly: near an edge it asks for more months with [onReachStart] or
 * [onReachEnd]; while the user scrolls, [onVisibleDay] reports the first day on screen (title of the top bar).
 */
@Composable
internal fun ScheduleView(
    date: LocalDate,
    from: LocalDate,
    to: LocalDate,
    instances: List<EventInstance>,
    onVisibleDay: (LocalDate) -> Unit,
    onReachStart: () -> Unit,
    onReachEnd: () -> Unit,
    onEventClick: (EventInstance) -> Unit,
    modifier: Modifier = Modifier,
    dimPast: Boolean = false,
) {
    val today = LocalDate.now()
    val now = rememberNow()
    val items = remember(from, to, instances) { buildItems(from, to, instances, today, Instant.now()) }
    fun indexOf(day: LocalDate) = items.indexOfFirst { it !is ScheduleItem.WeekHeader && it.day >= day }.let { if (it < 0) items.lastIndex else it }.coerceAtLeast(0)

    val list = rememberLazyListState(initialFirstVisibleItemIndex = indexOf(date))
    // Keep the list on the date while the data loads and when it jumps (Today, date picker), but not on the dates
    // that the list itself reported while the user scrolled.
    var touched by remember { mutableStateOf(false) }
    var reported by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(date, items.size) {
        if (date != reported) list.scrollToItem(indexOf(date))
    }
    val visibleDay by rememberUpdatedState(onVisibleDay)
    val reachStart by rememberUpdatedState(onReachStart)
    val reachEnd by rememberUpdatedState(onReachEnd)
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { if (it) touched = true }
    }
    LaunchedEffect(list, items) {
        snapshotFlow { list.firstVisibleItemIndex }.distinctUntilChanged().collect { index ->
            if (!list.isScrollInProgress) return@collect
            items.getOrNull(index)?.day?.let {
                reported = it
                visibleDay(it)
            }
        }
    }
    LaunchedEffect(list) {
        snapshotFlow { touched && list.firstVisibleItemIndex < 3 }.distinctUntilChanged().collect { if (it) reachStart() }
    }
    LaunchedEffect(list) {
        snapshotFlow {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            touched && last >= list.layoutInfo.totalItemsCount - 5
        }.distinctUntilChanged().collect { if (it) reachEnd() }
    }

    LazyColumn(modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 88.dp)) {
        items(items, key = { it.key }) { item ->
            when (item) {
                is ScheduleItem.WeekHeader -> WeekHeader(item.start)
                is ScheduleItem.Event -> DayRow(item.day, item.firstOfDay, today) { EventCard(item.instance, item.day, dimmed = dimPast && item.instance.end < now) { onEventClick(item.instance) } }
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

private fun buildItems(from: LocalDate, to: LocalDate, instances: List<EventInstance>, today: LocalDate, now: Instant): List<ScheduleItem> {
    val byDay = sortedMapOf<LocalDate, MutableList<EventInstance>>()
    instances.forEach { i -> i.daysIn(from, to).forEach { byDay.getOrPut(it) { mutableListOf() } += i } }
    if (today in from..<to) byDay.getOrPut(today) { mutableListOf() }

    val firstDay = firstDayOfWeek()
    val out = mutableListOf<ScheduleItem>()
    var week: LocalDate? = null
    byDay.forEach { (day, list) ->
        val start = weekStart(day, firstDay)
        if (week != null && start != week) out += ScheduleItem.WeekHeader(start)
        week = start
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
private fun EventCard(instance: EventInstance, day: LocalDate, dimmed: Boolean, onClick: () -> Unit) {
    val background = parseColor(instance.colorHex)
    val content = contentColorOn(background)
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.6f else 1f)
            .background(background, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
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
        if (instance.isBirthday) Icon(Icons.Outlined.Cake, contentDescription = stringResource(R.string.birthday), tint = content)
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
