package io.github.giansalvo.ggcalendar.core.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The second time zone of the time grids: a second column of hours next to the one of the phone, with its own short name.
 * Events, days and the database stay in the time zone of the phone; only the labels of the hours are converted, row by row,
 * with the rules of the zone (summer time, half hours), so that the hour of a line is the real hour there on that day.
 */
data class SecondZone(val zoneId: String, val label: String) {
    val zone: ZoneId? get() = runCatching { ZoneId.of(zoneId) }.getOrNull()
}

/** One line of the second column: the hour there, its date, and whether the date is worth writing (first line, or the date changes). */
data class ZoneRow(val time: LocalTime, val date: LocalDate, val showDate: Boolean)

object SecondZones {
    /** The longest name the column has room for. */
    const val MAX_LABEL = 6

    /** Trimmed and cut to [MAX_LABEL] characters (counted as people see them, so an emoji is one). */
    fun clampLabel(text: String): String {
        val t = text.trim()
        val end = t.offsetByCodePoints(0, minOf(MAX_LABEL, t.codePointCount(0, t.length)))
        return t.substring(0, end)
    }

    /** The name offered first: the initials of a city of several words ("New_York" gives "NY"), else the first six letters ("Tokyo"). */
    fun defaultLabel(zoneId: String): String {
        val city = zoneId.substringAfterLast('/').replace('_', ' ').trim()
        val words = city.split(' ', '-').filter { it.isNotEmpty() }
        return if (words.size > 1) clampLabel(words.joinToString("") { it.take(1).uppercase() }) else clampLabel(city)
    }

    /** The name the user typed, or the default when it is empty. */
    fun labelOf(zoneId: String, custom: String?): String = custom?.let(::clampLabel)?.takeIf { it.isNotEmpty() } ?: defaultLabel(zoneId)

    /**
     * The second zone to draw, or null when it is off, unknown to this phone (an old Android may not have the zone) or the same as the phone's.
     */
    fun from(view: ViewPreferences, phone: ZoneId = ZoneId.systemDefault()): SecondZone? {
        val id = view.secondZoneId ?: return null
        if (!view.secondZoneEnabled) return null
        val zone = runCatching { ZoneId.of(id) }.getOrNull() ?: return null
        if (zone == phone) return null
        return SecondZone(id, labelOf(id, view.secondZoneLabel))
    }

    /**
     * The 24 lines of [day] (each at its hour in the phone's zone) read in [second]. Each line is converted on its own, so the
     * answer is right on the days a zone changes its clock, whichever of the two does it, and for zones with half hours.
     */
    fun rows(day: LocalDate, phone: ZoneId, second: ZoneId): List<ZoneRow> {
        var previous: LocalDate? = null
        return (0 until 24).map { h ->
            val instant: Instant = day.atTime(h, 0).atZone(phone).toInstant()
            val there = instant.atZone(second)
            val row = ZoneRow(there.toLocalTime(), there.toLocalDate(), h == 0 || there.toLocalDate() != previous)
            previous = there.toLocalDate()
            row
        }
    }

    /** True when the hours of the two lists are the same line by line: a day column then needs no labels of its own. */
    fun sameHours(a: List<ZoneRow>, b: List<ZoneRow>): Boolean = a.map { it.time } == b.map { it.time }
}
