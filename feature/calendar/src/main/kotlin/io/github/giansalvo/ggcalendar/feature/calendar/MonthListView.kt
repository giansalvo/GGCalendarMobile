package io.github.giansalvo.ggcalendar.feature.calendar

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.giansalvo.ggcalendar.core.model.EventInstance
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

/**
 * "Month + list" (mockup `vista_mese_e_lista_giuli`): the month on top as numbers with a dot under the days that have
 * events, the events of the selected day below as cards with start and end time, the calendar color and the title.
 * Creating an event is the app's floating "+", which opens the editor on the selected day.
 */
@Composable
internal fun MonthListView(
    date: LocalDate,
    instances: List<EventInstance>,
    onMonthChange: (YearMonth) -> Unit,
    onDayClick: (LocalDate) -> Unit,
    onEventClick: (EventInstance) -> Unit,
) {
    val eventDays = remember(instances) {
        instances.flatMapTo(HashSet()) { it.daysIn(it.startDay, minOf(it.endDay, it.startDay.plusDays(62))).asIterable() }
    }
    val dayEvents = remember(instances, date) {
        instances.filter { it.startDay <= date && it.endDay > date }.sortedWith(compareByDescending<EventInstance> { it.allDay }.thenBy { it.begin })
    }
    Column(Modifier.fillMaxSize()) {
        MiniCalendar(
            selected = date,
            eventDays = eventDays,
            onMonthChange = onMonthChange,
            onPick = onDayClick,
            showHeader = false,
            container = MaterialTheme.colorScheme.background,
        )
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(40.dp).height(4.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)))
        }
        Text(
            dayTitle(date),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (dayEvents.isEmpty()) {
            Text(
                stringResource(R.string.no_events_day),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        // Room at the end so that the floating button never covers the last card.
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(dayEvents, key = { it.eventId + it.begin.toEpochMilli() }) { EventCard(it, onClick = { onEventClick(it) }) }
        }
    }
}

@Composable
private fun EventCard(instance: EventInstance, onClick: () -> Unit) {
    val context = LocalContext.current
    val time = remember(context) { DateFormat.getTimeFormat(context) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(64.dp)) {
            if (instance.allDay) {
                Text(stringResource(R.string.all_day), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(time.format(Date(instance.begin.toEpochMilli())), style = MaterialTheme.typography.bodyLarge)
                Text(time.format(Date(instance.end.toEpochMilli())), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box(Modifier.width(4.dp).height(40.dp).background(parseColor(instance.colorHex), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                instance.title.ifBlank { stringResource(R.string.untitled) },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (instance.location.isNotBlank()) {
                Text(
                    instance.location,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "Today", "Tomorrow", "In 4 days"... followed by the date, over the list. */
@Composable
private fun dayTitle(date: LocalDate): String {
    val days = ChronoUnit.DAYS.between(LocalDate.now(), date).toInt()
    val relative = when {
        days == 0 -> stringResource(R.string.day_today)
        days == 1 -> stringResource(R.string.day_tomorrow)
        days == -1 -> stringResource(R.string.day_yesterday)
        days > 1 -> pluralStringResource(R.plurals.days_from_now, days, days)
        else -> pluralStringResource(R.plurals.days_ago, -days, -days)
    }
    val locale = Locale.getDefault()
    return relative + " · " + date.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
}
