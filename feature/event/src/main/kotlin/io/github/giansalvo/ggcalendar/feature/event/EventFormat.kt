package io.github.giansalvo.ggcalendar.feature.event

import android.content.res.Resources
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.giansalvo.ggcalendar.core.model.Frequency
import io.github.giansalvo.ggcalendar.core.model.Reminder
import io.github.giansalvo.ggcalendar.core.model.ReminderMethod
import io.github.giansalvo.ggcalendar.core.model.RecurrenceRule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

internal fun parseColor(hex: String?): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFF1A73E8))

internal fun contentColorOn(background: Color): Color = if (background.luminance() > 0.5f) Color(0xFF1F1F1F) else Color.White

private fun String.capitalized(locale: Locale = Locale.getDefault()) = replaceFirstChar { it.titlecase(locale) }

internal val timeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMM")
private val shortDayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
internal val longDateFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/** "Mercoledì 7 ott • 05:00–06:00", "Lun 12 ott 22:00 – Mar 13 ott 01:00", "Mercoledì 7 ott" or "Lun 12 – Mer 14 ott". */
internal fun whenText(allDay: Boolean, begin: Instant, end: Instant, startDay: LocalDate, endDay: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
    if (allDay) {
        val last = endDay.minusDays(1)
        return if (last <= startDay) startDay.format(dayFormat).capitalized()
        else "${startDay.format(shortDayFormat).capitalized()} – ${last.format(shortDayFormat).capitalized()}"
    }
    val from = begin.atZone(zone)
    val to = end.atZone(zone)
    return if (from.toLocalDate() == to.toLocalDate() || (to.toLocalTime().toSecondOfDay() == 0 && to.toLocalDate() == from.toLocalDate().plusDays(1))) {
        "${from.format(dayFormat).capitalized()} • ${from.format(timeFormat)}–${to.format(timeFormat)}"
    } else {
        "${from.format(shortDayFormat).capitalized()} ${from.format(timeFormat)} – ${to.format(shortDayFormat).capitalized()} ${to.format(timeFormat)}"
    }
}

/** "Ogni 2 settimane il lunedì, giovedì; 5 volte"; null for rules the model cannot describe. */
internal fun describe(rrule: String?, res: Resources, locale: Locale = Locale.getDefault()): String? {
    val rule = RecurrenceRule.parse(rrule) ?: return null
    val unit = when (rule.frequency) {
        Frequency.DAILY -> R.plurals.every_day
        Frequency.WEEKLY -> R.plurals.every_week
        Frequency.MONTHLY -> R.plurals.every_month
        Frequency.YEARLY -> R.plurals.every_year
    }
    val text = StringBuilder(res.getQuantityString(unit, rule.interval, rule.interval))
    if (rule.frequency == Frequency.WEEKLY && rule.days.isNotEmpty()) {
        text.append(res.getString(R.string.on_days, rule.orderedDays().joinToString(", ") { it.getDisplayName(TextStyle.FULL, locale) }))
    }
    rule.monthDay?.let { text.append(res.getString(R.string.on_month_day, it)) }
    rule.nthWeekday?.let { (n, day) ->
        val ordinals = res.getStringArray(R.array.ordinals)
        text.append(res.getString(R.string.on_nth_weekday, ordinals[if (n < 0) 0 else n.coerceAtMost(5)], day.getDisplayName(TextStyle.FULL, locale)))
    }
    rule.count?.let { text.append(res.getQuantityString(R.plurals.times, it, it)) }
    rule.until?.let { text.append(res.getString(R.string.until_date, it.format(longDateFormat))) }
    return text.toString()
}

/** "10 minuti prima", "1 ora prima", "2 giorni prima", "1 settimana prima". */
internal fun beforeText(minutes: Int, res: Resources): String = when {
    minutes <= 0 -> res.getString(R.string.at_start)
    minutes % 10080 == 0 -> res.getQuantityString(R.plurals.weeks_before, minutes / 10080, minutes / 10080)
    minutes % 1440 == 0 -> res.getQuantityString(R.plurals.days_before, minutes / 1440, minutes / 1440)
    minutes % 60 == 0 -> res.getQuantityString(R.plurals.hours_before, minutes / 60, minutes / 60)
    else -> res.getQuantityString(R.plurals.minutes_before, minutes, minutes)
}

internal fun methodText(reminder: Reminder, res: Resources): String = res.getString(
    when (reminder.method) {
        ReminderMethod.POPUP -> R.string.reminder_popup
        ReminderMethod.ALARM -> R.string.reminder_alarm
        ReminderMethod.EMAIL -> R.string.reminder_email
    },
)
