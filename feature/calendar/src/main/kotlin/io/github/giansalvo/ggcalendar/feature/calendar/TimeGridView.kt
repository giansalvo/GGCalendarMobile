package io.github.giansalvo.ggcalendar.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.giansalvo.ggcalendar.core.model.EventInstance
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

private const val PAGES = 20_000
private const val CENTER = PAGES / 2

const val MIN_HOUR_HEIGHT = 32f
const val MAX_HOUR_HEIGHT = 120f
const val DEFAULT_HOUR_HEIGHT = 48f

private val HOUR_COLUMN = 48.dp
private val BAR_HEIGHT = 20.dp
private val BAR_ROW = 22.dp
private val MIN_EVENT_HEIGHT = 18.dp
private const val PLACEHOLDER_MINUTES = 60

/** A timed event's piece on one day, in minutes from midnight. */
private data class Segment(val instance: EventInstance, val from: Int, val to: Int)

/** An event being dragged: where it started ([dayIndex], [from], [to]) and how far the finger moved, in pixels. */
private data class Drag(val instance: EventInstance, val dayIndex: Int, val from: Int, val to: Int, val resize: Boolean, val dx: Float = 0f, val dy: Float = 0f)

private const val SNAP_MINUTES = 15

/**
 * Giorno, 6 giorni and Settimana: [dayCount] columns of 24 hours that swipe horizontally, as in Google Calendar.
 * The week starts on the locale's first day; the day and 6-day views start from the selected date.
 */
