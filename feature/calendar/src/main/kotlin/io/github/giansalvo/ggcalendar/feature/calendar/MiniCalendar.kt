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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private const val PAGES = 2400
private const val CENTER = PAGES / 2

/**
 * The month that drops down under the top bar when the title is tapped (as in Google Calendar): swipe between
 * months, a dot marks the days with events, a tap jumps to that day. The header opens a grid of years for long jumps.
 * [onMonthChange] reports the month on screen when the user swipes or picks a year; the view follows it.
 */
@Composable
internal fun MiniCalendar(
    selected: LocalDate,
    eventDays: Set<LocalDate>,
    onMonthChange: (YearMonth) -> Unit,
    onPick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    /** The month and year line that opens the grid of years; off where the screen already shows the month. */
    showHeader: Boolean = true,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    val base = remember { YearMonth.from(selected) }
    val pager = rememberPagerState(initialPage = CENTER) { PAGES }
    fun pageOf(month: YearMonth) = CENTER + ChronoUnit.MONTHS.between(base, month).toInt()
    val shown = base.plusMonths((pager.currentPage - CENTER).toLong())
    var choosingYear by remember { mutableStateOf(false) }

    // Follow the selected date when it changes elsewhere (swipe of the view, Today), report the user's swipes.
    val selectedMonth = YearMonth.from(selected)
    LaunchedEffect(selectedMonth, choosingYear) {
        if (!choosingYear && pager.currentPage != pageOf(selectedMonth)) pager.scrollToPage(pageOf(selectedMonth))
    }
    val monthChange by rememberUpdatedState(onMonthChange)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { monthChange(base.plusMonths((it - CENTER).toLong())) }
    }
    val firstDay = firstDayOfWeek()
    val locale = Locale.getDefault()
    val today = LocalDate.now()

    Column(modifier.fillMaxWidth().background(container).padding(horizontal = 8.dp, vertical = 4.dp)) {
        if (showHeader) Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable { choosingYear = !choosingYear }.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${shown.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }} ${shown.year}",
                style = MaterialTheme.typography.titleSmall,
            )
            Icon(
                if (choosingYear) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                contentDescription = stringResource(R.string.choose_year),
            )
        }
        if (choosingYear) {
            YearGrid(
                current = shown.year,
                years = (base.year - 99)..(base.year + 99),
                onPick = { year ->
                    choosingYear = false
                    monthChange(YearMonth.of(year, shown.month))
                },
            )
            return@Column
        }
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

/** Years in four columns, opened on the current one; as tall as the month grid it replaces. */
@Composable
private fun YearGrid(current: Int, years: IntRange, onPick: (Int) -> Unit) {
    val state = rememberLazyGridState(initialFirstVisibleItemIndex = maxOf(current - years.first - 8, 0))
    LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxWidth().height(284.dp), state = state) {
        items(years.toList()) { year ->
            val isCurrent = year == current
            Box(Modifier.padding(4.dp).height(40.dp), contentAlignment = Alignment.Center) {
                Text(
                    year.toString(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isCurrent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .then(if (isCurrent) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier)
                        .clickable { onPick(year) }
                        .padding(vertical = 8.dp),
                )
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
