package io.github.giansalvo.ggcalendar.feature.calendar

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.giansalvo.ggcalendar.core.model.EventInstance
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.WeekFields
import java.util.Locale

internal fun parseColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFF1A73E8))

/** White or near-black text, whichever reads better on [background]. */
internal fun contentColorOn(background: Color): Color = if (background.luminance() > 0.5f) Color(0xFF1F1F1F) else Color.White

internal fun firstDayOfWeek(): DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek

private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** "09:00–10:00", or only the part that falls on [day] for events spanning several days; null for all-day events. */
internal fun EventInstance.timeRange(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String? {
    if (allDay) return null
    val start = begin.atZone(zone)
    val finish = end.atZone(zone)
    val from = if (start.toLocalDate() == day) start.format(timeFormat) else null
    val to = if (finish.toLocalDate() == day) finish.format(timeFormat) else null
    return when {
        from != null && to != null -> "$from–$to"
        from != null -> "$from–"
        to != null -> "–$to"
        else -> null
    }
}

/** The days of [startDay, endDay) of this instance that fall inside [from, to). */
internal fun EventInstance.daysIn(from: LocalDate, to: LocalDate): Sequence<LocalDate> {
    val first = maxOf(startDay, from)
    val last = minOf(endDay, to)
    return generateSequence(first) { it.plusDays(1) }.takeWhile { it < last }
}
