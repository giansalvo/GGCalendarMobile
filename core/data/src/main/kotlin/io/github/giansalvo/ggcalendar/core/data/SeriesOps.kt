package io.github.giansalvo.ggcalendar.core.data

import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.model.Frequency
import io.github.giansalvo.ggcalendar.core.model.RecurrenceRule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Which occurrences of a series an edit, a deletion or a drag applies to (mockup 8). */
enum class SeriesScope { THIS, FOLLOWING, ALL }

/** One occurrence of a series as drawn: its start and end, and its day (the start for all-day series). */
data class Occurrence(val beginUtc: Long, val endUtc: Long, val startDay: Long, val allDay: Boolean) {
    /** The EXDATE value of this occurrence, in the form the mapper stores (ISO instant or ISO date). */
    val exdate: String get() = if (allDay) LocalDate.ofEpochDay(startDay).toString() else Instant.ofEpochMilli(beginUtc).toString()
}

/**
 * Pure transformations of a series for the three scopes, ported from the desktop `MainViewModel.SaveOccurrence`,
 * `DeleteOccurrence` and `MoveOccurrence`. [edited] is the occurrence as the user saved it (times included).
 */
object SeriesOps {
    fun isFirst(master: EventEntity, occ: Occurrence): Boolean =
        if (master.allDay) occ.startDay == master.startDay else occ.beginUtc == master.startUtc

    /** "Only this event": a modified occurrence linked to its series (Google patches the series' instance). */
    fun exception(master: EventEntity, occ: Occurrence, edited: EventEntity): EventEntity = edited.copy(
        id = UUID.randomUUID().toString(),
        calendarId = master.calendarId,
        remoteId = null,
        etag = null,
        iCalUid = null,
        rrule = null,
        exdates = emptyList(),
        seriesMasterId = master.id,
        originalStartUtc = if (master.allDay) null else occ.beginUtc,
        originalStartDay = if (master.allDay) occ.startDay else null,
        isCancelled = false,
    )

    data class Shifted(val master: EventEntity, val children: List<EventEntity>)

    /**
     * "All events": the master takes the edit; moving the occurrence moves every slot by the same wall-clock amount,
     * so exclusions, modified occurrences and a weekly rule's weekdays follow.
     */
    fun all(master: EventEntity, occ: Occurrence, edited: EventEntity, children: List<EventEntity>, zone: ZoneId): Shifted {
        val masterDate = startDate(master, zone)
        val occDate = if (occ.allDay) LocalDate.ofEpochDay(occ.startDay) else Instant.ofEpochMilli(occ.beginUtc).atZone(zone(master, zone)).toLocalDate()
        val back = ChronoUnit.DAYS.between(masterDate, occDate)
        // The edited occurrence moved back to the series' first day.
        var updated = moveDays(edited, -back, zone).copy(id = master.id, remoteId = master.remoteId, etag = master.etag, iCalUid = master.iCalUid)
        val sameKind = updated.allDay == master.allDay
        val shiftMs = if (sameKind && !master.allDay) (updated.startUtc ?: 0) - (master.startUtc ?: 0) else 0
        val shiftDays = if (sameKind) ChronoUnit.DAYS.between(masterDate, startDate(updated, zone)) else 0
        val rule = if (edited.rrule == master.rrule) shiftWeekdays(master.rrule, shiftDays, updated.allDay, zone) else edited.rrule
        updated = updated.copy(
            rrule = rule,
            exdates = if (!sameKind) emptyList() else master.exdates.map { shiftExdate(it, shiftMs, shiftDays) },
        )
        val moved = if (!sameKind || (shiftMs == 0L && shiftDays == 0L)) emptyList() else children.map { c ->
            c.copy(
                originalStartUtc = c.originalStartUtc?.plus(shiftMs),
                originalStartDay = c.originalStartDay?.plus(shiftDays),
            )
        }
        return Shifted(updated, moved)
    }

    data class Split(val master: EventEntity, val following: EventEntity)