@Composable
internal fun TimeGridView(
    date: LocalDate,
    dayCount: Int,
    instances: List<EventInstance>,
    hourHeight: Float,
    dayStartHour: Int,
    dimPast: Boolean,
    onHourHeightChange: (Float) -> Unit,
    onPageChange: (start: LocalDate, days: Int) -> Unit,
    onDayClick: (LocalDate) -> Unit,
    onEventClick: (EventInstance) -> Unit,
    onCreateAt: (LocalDateTime) -> Unit,
    /** Start of the new event being edited, drawn as "(New event)" until it is saved or discarded. */
    pendingStart: LocalDateTime?,
    /** A drag ended: the event's new start and end. */
    onEventMoved: (EventInstance, Instant, Instant) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstDay = firstDayOfWeek()
    fun aligned(d: LocalDate) = if (dayCount == 7) weekStart(d, firstDay) else d

    var anchor by remember { mutableStateOf(aligned(date)) }
    val pager = rememberPagerState(initialPage = CENTER) { PAGES }
    fun pageStart(page: Int): LocalDate = anchor.plusDays((page - CENTER).toLong() * dayCount)

    // Jump when the date changes from outside (Today, date picker), report the period when the user swipes.
    LaunchedEffect(date) {
        val start = pageStart(pager.currentPage)
        if (date >= start && date < start.plusDays(dayCount.toLong())) return@LaunchedEffect
        if (dayCount == 7) {
            pager.scrollToPage(CENTER + Math.floorDiv(ChronoUnit.DAYS.between(anchor, aligned(date)), 7L).toInt())
        } else {
            anchor = date
            pager.scrollToPage(CENTER)
        }
    }
    val pageChange by rememberUpdatedState(onPageChange)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { pageChange(pageStart(it), dayCount) }
    }

    val now = rememberNow()
    val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
    val density = LocalDensity.current

    // The zoom lives here while pinching and is reported when the gesture ends.
    var hour by remember { mutableFloatStateOf(hourHeight) }
    LaunchedEffect(hourHeight) { hour = hourHeight }
    val hourDp = hour.dp

    // One vertical position for all pages, so that swiping keeps the same hours on screen.
    val sharedScroll = remember {
        val start = aligned(date)
        val showsToday = today >= start && today < start.plusDays(dayCount.toLong())
        val firstHour = if (showsToday) maxOf(now.atZone(ZoneId.systemDefault()).hour - 1, 0) else dayStartHour
        mutableIntStateOf(with(density) { (hourHeight.dp * firstHour).roundToPx() })
    }
    var zoomTarget by remember { mutableStateOf<Int?>(null) }
    var drag by remember { mutableStateOf<Drag?>(null) }
    val moved by rememberUpdatedState(onEventMoved)

    HorizontalPager(pager, modifier.fillMaxSize(), beyondViewportPageCount = 1, userScrollEnabled = drag == null) { page ->
        val start = pageStart(page)
        val days = remember(start) { List(dayCount) { start.plusDays(it.toLong()) } }
        val scroll = rememberScrollState(sharedScroll.intValue)
        val isCurrent = pager.currentPage == page
        LaunchedEffect(isCurrent) {
            if (isCurrent) {
                snapshotFlow { scroll.value }.collect { sharedScroll.intValue = it }
            } else {
                snapshotFlow { sharedScroll.intValue }.collect { if (scroll.value != it) scroll.scrollTo(it) }
            }
        }
        LaunchedEffect(hour) {
            val target = zoomTarget ?: return@LaunchedEffect
            withFrameNanos { } // Let the taller or shorter grid be measured first.
            scroll.scrollTo(target)
        }

        Column(Modifier.fillMaxSize()) {
            DayHeader(days, today, onDayClick)
            AllDayStrip(days, instances, now, dimPast, onEventClick)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            var gridWidth by remember { mutableIntStateOf(0) }
            val hourPx = with(density) { hourDp.toPx() }
            val columnPx = (gridWidth - with(density) { HOUR_COLUMN.toPx() }) / dayCount
            // Snapped move of the drag: minutes and days.
            fun snapped(d: Drag): Pair<Int, Int> {
                val minutes = ((d.dy / hourPx * 60) / SNAP_MINUTES).roundToInt() * SNAP_MINUTES
                val days = if (d.resize || columnPx <= 0) 0 else (d.dx / columnPx).roundToInt().coerceIn(-d.dayIndex, dayCount - 1 - d.dayIndex)
                return minutes to days
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { gridWidth = it.width }
                    .pinchToZoom(
                        onZoom = { factor, focusY ->
                            val old = hour
                            val new = (old * factor).coerceIn(MIN_HOUR_HEIGHT, MAX_HOUR_HEIGHT)
                            if (new != old) {
                                zoomTarget = ((scroll.value + focusY) * new / old - focusY).roundToInt().coerceAtLeast(0)
                                sharedScroll.intValue = zoomTarget!!
                                hour = new
                            }
                        },
                        onEnd = { onHourHeightChange(hour) },
                    )
                    .verticalScroll(scroll),
            ) {
              Row {
                HourLabels(hourDp)
                days.forEachIndexed { index, day ->
                    DayColumn(
                        day = day,
                        instances = instances,
                        hour = hourDp,
                        now = now,
                        isToday = day == today,
                        dimPast = dimPast,
                        showTime = dayCount == 1,
                        placeholder = pendingStart?.takeIf { it.toLocalDate() == day }?.let { it.hour * 60 + it.minute },
                        onEmptyTap = { minute -> onCreateAt(day.atStartOfDay().plusMinutes(minute.toLong())) },
                        onEventClick = onEventClick,
                        dragging = drag?.instance?.takeIf { page == pager.currentPage },
                        onDragStart = { s, resize -> drag = Drag(s.instance, index, s.from, s.to, resize) },
                        onDrag = { dx, dy -> drag = drag?.let { it.copy(dx = it.dx + dx, dy = it.dy + dy) } },
                        onDragEnd = {
                            drag?.let { d ->
                                val (minutes, shiftDays) = snapped(d)
                                if (minutes != 0 || shiftDays != 0) {
                                    val zone = ZoneId.systemDefault()
                                    val begin = d.instance.begin.atZone(zone).plusDays(shiftDays.toLong()).plusMinutes(if (d.resize) 0L else minutes.toLong())
                                    var end = d.instance.end.atZone(zone).plusDays(shiftDays.toLong()).plusMinutes(minutes.toLong())
                                    if (!end.isAfter(begin.plusMinutes(SNAP_MINUTES - 1L))) end = begin.plusMinutes(SNAP_MINUTES.toLong())
                                    moved(d.instance, begin.toInstant(), end.toInstant())
                                }
                            }
                            drag = null
                        },
                        onDragCancel = { drag = null },
                        modifier = Modifier.weight(1f),
                    )
                }
              }
                // The dragged event follows the finger, snapped to quarter hours and columns.
                drag?.takeIf { page == pager.currentPage }?.let { d ->
                    val (minutes, shiftDays) = snapped(d)
                    val from = if (d.resize) d.from else (d.from + minutes).coerceIn(0, 24 * 60 - SNAP_MINUTES)
                    val to = if (d.resize) maxOf(d.to + minutes, d.from + SNAP_MINUTES).coerceAtMost(24 * 60) else from + (d.to - d.from)
                    val columnDp = with(density) { columnPx.toDp() }
                    val color = parseColor(d.instance.colorHex)
                    Box(
                        Modifier
                            .offset(x = HOUR_COLUMN + columnDp * (d.dayIndex + shiftDays), y = hourDp * from / 60)
                            .width(columnDp - 2.dp)
                            .height(maxOf(hourDp * (to - from) / 60, MIN_EVENT_HEIGHT))
                            .shadow(8.dp, RoundedCornerShape(6.dp))
                            .background(color, RoundedCornerShape(6.dp))
                            .border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(6.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        val zone = ZoneId.systemDefault()
                        val label = d.instance.begin.atZone(zone).toLocalDate().atStartOfDay().plusMinutes(from.toLong()).toLocalTime().format(DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))
                        Text("$label ${d.instance.title}", color = contentColorOn(color), fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/** Two-finger pinch: consumed before the scroll sees it, so one finger still scrolls and swipes. */
private fun Modifier.pinchToZoom(onZoom: (factor: Float, focusY: Float) -> Unit, onEnd: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var zoomed = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    val factor = event.calculateZoom()
                    if (factor != 1f) onZoom(factor, event.calculateCentroid().y)
                    event.changes.forEach { it.consume() }
                    zoomed = true
                }
            } while (event.changes.any { it.pressed })
            if (zoomed) onEnd()
        }
    }

@Composable
private fun DayHeader(days: List<LocalDate>, today: LocalDate, onDayClick: (LocalDate) -> Unit) {
    val locale = Locale.getDefault()
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Spacer(Modifier.width(HOUR_COLUMN))
        days.forEach { day ->
            val isToday = day == today
            val color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { onDayClick(day) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).replaceFirstChar { it.titlecase(locale) },
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                )
                Box(
                    Modifier.size(32.dp).then(if (isToday) Modifier.background(MaterialTheme.colorScheme.primary, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        day.dayOfMonth.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** All-day and multi-day events as continuous bars; two rows and "+N" until tapped. */
@Composable
private fun AllDayStrip(days: List<LocalDate>, instances: List<EventInstance>, now: Instant, dimPast: Boolean, onEventClick: (EventInstance) -> Unit) {
    val lanes = remember(days.first(), instances) { Lanes.assign(barSpans(instances, days.first(), days.size)) }
    if (lanes.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val laneCount = lanes.maxOf { it.lane } + 1
    val rows = if (expanded || laneCount <= 2) laneCount else 2
    val (visible, more) = remember(lanes, rows) { Lanes.fit(lanes, days.size, rows) }

    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Box(
            Modifier.width(HOUR_COLUMN).height(BAR_ROW * rows).clickable(enabled = laneCount > 2) { expanded = !expanded },
            contentAlignment = Alignment.TopCenter,
        ) {
            if (laneCount > 2) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(if (expanded) R.string.show_less else R.string.show_more),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        BoxWithConstraints(Modifier.weight(1f).height(BAR_ROW * rows)) {
            val column = maxWidth / days.size
            visible.forEach { l ->
                EventBar(
                    l.span.item,
                    dimmed = dimPast && l.span.item.end < now,
                    onClick = { onEventClick(l.span.item) },
                    modifier = Modifier
                        .offset(x = column * l.span.startCol, y = BAR_ROW * l.lane)
                        .width(column * (l.span.endCol - l.span.startCol) - 2.dp)
                        .height(BAR_HEIGHT),
                )
            }
            more.forEachIndexed { c, n ->
                if (n > 0) {
                    Text(
                        "+$n",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .offset(x = column * c, y = BAR_ROW * (rows - 1))
                            .width(column - 2.dp)
                            .height(BAR_HEIGHT)
                            .clickable { expanded = true }
                            .padding(start = 4.dp, top = 2.dp),
                    )
                }
            }
        }
    }
}

/** A colored chip with a one-line title: all-day strip and month view. */
@Composable
internal fun EventBar(
    instance: EventInstance,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    onClick: (() -> Unit)?,
) {
    val background = parseColor(instance.colorHex)
    val content = contentColorOn(background)
    Row(
        modifier
            .alpha(if (dimmed) 0.6f else 1f)
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (instance.isBirthday) {
            Icon(Icons.Outlined.Cake, contentDescription = stringResource(R.string.birthday), tint = content, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(2.dp))
        }
        Text(
            instance.title.ifBlank { stringResource(R.string.untitled) },
            fontSize = 11.sp,
            lineHeight = 13.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            color = content,
        )
    }
}

@Composable
private fun HourLabels(hour: Dp) {
    val is24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val format = remember(is24) { DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h a") }
    Box(Modifier.width(HOUR_COLUMN).height(hour * 24)) {
        (1 until 24).forEach { h ->
            Text(
                LocalTime.of(h, 0).format(format),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.offset(y = hour * h - 7.dp).width(HOUR_COLUMN - 6.dp),
            )
        }
    }
}

@Composable
private fun DayColumn(
    day: LocalDate,
    instances: List<EventInstance>,
    hour: Dp,
    now: Instant,
    isToday: Boolean,
    dimPast: Boolean,
    showTime: Boolean,
    placeholder: Int?,
    onEmptyTap: (minute: Int) -> Unit,
    onEventClick: (EventInstance) -> Unit,
    dragging: EventInstance?,
    onDragStart: (Segment, resize: Boolean) -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier,
) {
    val zone = ZoneId.systemDefault()
    val placed = remember(day, instances) {
        val segments = instances.filter { !it.isBar }.mapNotNull { i -> i.minutesOn(day, zone)?.let { Segment(i, it.first, it.second) } }
        // Short events are drawn at least MIN_EVENT_HEIGHT tall: give them a quarter of an hour when stacking.
        OverlapLayout.place(segments, { it.from.toLong() }, { maxOf(it.to, it.from + 15).toLong() })
    }
    val cell = MaterialTheme.colorScheme.surfaceContainer
    val currentHour by rememberUpdatedState(hour)
    val tap by rememberUpdatedState(onEmptyTap)

    BoxWithConstraints(
        modifier
            .height(hour * 24)
            .drawBehind {
                val h = hour.toPx()
                val gap = 1.5.dp.toPx()
                val radius = CornerRadius(6.dp.toPx())
                for (i in 0 until 24) {
                    drawRoundRect(cell, Offset(gap, i * h + gap), Size(size.width - 2 * gap, h - 2 * gap), radius)
                }
            }
            .pointerInput(day) {
                detectTapGestures(onTap = { offset ->
                    val minute = (offset.y / currentHour.toPx() * 60).toInt()
                    tap((minute / 15 * 15).coerceIn(0, 24 * 60 - PLACEHOLDER_MINUTES))
                })
            },
    ) {
        val width = maxWidth
        placed.forEach { p ->
            val s = p.item
            EventBlock(
                instance = s.instance,
                day = day,
                showTime = showTime && s.to - s.from >= 45,
                dimmed = dimPast && s.instance.end < now || s.instance == dragging,
                onClick = { onEventClick(s.instance) },
                modifier = Modifier
                    .offset(x = width * p.column / p.columnCount + 1.dp, y = hour * s.from / 60 + 1.dp)
                    .width(width / p.columnCount - 2.dp)
                    .height(maxOf(hour * (s.to - s.from) / 60 - 2.dp, MIN_EVENT_HEIGHT))
                    .then(
                        if (!s.instance.canWrite) Modifier else Modifier.pointerInput(s.instance) {
                            detectDragGesturesAfterLongPress(
                                // From the lower quarter of a block taller than half an hour: change the duration.
                                onDragStart = { at -> onDragStart(s, at.y > size.height * 0.75f && s.to - s.from >= 30) },
                                onDrag = { change, amount ->
                                    change.consume()
                                    onDrag(amount.x, amount.y)
                                },
                                onDragEnd = onDragEnd,
                                onDragCancel = onDragCancel,
                            )
                        },
                    ),
            )
        }
        if (placeholder != null) {
            Box(
                Modifier
                    .offset(x = 1.dp, y = hour * placeholder / 60 + 1.dp)
                    .width(width - 2.dp)
                    .height(hour * PLACEHOLDER_MINUTES / 60 - 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    stringResource(R.string.new_event_placeholder),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        if (isToday) {
            val local = now.atZone(zone).toLocalTime()
            NowLine(Modifier.offset(y = hour * (local.hour * 60 + local.minute) / 60 - 5.dp))
        }
    }
}

@Composable
private fun EventBlock(instance: EventInstance, day: LocalDate, showTime: Boolean, dimmed: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val background = parseColor(instance.colorHex)
    val content = contentColorOn(background)
    Column(
        modifier
            .alpha(if (dimmed) 0.6f else 1f)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Text(
            instance.title.ifBlank { stringResource(R.string.untitled) },
            color = content,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            overflow = TextOverflow.Clip,
        )
        // As in Google Calendar, the narrow columns of the multi-day views show only the title.
        if (showTime) {
            instance.timeRange(day)?.let { Text(it, color = content, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1) }
        }
    }
}

@Composable
private fun NowLine(modifier: Modifier) {
    val color = MaterialTheme.colorScheme.tertiary
    Row(modifier.fillMaxWidth().height(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Box(Modifier.weight(1f).height(2.dp).background(color))
    }
}
