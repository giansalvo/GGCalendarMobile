package io.github.giansalvo.ggcalendar.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One VEVENT to write. [exdates] are the excluded occurrences of a series as stored by the app (ISO instants for timed
 * series, ISO dates for all-day ones); [recurrenceId] is the same kind of value and marks a modified occurrence, which
 * keeps the [uid] of its series.
 */
data class IcsEvent(
    val uid: String,
    val title: String,
    val time: EventTime,
    val location: String = "",
    val description: String = "",
    val rrule: String? = null,
    val exdates: List<String> = emptyList(),
    val recurrenceId: String? = null,
    val organizerEmail: String? = null,
    val attendees: List<Attendee> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val meetLink: String? = null,
    val busy: Boolean = true,
    val visibility: Visibility = Visibility.DEFAULT,
    /** The links written as ATTACH (https only, at most 25); never the files. */
    val attachments: List<EventAttachment> = emptyList(),
)

/**
 * RFC 5545 writer for `.ics` files (sharing and export). Timed events that do not repeat are written in UTC; a series
 * (and its modified occurrences) keeps its time zone with `TZID`, so that every occurrence stays at the same local time
 * across DST changes. No VTIMEZONE component is written: Google Calendar, Apple and Outlook resolve the IANA name.
 * Lines end with CRLF and are folded at 75 bytes (UTF-8), never inside a character.
 */
object IcsWriter {
    private val utcStamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val localStamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val dateStamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun write(events: List<IcsEvent>, now: Instant = Instant.now(), productId: String = "-//GGCalendar//IT"): String {
        val out = StringBuilder()
        line(out, "BEGIN:VCALENDAR")
        line(out, "VERSION:2.0")
        line(out, "PRODID:$productId")
        line(out, "CALSCALE:GREGORIAN")
        events.forEach { event(out, it, now) }
        line(out, "END:VCALENDAR")
        return out.toString()
    }

    private fun event(out: StringBuilder, e: IcsEvent, now: Instant) {
        line(out, "BEGIN:VEVENT")
        line(out, "UID:" + clean(e.uid))
        line(out, "DTSTAMP:" + utcStamp.format(now))
        val zone = zoneOf(e)
        e.recurrenceId?.let { line(out, recurrenceId(e, zone, it)) }
        when (val t = e.time) {
            is EventTime.AllDay -> {
                line(out, "DTSTART;VALUE=DATE:" + dateStamp.format(t.startDay))
                line(out, "DTEND;VALUE=DATE:" + dateStamp.format(t.endDay))
            }
            is EventTime.Timed -> {
                line(out, timed("DTSTART", t.start, zone))
                line(out, timed("DTEND", t.end, zone))
            }
        }
        line(out, "SUMMARY:" + escape(e.title))
        if (e.location.isNotBlank()) line(out, "LOCATION:" + escape(e.location))
        val description = description(e)
        if (description.isNotEmpty()) line(out, "DESCRIPTION:" + escape(description))
        e.meetLink?.let { line(out, "URL:" + clean(it)) }
        e.attachments.filter { it.canOpen }.distinctBy { it.url }.take(AttachmentLink.MAX_COUNT).forEach { line(out, attach(it)) }
        e.rrule?.takeIf { it.isNotBlank() }?.let { rule ->
            line(out, "RRULE:" + rule.removePrefix("RRULE:"))
            e.exdates.forEach { line(out, exdate(e, zone, it)) }
        }
        line(out, "TRANSP:" + if (e.busy) "OPAQUE" else "TRANSPARENT")
        when (e.visibility) {
            Visibility.PRIVATE -> line(out, "CLASS:PRIVATE")
            Visibility.PUBLIC -> line(out, "CLASS:PUBLIC")
            Visibility.DEFAULT -> Unit
        }
        e.organizerEmail?.let { line(out, "ORGANIZER:mailto:" + clean(it)) }
        e.attendees.forEach { line(out, attendee(it)) }
        // Email reminders are sent by Google, not by the calendar that opens the file: they have no VALARM.
        e.reminders.filter { it.method != ReminderMethod.EMAIL }.forEach { alarm(out, e, it) }
        line(out, "END:VEVENT")
    }

