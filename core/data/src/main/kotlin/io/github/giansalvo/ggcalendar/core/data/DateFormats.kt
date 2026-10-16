package io.github.giansalvo.ggcalendar.core.data

import android.icu.text.DateIntervalFormat
import android.icu.util.DateInterval
import android.icu.util.TimeZone
import android.text.format.DateFormat
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Dates written the way the language of the app writes them ("ven 16 ott" in Italian, "Fri, Oct 16" in American English),
 * from a skeleton such as "EEEdMMM" instead of a fixed pattern. A formatter is built once per skeleton and language, so
 * that asking again while a list scrolls costs a lookup, and a formatter never stays in the language it was first built for.
 */
object DateFormats {
    private val formatters = ConcurrentHashMap<Pair<String, Locale>, DateTimeFormatter>()
    private val intervals = ConcurrentHashMap<Pair<String, Locale>, DateIntervalFormat>()

    /** The formatter for [skeleton] (letters of the fields wanted, in any order: "EEEEdMMM", "EEEdMMMy", "dMMM"). */
    fun of(skeleton: String, locale: Locale = Locale.getDefault()): DateTimeFormatter =
        formatters.getOrPut(skeleton to locale) {
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
        }

    /** "12–18 Oct" or "28 Oct – 3 Nov": the days from [first] to [last] (inclusive), with the month once when it is the same. */
    fun days(first: LocalDate, last: LocalDate, locale: Locale = Locale.getDefault()): String {
        val format = intervals.getOrPut("MMMd" to locale) {
            // The days are whole dates: counting them in UTC keeps the time zone of the phone out of it.
            DateIntervalFormat.getInstance("MMMd", locale).also { it.timeZone = TimeZone.GMT_ZONE }
        }
        fun millis(day: LocalDate) = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val text = synchronized(format) { format.format(DateInterval(millis(first), millis(last))) }
        // Some Android versions capitalize the month at the start of the line ("19–25 Ott"); the other formats here do not,
        // so the month is put back as the formatter of the language writes it in the middle of a line.
        val month = DateTimeFormatter.ofPattern("MMM", locale) // the month inside a line ("MMM", not the stand-alone "LLL")
        return listOf(first, last).map { month.format(it) }.distinct().fold(text) { acc, m ->
            acc.replace(Regex(Regex.escape(m), RegexOption.IGNORE_CASE), m)
        }
    }
}
