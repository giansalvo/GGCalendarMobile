package io.github.giansalvo.ggcalendar.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private const val PAGES = 2400
private const val CENTER = PAGES / 2

/**
 * The month that drops down under the top bar when the title is tapped (as in Google Calendar): swipe between
 * months, a dot marks the days with events, a tap jumps to that day. [onMonthChange] reports the month on screen.
 */
@Composable
internal fun MiniCalendar(
    selected: LocalDate,
    eventDays: Set<LocalDate>,
    onMonthChange: (YearMonth) -> Unit,
    onPick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val base = YearMonth.from(selected)
    val pager = rememberPagerState(initialPage = CENTER) { PAGES }
    val monthChange by rememberUpdatedState(onMonthChange)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { monthChange(base.plusMonths((it - CENTER).toLong())) }
    }
    val firstDay = firstDayOfWeek()
    val locale = Locale.getDefault()
    val today = LocalDate.now()

    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            (0 until 7).forEach { i ->
                Text(
                    firstDay.plus(i.toLong()).getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        HorizontalPager(pager, Modifier.fillMaxWidth()) { page ->
            val month = base.plusMonths((page - CENTER).toLong())
            val start = weekStart(month.atDay(1), firstDay)
            // Always six rows, so that the panel keeps its height while swiping.
            Column {
                (0 until 6).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        (0 until 7).forEach { d ->
                            val day = start.plusDays(week * 7L + d)
                            MiniDay(
                                day = day,
                                inMonth = YearMonth.from(day) == month,
                                isToday = day == today,
                                isSelected = day == selected,
                                hasEvents = day in eventDays,
                                onClick = { onPick(day) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniDay(
    day: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    hasEvents: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val (background, content) = when {
        isToday -> colors.primary to colors.onPrimary
        isSelected -> colors.primaryContainer to colors.onPrimaryContainer
        else -> null to colors.onSurface
    }
    Column(
        modifier.height(44.dp).alpha(if (inMonth) 1f else 0.45f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .then(if (background != null) Modifier.background(background) else Modifier)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium, color = content)
        }
        if (hasEvents) Box(Modifier.padding(top = 3.dp).size(4.dp).background(colors.onSurfaceVariant, CircleShape))
    }
}

/** First and last (exclusive) day of the six-week grid of [month]. */
internal fun monthGrid(month: YearMonth): Pair<LocalDate, LocalDate> {
    val start = weekStart(month.atDay(1), firstDayOfWeek())
    return start to start.plusDays(42)
}