    /** The zone a timed series is written in; null means UTC (a single timed event). */
    private fun zoneOf(e: IcsEvent): ZoneId? {
        val t = e.time as? EventTime.Timed ?: return null
        if (e.rrule.isNullOrBlank() && e.recurrenceId == null) return null
        return t.zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() }
    }

    private fun timed(name: String, instant: Instant, zone: ZoneId?): String =
        if (zone == null) "$name:" + utcStamp.format(instant)
        else "$name;TZID=${zone.id}:" + localStamp.format(instant.atZone(zone))

    private fun recurrenceId(e: IcsEvent, zone: ZoneId?, value: String): String = when (e.time) {
        is EventTime.AllDay -> "RECURRENCE-ID;VALUE=DATE:" + dateStamp.format(LocalDate.parse(value))
        is EventTime.Timed -> timed("RECURRENCE-ID", Instant.parse(value), zone)
    }

    private fun exdate(e: IcsEvent, zone: ZoneId?, value: String): String = when (e.time) {
        is EventTime.AllDay -> "EXDATE;VALUE=DATE:" + dateStamp.format(LocalDate.parse(value))
        is EventTime.Timed -> timed("EXDATE", Instant.parse(value), zone)
    }

    private fun description(e: IcsEvent): String {
        val meet = e.meetLink?.takeIf { it.isNotBlank() && !e.description.contains(it) }
        return listOfNotNull(e.description.trim().takeIf { it.isNotEmpty() }, meet?.let { "Google Meet: $it" }).joinToString("\n")
    }

    /** `ATTACH;FMTTYPE=type;FILENAME=name:address`: the type and the name only when there are; a name with separators goes in quotes. */
    private fun attach(a: EventAttachment): String {
        val params = StringBuilder()
        a.mimeType?.takeIf { it.isNotBlank() }?.let { params.append(";FMTTYPE=").append(param(it)) }
        a.title.takeIf { it.isNotBlank() }?.let { params.append(";FILENAME=").append(param(it)) }
        return "ATTACH$params:" + clean(a.url)
    }

    private fun attendee(a: Attendee): String {
        val params = StringBuilder()
        a.displayName?.takeIf { it.isNotBlank() }?.let { params.append(";CN=").append(param(it)) }
        params.append(";ROLE=").append(if (a.optional) "OPT-PARTICIPANT" else "REQ-PARTICIPANT")
        when (a.response) {
            "accepted" -> params.append(";PARTSTAT=ACCEPTED")
            "declined" -> params.append(";PARTSTAT=DECLINED")
            "tentative" -> params.append(";PARTSTAT=TENTATIVE")
            else -> params.append(";PARTSTAT=NEEDS-ACTION")
        }
        return "ATTENDEE$params:mailto:" + clean(a.email)
    }

    private fun alarm(out: StringBuilder, e: IcsEvent, r: Reminder) {
        line(out, "BEGIN:VALARM")
        line(out, "ACTION:" + if (r.method == ReminderMethod.ALARM) "AUDIO" else "DISPLAY")
        line(out, "TRIGGER:" + trigger(r.minutes))
        if (r.method != ReminderMethod.ALARM) line(out, "DESCRIPTION:" + escape(e.title.ifBlank { "Reminder" }))
        line(out, "END:VALARM")
    }

    /** "N minutes before the start" as an ISO 8601 duration; whole days are written as days. */
    internal fun trigger(minutes: Int): String = when {
        minutes <= 0 -> "PT0S"
        minutes % (24 * 60) == 0 -> "-P${minutes / (24 * 60)}D"
        else -> "-PT${minutes}M"
    }

    /** Text values: backslash, semicolon, comma and line breaks are escaped. */
    internal fun escape(s: String): String =
        clean(s).replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")

    /** Parameter values containing separators go in quotes (a double quote cannot be written: it is dropped). */
    private fun param(s: String): String {
        val v = clean(s).replace("\"", "")
        return if (v.any { it == ',' || it == ';' || it == ':' }) "\"$v\"" else v
    }

    /** Removes control characters other than line breaks (those are handled by [escape] and [line]). */
    private fun clean(s: String): String = s.filter { it == '\n' || it == '\r' || it >= ' ' }

    private fun line(out: StringBuilder, text: String) {
        // escape() already turned the line breaks of text values into backslash-n: any other one would break the file.
        val single = text.replace('\r', ' ').replace('\n', ' ')
        var budget = 75
        var used = 0
        var i = 0
        while (i < single.length) {
            val cp = single.codePointAt(i)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (used + size > budget) {
                out.append("\r\n ")
                used = 1
                budget = 75
            }
            out.appendCodePoint(cp)
            used += size
            i += Character.charCount(cp)
        }
        out.append("\r\n")
    }
}
