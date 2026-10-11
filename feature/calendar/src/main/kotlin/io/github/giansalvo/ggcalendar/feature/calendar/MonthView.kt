package io.github.giansalvo.ggcalendar.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.style.TextOverflow
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
private const val DAY_HEADER_DP = 26

/** Mese: six-week grids that swipe horizontally; events as colored chips with truncated titles, then "+N". */
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
    val firstDay = firstDayOfWeek()
    val first = month.atDay(1)
    val gridStart = first.minusDays(((first.dayOfWeek.value - firstDay.value + 7) % 7).toLong())
    val gridEnd = gridStart.plusDays(42)
    val byDay = remember(month, instances) {
        val map = HashMap<LocalDate, MutableList<EventInstance>>()
        instances.forEach { i -> i.daysIn(gridStart, gridEnd).forEach { map.getOrPut(it) { mutableListOf() } += i } }
        map.mapValues { (_, list) -> list.sortedWith(compareByDescending<EventInstance> { it.allDay || it.endDay > it.startDay.plusDays(1) }.thenBy { it.begin }) }
    }
    val today = LocalDate.now()

    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        val cellHeight = maxHeight / 6
        val slots = ((cellHeight.value - DAY_HEADER_DP) / (CHIP_HEIGHT_DP + 2)).toInt().coerceAtLeast(1)
        Column(Modifier.fillMaxSize()) {
            (0 until 6).forEach { week ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    (0 until 7).forEach { d ->
                        val day = gridStart.plusDays((week * 7 + d).toLong())
                        DayCell(
                            day = day,
                            inMonth = YearMonth.from(day) == month,
                            isToday = day == today,
                            events = byDay[day].orEmpty(),
                            slots = slots,
                            onClick = { onDayClick(day) },
                            modifier = Modifier.weight(1f).fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    events: List<EventInstance>,
    slots: Int,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier
            .padding(1.5.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp)
            .alpha(if (inMonth) 1f else 0.5f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
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
        val shown = if (events.size > slots) slots - 1 else events.size
        events.take(shown).forEach { EventChip(it) }
        if (events.size > shown) {
            Text(
                "+${events.size - shown}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 3.dp),
            )
        }
    }
}

@Composable
private fun EventChip(instance: EventInstance) {
    val background = parseColor(instance.colorHex)
    Text(
        instance.title.ifBlank { "…" },
        fontSize = 11.sp,
        lineHeight = 13.sp,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        color = contentColorOn(background),
        modifier = Modifier
            .fillMaxWidth()
            .height(CHIP_HEIGHT_DP.dp)
            .background(background, RoundedCornerShape(4.dp))
            .padding(horizontal = 3.dp, vertical = 2.dp),
    )
}