    /**
     * "This and following events": the series ends the day before [occ] and a new series starts with the edit. With a
     * COUNT, the new series keeps the occurrences still to come ([before] are already past it).
     */
    fun following(master: EventEntity, occ: Occurrence, edited: EventEntity, before: Int, zone: ZoneId): Split {
        val masterRule = master.rrule.orEmpty()
        val newRule = edited.rrule?.let { rule ->
            val total = RecurrenceRule.parse(masterRule)?.count
            if (rule == masterRule && total != null) RecurrenceRule.withCount(rule, maxOf(1, total - before)) else rule
        }
        val tail = master.exdates.filter { !isBefore(it, occ) }
        val following = edited.copy(
            id = UUID.randomUUID().toString(),
            remoteId = null,
            etag = null,
            iCalUid = null,
            seriesMasterId = null,
            originalStartUtc = null,
            originalStartDay = null,
            meetLink = null,
            rrule = newRule,
            exdates = if (edited.allDay == master.allDay) tail else emptyList(),
        )
        return Split(ended(master, occ, zone), following)
    }

    /** "Only this event" deleted: the series skips it. */
    fun deleteThis(master: EventEntity, occ: Occurrence): EventEntity = master.copy(exdates = (master.exdates + occ.exdate).distinct())

    /** "This and following events" deleted: the series ends the day before; null when that leaves nothing (first one). */
    fun deleteFollowing(master: EventEntity, occ: Occurrence, zone: ZoneId): EventEntity? =
        if (isFirst(master, occ)) null else ended(master, occ, zone)

    private fun ended(master: EventEntity, occ: Occurrence, zone: ZoneId): EventEntity {
        val z = zone(master, zone)
        val last = if (master.allDay) {
            LocalDate.ofEpochDay(occ.startDay - 1).atStartOfDay(z).toInstant()
        } else {
            // The end of the previous day, as in the desktop app.
            Instant.ofEpochMilli(occ.beginUtc).atZone(z).toLocalDate().atStartOfDay(z).toInstant().minusSeconds(1)
        }
        return master.copy(
            rrule = RecurrenceRule.withUntil(master.rrule.orEmpty(), last, master.allDay, z),
            exdates = master.exdates.filter { isBefore(it, occ) },
        )
    }

    private fun isBefore(exdate: String, occ: Occurrence): Boolean =
        if ('T' in exdate) Instant.parse(exdate).toEpochMilli() < occ.beginUtc else LocalDate.parse(exdate).toEpochDay() < occ.startDay

    private fun zone(e: EventEntity, fallback: ZoneId): ZoneId = e.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: fallback

    private fun startDate(e: EventEntity, zone: ZoneId): LocalDate =
        if (e.allDay) LocalDate.ofEpochDay(e.startDay ?: 0) else Instant.ofEpochMilli(e.startUtc ?: 0).atZone(zone(e, zone)).toLocalDate()

    /** The same event [days] days later, keeping its wall-clock times. */
    private fun moveDays(e: EventEntity, days: Long, zone: ZoneId): EventEntity {
        if (days == 0L) return e
        if (e.allDay) return e.copy(startDay = e.startDay?.plus(days), endDay = e.endDay?.plus(days))
        val z = zone(e, zone)
        fun shift(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(z).plusDays(days).toInstant().toEpochMilli() }
        return e.copy(startUtc = shift(e.startUtc), endUtc = shift(e.endUtc))
    }

    private fun shiftExdate(exdate: String, ms: Long, days: Long): String =
        if ('T' in exdate) Instant.parse(exdate).plusMillis(ms).toString() else LocalDate.parse(exdate).plusDays(days).toString()

    /** Moving a weekly "on these days" series to another weekday shifts its days too. */
    private fun shiftWeekdays(rrule: String?, days: Long, allDay: Boolean, zone: ZoneId): String? {
        if (rrule == null || days % 7 == 0L) return rrule
        val rule = RecurrenceRule.parse(rrule, zone) ?: return rrule
        if (rule.frequency != Frequency.WEEKLY || rule.days.isEmpty()) return rrule
        return rule.copy(days = rule.days.map { it.plus(days) }).toRrule(allDay, zone)
    }
}
