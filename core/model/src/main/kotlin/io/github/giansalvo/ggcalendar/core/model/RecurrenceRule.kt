package io.github.giansalvo.ggcalendar.core.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

/**
 * The RRULEs the editor can show and build (frequency, interval, weekdays, n-th weekday or day of the month, end by
 * date or count), ported from the desktop `RecurrenceRule`. Expansion is done by lib-recur, which supports any RRULE.
 */
data class RecurrenceRule(
    val frequency: Frequency = Frequency.WEEKLY,
    val interval: Int = 1,
    val days: List<DayOfWeek> = emptyList(),
    /** Last day of the series (inclusive). */
    val until: LocalDate? = null,
    val count: Int? = null,
    /** Monthly or yearly "n-th weekday": 2 to TUESDAY = second Tuesday, -1 to FRIDAY = last Friday. */
    val nthWeekday: Pair<Int, DayOfWeek>? = null,
    /** Monthly BYMONTHDAY (negative counts from the end of the month). */
    val monthDay: Int? = null,
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
) {
    /** The RRULE value (without "RRULE:"); a timed series ends at the end of [until] in [zone]. */
    fun toRrule(allDay: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
        val parts = mutableListOf("FREQ=${frequency.name}")
        if (interval > 1) parts += "INTERVAL=$interval"
        if (frequency == Frequency.WEEKLY && days.isNotEmpty()) parts += "BYDAY=" + orderedDays().joinToString(",") { code(it) }
        nthWeekday?.let { (n, day) -> parts += "BYDAY=$n${code(day)}" }
        monthDay?.let { parts += "BYMONTHDAY=$it" }
        if (weekStart != DayOfWeek.MONDAY) parts += "WKST=${code(weekStart)}"
        when {
            count != null -> parts += "COUNT=$count"
            until != null -> parts += "UNTIL=" + untilValue(until, allDay, zone)
        }
        return parts.joinToString(";")
    }

    /** Weekdays from Monday to Sunday, without repetitions. */
    fun orderedDays(): List<DayOfWeek> = days.distinct().sortedBy { it.value }

    companion object {
        private val CODES = mapOf(
            DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE", DayOfWeek.THURSDAY to "TH",
            DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
        )
        private val BY_CODE = CODES.entries.associate { (k, v) -> v to k }
        private val UTC_BASIC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        private fun code(day: DayOfWeek) = CODES.getValue(day)

        private fun untilValue(last: LocalDate, allDay: Boolean, zone: ZoneId): String =
            if (allDay) last.format(DateTimeFormatter.BASIC_ISO_DATE)
            else UTC_BASIC.format(last.plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1))

        /** Parses an RRULE; null when it uses parts this model cannot represent (the editor then keeps it as it is). */
        fun parse(rrule: String?, zone: ZoneId = ZoneId.systemDefault()): RecurrenceRule? {
            if (rrule.isNullOrBlank()) return null
            var rule = RecurrenceRule()
            val days = mutableListOf<DayOfWeek>()
            var hasFrequency = false
            for (part in rrule.removePrefix("RRULE:").split(';').filter { it.isNotBlank() }) {
                val kv = part.split('=', limit = 2)
                if (kv.size != 2) return null
                val value = kv[1].trim().uppercase()
                when (kv[0].trim().uppercase()) {
                    "FREQ" -> {
                        rule = rule.copy(frequency = runCatching { Frequency.valueOf(value) }.getOrNull() ?: return null)
                        hasFrequency = true
                    }
                    "INTERVAL" -> rule = rule.copy(interval = value.toIntOrNull()?.takeIf { it >= 1 } ?: return null)
                    "COUNT" -> rule = rule.copy(count = value.toIntOrNull()?.takeIf { it >= 1 } ?: return null)
                    "UNTIL" -> rule = rule.copy(until = parseUntil(value, zone) ?: return null)
                    "WKST" -> rule = rule.copy(weekStart = BY_CODE[value] ?: return null)
                    "BYMONTHDAY" -> rule = rule.copy(monthDay = value.toIntOrNull()?.takeIf { it != 0 && it in -31..31 } ?: return null)
                    "BYDAY" -> for (raw in value.split(',').filter { it.isNotBlank() }) {
                        val day = BY_CODE[raw.takeLast(2)] ?: return null
                        val ordinal = raw.dropLast(2)
                        if (ordinal.isEmpty()) {
                            days += day
                        } else {
                            val n = ordinal.toIntOrNull()?.takeIf { it != 0 && it in -5..5 } ?: return null
                            rule = rule.copy(nthWeekday = n to day)
                        }
                    }
                    else -> return null
                }
            }
            if (!hasFrequency || (rule.count != null && rule.until != null)) return null
            if (rule.nthWeekday != null && (rule.frequency == Frequency.DAILY || rule.frequency == Frequency.WEEKLY)) return null
            return rule.copy(days = days)
        }

        /** The last day of an UNTIL: a date, or a UTC/floating date-time seen in [zone]. */
        private fun parseUntil(value: String, zone: ZoneId): LocalDate? = runCatching {
            when {
                value.length == 8 -> LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE)
                value.endsWith("Z") -> LocalDateTime.parse(value.dropLast(1), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                    .toInstant(ZoneOffset.UTC).atZone(zone).toLocalDate()
                else -> LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).toLocalDate()
            }
        }.getOrNull()

        /** Replaces COUNT/UNTIL of a raw RRULE with a COUNT, keeping every other part. */
        fun withCount(rrule: String, count: Int): String = (endless(rrule) + "COUNT=$count").joinToString(";")

        /** Replaces COUNT/UNTIL of a raw RRULE so that the series ends at [lastMoment] (split of a series). */
        fun withUntil(rrule: String, lastMoment: Instant, allDay: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
            val value = if (allDay) lastMoment.atZone(zone).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE) else UTC_BASIC.format(lastMoment)
            return (endless(rrule) + "UNTIL=$value").joinToString(";")
        }

        private fun endless(rrule: String): List<String> = rrule.split(';')
            .filter { it.isNotBlank() && !it.startsWith("COUNT=", ignoreCase = true) && !it.startsWith("UNTIL=", ignoreCase = true) }
    }
}
