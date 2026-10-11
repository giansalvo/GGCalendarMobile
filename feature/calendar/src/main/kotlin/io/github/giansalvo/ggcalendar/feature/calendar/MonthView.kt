package io.github.giansalvo.ggcalendar.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.giansalvo.ggcalendar.core.model.EventInstance
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private const val PAGES = 2400
private const val CENTER = PAGES / 2
private val BASE_MONTH: YearMonth = YearMonth.now()

private const val CHIP_HEIGHT_DP = 18
private const val CHIP_ROW_DP = CHIP_HEIGHT_DP + 2
private const val DAY_HEADER_DP = 26

/** Mese: six-week grids that swipe horizontally; events as colored chips with truncated titles, then "+N" per day. */
@Composable
internal fun MonthView(
    date: LocalDate,
    instances: List<EventInstance>,
    onMonthChange: (YearMonth) -> Unit,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val month = YearMonth.from(date)
    val pager = rememberPagerState(initialPage = CENTER + ChronoUnit.MONTHS.between(BASE_MONTH, month).toInt()) { PAGES }

    // Jump when the date changes from outside (Today, date picker), follow the pager when the user swipes.
    LaunchedEffect(month) {
        val target = CENTER + ChronoUnit.MONTHS.between(BASE_MONTH, month).toInt()
        if (pager.currentPage != target) pager.scrollToPage(target)
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { onMonthChange(BASE_MONTH.plusMonths((it - CENTER).toLong())) }
    }

    val firstDay = firstDayOfWeek()
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            (0 until 7).forEach { i ->
                val dow = firstDay.plus(i.toLong())
                Text(
                    dow.getDisplayName(TextStyle.SHORT, Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (dow == LocalDate.now().dayOfWeek) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
            MonthGrid(BASE_MONTH.plusMonths((page - CENTER).toLong()), instances, onDayClick)
        }
    }
}

@Composable
private fun MonthGrid(month: YearMonth, instances: List<EventInstance>, onDayClick: (LocalDate) -> Unit) {
    val gridStart = weekStart(month.atDay(1), firstDayOfWeek())
    val today = LocalDate.now()
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        val cellHeight = maxHeight / 6
        val slots = ((cellHeight.value - DAY_HEADER_DP - 3) / CHIP_ROW_DP).toInt().coerceAtLeast(1)
        Column(Modifier.fillMaxSize()) {
            (0 until 6).forEach { week ->
                WeekRow(
                    start = gridStart.plusDays(week * 7L),
                    month = month,
                    today = today,
                    instances = instances,
                    slots = slots,
                    onDayClick = onDayClick,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}

/** One week: the day cells, then every event as a chip; events of several days are continuous bars. */
@Composable
private fun WeekRow(
    start: LocalDate,
    month: YearMonth,
    today: LocalDate,
    instances: List<EventInstance>,
    slots: Int,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier,
) {
    val lanes = remember(start, instances) {
        val end = start.plusDays(7)
        val spans = instances
            .filter { it.startDay < end && it.endDay > start }
            .sortedWith(compareByDescending<EventInstance> { it.isBar }.thenBy { it.begin })
            .map { Span(it, ChronoUnit.DAYS.between(start, maxOf(it.startDay, start)).toInt(), ChronoUnit.DAYS.between(start, minOf(it.endDay, end)).toInt()) }
        Lanes.assign(spans)
    }
    val (visible, more) = remember(lanes, slots) { Lanes.fit(lanes, 7, slots) }

    BoxWithConstraints(modifier) {
        val column = maxWidth / 7
        Row(Modifier.fillMaxSize()) {
            (0 until 7).forEach { d ->
                val day = start.plusDays(d.toLong())
                DayCell(day, YearMonth.from(day) == month, day == today, { onDayClick(day) }, Modifier.weight(1f).fillMaxSize())
            }
        }
        // The chips are not clickable: a tap goes through to the day cell below.
        visible.forEach { l ->
            val inMonth = YearMonth.from(start.plusDays(l.span.startCol.toLong())) == month ||
                YearMonth.from(start.plusDays(l.span.endCol - 1L)) == month
            EventBar(
                l.span.item,
                onClick = null,
                modifier = Modifier
                    .offset(x = column * l.span.startCol + 3.dp, y = (DAY_HEADER_DP + 1.5 + CHIP_ROW_DP * l.lane).dp)
                    .width(column * (l.span.endCol - l.span.startCol) - 6.dp)
                    .height(CHIP_HEIGHT_DP.dp)
                    .alpha(if (inMonth) 1f else 0.5f),
            )
        }
        more.forEachIndexed { c, n ->
            if (n > 0) {
                Text(
                    "+$n",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .offset(x = column * c + 6.dp, y = (DAY_HEADER_DP + 1.5 + CHIP_ROW_DP * (slots - 1)).dp),
                )
            }
        }
    }
}

@Composable
private fun DayCell(day: LocalDate, inMonth: Boolean, isToday: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .padding(1.5.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .alpha(if (inMonth) 1f else 0.5f),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.fillMaxWidth().height(DAY_HEADER_DP.dp), contentAlignment = Alignment.Center) {
            if (isToday) {
                Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                    Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
                }
            } else {
                Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
