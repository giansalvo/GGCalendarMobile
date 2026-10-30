package io.github.giansalvo.ggcalendar.core.model

import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

data class IcsImportOptions(
    /** Prefix of every event id: `{calendarId}|{UID}`. */
    val calendarId: String,
    /** The zone of floating times and of unknown time zones (the phone's). */
    val defaultZone: ZoneId = ZoneId.systemDefault(),
    /** Read the relative VALARM of the events (the summary switch); the file's alarms are only counted when off. */
    val importAlarms: Boolean = true,
    /** Write the organizer and the attendees as text in the description. They never become guests. */
    val guestsInDescription: Boolean = false,
)

/**
 * An event read from a file, ready for a destination. [masterId] is set for a modified occurrence of a series in the same file
 * ([recurrenceId] is the original start, an ISO instant for timed series and an ISO date for all-day ones, as the app stores
 * it); an occurrence whose series is not in the file is an ordinary event with its own id.
 */
data class ImportedEvent(
    val id: String,
    val uid: String,
    val masterId: String?,
    val recurrenceId: String?,
    val title: String,
    val location: String,
    val description: String,
    val time: EventTime,
    val rrule: String?,
    val exdates: List<String>,
    val busy: Boolean,
    val visibility: Visibility,
    val reminders: List<Reminder>,
    val hasAlarm: Boolean,
    /** The https links of ATTACH (at most 25, no repeated address); the other kinds of ATTACH are counted in the report, never read. */
    val attachments: List<EventAttachment> = emptyList(),
)

class IcsReport(
    val method: String?,
    val calendarName: String?,
    val cancelledEvents: Int,
    val cancelledOccurrences: Int,
    /** Occurrences of a series that is not in the file (the public feeds of Google): imported as single events. */
    val orphanOccurrences: Int,
    val fileHasAlarms: Boolean,
    val ignoredComponents: Map<String, Int>,
    val issues: List<IcsIssue>,
    val fatal: IcsIssueCode?,
)

class IcsImport(val events: List<ImportedEvent>, val report: IcsReport)

/**
 * Turns the blocks read by [IcsReader] into events. The text is untrusted: fields are cut to a maximum length, control
 * characters are dropped, a wrong event is skipped and counted in the report, and nothing is ever sent to anybody.
 */
object IcsMapper {
    const val MAX_TITLE = 1024
    const val MAX_LOCATION = 1024
    const val MAX_DESCRIPTION = 8192
    const val MAX_UID = 512
    const val MAX_ALARMS = 5
    private const val MAX_MINUTES_BEFORE = 40_320L

    private val dateFormat = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT)
    private val dateTimeFormat = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss").withResolverStyle(ResolverStyle.STRICT)
    private val utcStamp = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun parse(text: String, options: IcsImportOptions): IcsImport = map(IcsReader.read(text), options)

    fun map(doc: IcsDocument, o: IcsImportOptions): IcsImport {
        val issues = IcsIssues()
        doc.issues.forEach { issues.merge(it) }
        val method = doc.properties.firstOrNull { it.name == "METHOD" }?.value?.trim()?.uppercase()
        val name = doc.properties.firstOrNull { it.name == "X-WR-CALNAME" }?.let { text(it.value, MAX_TITLE, issues, singleLine = true) }?.ifBlank { null }
        val ignored = java.util.TreeMap<String, Int>()
        val drafts = ArrayList<Draft>()
        var hasAlarms = false
        for (c in doc.components) {
            when (c.name) {
                "VEVENT" -> {
                    if (c.children("VALARM").isNotEmpty()) hasAlarms = true
                    build(c, o, issues, cancelAll = method == "CANCEL")?.let { drafts.add(it) }
                }
                "VTIMEZONE" -> Unit
                else -> ignored.merge(c.name, 1, Int::plus)
            }
        }
        var cancelledEvents = 0
        var cancelledOccurrences = 0
        var orphans = 0

        val masters = HashMap<String, Draft>()
        for (d in drafts) {
            if (d.recurrenceKey != null) continue
            if (d.cancelled) {
                cancelledEvents++
            } else if (masters.put(d.uid, d) != null) {
                issues.add(IcsIssueCode.DUPLICATE_UID, d.uid)
            }
        }
        // A cancelled occurrence of a series becomes an excluded date of it; one without its series is a cancelled event.
        for (d in drafts) {
            val key = d.recurrenceKey ?: continue
            if (!d.cancelled) continue
            val master = masters[d.uid]
            if (master != null) {
                if (master.rrule != null && key !in master.exdates) master.exdates.add(key)
                cancelledOccurrences++
            } else {
                cancelledEvents++
            }
        }
        val out = LinkedHashMap<String, ImportedEvent>()
        for (d in drafts) {
            if (d.cancelled) continue
            val key = d.recurrenceKey
            if (key == null) {
                if (masters[d.uid] === d) add(out, issues, d.toEvent(o.calendarId + "|" + d.uid, null, null))
                continue
            }
            val id = o.calendarId + "|" + d.uid + "|" + key
            if (masters[d.uid] != null) {
                add(out, issues, d.toEvent(id, o.calendarId + "|" + d.uid, key))
            } else {
                orphans++
                add(out, issues, d.toEvent(id, null, null))
            }
        }
        val events = out.values.toList()
        return IcsImport(
            events,
            IcsReport(method, name, cancelledEvents, cancelledOccurrences, orphans, hasAlarms, ignored, issues.list(), doc.fatal),
        )
    }

    private fun add(out: LinkedHashMap<String, ImportedEvent>, issues: IcsIssues, e: ImportedEvent) {
        if (out.put(e.id, e) != null) issues.add(IcsIssueCode.DUPLICATE_UID, e.uid)
    }

    // ---- one VEVENT ----

    private class Draft(
        val uid: String,
        /** ISO instant (timed) or ISO date (all day) of the original start, for a modified occurrence. */
        val recurrenceKey: String?,
        val cancelled: Boolean,
        val title: String,
        val location: String,
        val description: String,
        val time: EventTime,
        val rrule: String?,
        val exdates: MutableList<String>,
        val busy: Boolean,
        val visibility: Visibility,
        val reminders: List<Reminder>,
        val hasAlarm: Boolean,
        val attachments: List<EventAttachment>,
    ) {
        fun toEvent(id: String, masterId: String?, recurrenceId: String?) = ImportedEvent(
            id, uid, masterId, recurrenceId, title, location, description, time, rrule, exdates.toList(), busy, visibility, reminders, hasAlarm, attachments,
        )
    }

    private class Temporal(val date: LocalDate?, val local: LocalDateTime?, val utc: Boolean, val tz: ZoneId?)

    private fun build(c: IcsComponent, o: IcsImportOptions, issues: IcsIssues, cancelAll: Boolean): Draft? {
        val startProp = c.property("DTSTART")
        if (startProp == null) {
            issues.add(IcsIssueCode.MISSING_START)
            return null
        }
        val start = temporal(startProp, issues)
        if (start == null) {
            issues.add(IcsIssueCode.INVALID_DATE, startProp.value)
            return null
        }
        val title = text(c.property("SUMMARY")?.value.orEmpty(), MAX_TITLE, issues, singleLine = true)
        val location = text(c.property("LOCATION")?.value.orEmpty(), MAX_LOCATION, issues, singleLine = true)
        var description = text(c.property("DESCRIPTION")?.value.orEmpty(), MAX_DESCRIPTION, issues, singleLine = false)
        if (o.guestsInDescription) description = withGuests(c, description)
        val uid = c.property("UID")?.value?.trim()?.filter { it >= ' ' }?.takeIf { it.isNotEmpty() }?.let {
            if (it.length > MAX_UID) {
                issues.add(IcsIssueCode.FIELD_TRUNCATED, "UID")
                it.take(MAX_UID)
            } else {
                it
            }
        } ?: run {
            issues.add(IcsIssueCode.MISSING_UID)
            "noid-" + sha256(startProp.value + "|" + title + "|" + location).take(16)
        }
        val status = c.property("STATUS")?.value?.trim()?.uppercase()
        val cancelled = cancelAll || status == "CANCELLED"

        // Time.
        val eventZone: ZoneId = start.tz ?: if (start.utc) ZoneOffset.UTC else o.defaultZone
        val allDay = start.date != null
        val endTemporal = c.property("DTEND")?.let { temporal(it, issues) }
        val durationSeconds = c.property("DURATION")?.let { duration(it.value) }
        val time: EventTime
        val recurringRule = c.property("RRULE")
        val rrule = recurringRule?.let { rrule(it.value, allDay, eventZone, issues) }
        if (allDay) {
            val startDay = start.date!!
            var endDay = when {
                endTemporal != null -> endTemporal.date ?: endTemporal.local?.toLocalDate()
                durationSeconds != null -> startDay.plusDays(maxOf(1L, durationSeconds / 86_400L))
                else -> null
            } ?: startDay.plusDays(1)
            if (!endDay.isAfter(startDay)) endDay = startDay.plusDays(1)
            time = EventTime.AllDay(startDay, endDay)
        } else {
            val begin = instant(start, o.defaultZone)
            var end = when {
                endTemporal != null -> instant(endTemporal, endTemporal.tz ?: eventZone)
                durationSeconds != null -> begin.plusSeconds(durationSeconds)
                else -> begin
            }
            if (end.isBefore(begin)) {
                issues.add(IcsIssueCode.END_BEFORE_START)
                end = begin
            }
            val zoneName = when {
                start.tz != null -> start.tz.id
                start.utc -> if (rrule != null) "UTC" else null
                else -> o.defaultZone.id
            }
            time = EventTime.Timed(begin, end, zoneName)
        }

        // Series: excluded dates; RDATE is not supported.
        val exdates = ArrayList<String>()
        if (c.all("RDATE").isNotEmpty()) issues.add(IcsIssueCode.RDATE_IGNORED, count = c.all("RDATE").size)
        if (rrule != null) {
            for (p in c.all("EXDATE")) {
                for (piece in p.value.split(',')) {
                    if (piece.isBlank()) continue
                    val t = temporal(IcsProperty(p.name, p.params, piece.trim()), issues)
                    if (t == null) {
                        issues.add(IcsIssueCode.INVALID_DATE, piece)
                        continue
                    }
                    val key = key(t, time, eventZone) ?: continue
                    if (key !in exdates) exdates.add(key)
                }
            }
        }

        // Modified occurrence.
        var recurrenceKey: String? = null
        val rid = c.property("RECURRENCE-ID")
        if (rid != null) {
            val t = temporal(rid, issues)
            if (t == null) {
                issues.add(IcsIssueCode.INVALID_DATE, rid.value)
                return null
            }
            if (rid.params["RANGE"]?.uppercase() == "THISANDFUTURE") issues.add(IcsIssueCode.THIS_AND_FUTURE)
            recurrenceKey = key(t, time, eventZone)
        }

        val attachments = attachments(c, issues)
        if (c.all("CATEGORIES").isNotEmpty()) issues.add(IcsIssueCode.CATEGORIES_IGNORED)

        val alarmBlocks = c.children("VALARM")
        val reminders = if (o.importAlarms && alarmBlocks.isNotEmpty()) alarms(alarmBlocks, time, issues) else emptyList()
        val visibility = when (c.property("CLASS")?.value?.trim()?.uppercase()) {
            "PRIVATE", "CONFIDENTIAL" -> Visibility.PRIVATE
            "PUBLIC" -> Visibility.PUBLIC
            else -> Visibility.DEFAULT
        }
        val busy = c.property("TRANSP")?.value?.trim()?.uppercase() != "TRANSPARENT"
        return Draft(uid, recurrenceKey, cancelled, title, location, description, time, rrule, exdates, busy, visibility, reminders, alarmBlocks.isNotEmpty(), attachments)
    }

    private val MIME = Regex("^[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*$")

    /**
     * The links of ATTACH: https only (the type and the name come from FMTTYPE and FILENAME, or from the address). A file inside the
     * calendar (BINARY or ENCODING), another scheme and an address that is not valid are ignored and counted ([IcsIssueCode.ATTACH_IGNORED]);
     * the same address twice is one attachment; the ones after the 25th are counted too ([IcsIssueCode.ATTACH_CUT]). Nothing is downloaded.
     */
    private fun attachments(c: IcsComponent, issues: IcsIssues): List<EventAttachment> {
        val kept = LinkedHashMap<String, EventAttachment>()
        val cut = HashSet<String>()
        var ignored = 0
        for (p in c.all("ATTACH")) {
            val binary = p.params["VALUE"]?.trim()?.equals("BINARY", ignoreCase = true) == true || p.params.containsKey("ENCODING")
            val address = p.value.trim()
            if (binary || !AttachmentLink.isValid(address)) {
                ignored++
                continue
            }
            if (address in kept || address in cut) continue
            if (kept.size >= AttachmentLink.MAX_COUNT) {
                cut += address
                continue
            }
            val name = p.params["FILENAME"]?.filter { it >= ' ' }?.trim()?.take(AttachmentLink.MAX_TITLE_LENGTH)
            val made = AttachmentLink.create(address, name) ?: continue
            val type = p.params["FMTTYPE"]?.trim()?.takeIf { it.length <= 100 && MIME.matches(it) }
            kept[address] = if (type != null) made.copy(mimeType = type) else made
        }
        if (ignored > 0) issues.add(IcsIssueCode.ATTACH_IGNORED, count = ignored)
        if (cut.isNotEmpty()) issues.add(IcsIssueCode.ATTACH_CUT, count = cut.size)
        return kept.values.toList()
    }

    // ---- dates and zones ----

    private fun temporal(p: IcsProperty, issues: IcsIssues): Temporal? {
        val v = p.value.trim()
        return try {
            if (p.params["VALUE"]?.uppercase() == "DATE" || (v.length == 8 && v.all { it.isDigit() })) {
                Temporal(LocalDate.parse(v, dateFormat), null, false, null)
            } else {
                val utc = v.endsWith("Z", ignoreCase = true)
                val local = LocalDateTime.parse(v.dropLast(if (utc) 1 else 0).uppercase(), dateTimeFormat)
                Temporal(null, local, utc, if (utc) null else p.params["TZID"]?.let { zone(it, issues) })
            }
        } catch (e: DateTimeParseException) {
            null
        } catch (e: DateTimeException) {
            null
        }
    }

    private fun instant(t: Temporal, fallback: ZoneId): Instant = when {
        t.date != null -> t.date.atStartOfDay(fallback).toInstant()
        t.utc -> t.local!!.toInstant(ZoneOffset.UTC)
        else -> t.local!!.atZone(t.tz ?: fallback).toInstant()
    }

    /** The stored form of an original start or an excluded date: ISO date for all-day series, ISO instant for timed ones. */
    private fun key(t: Temporal, time: EventTime, eventZone: ZoneId): String? = when (time) {
        is EventTime.AllDay -> (t.date ?: t.local?.toLocalDate())?.toString()
        is EventTime.Timed ->
            if (t.date != null) {
                // A date for a timed series: the occurrence of that day, at the time of the series.
                val at = time.start.atZone(eventZone).toLocalTime()
                t.date.atTime(at).atZone(eventZone).toInstant().toString()
            } else {
                instant(t, eventZone).toString()
            }
    }

    private fun zone(name: String, issues: IcsIssues): ZoneId? {
        val tzid = name.trim().trim('"')
        if (tzid.isEmpty()) return null
        runCatching { ZoneId.of(tzid) }.getOrNull()?.let { return it }
        // "/mozilla.org/20050126_1/Europe/Rome": try the tail of the name.
        val parts = tzid.split('/').filter { it.isNotEmpty() }
        for (i in 1 until parts.size) {
            runCatching { ZoneId.of(parts.drop(i).joinToString("/")) }.getOrNull()?.let { return it }
        }
        WINDOWS_ZONES[tzid.lowercase()]?.let { return ZoneId.of(it) }
        issues.add(IcsIssueCode.UNKNOWN_TZID, tzid)
        return null
    }

    // ---- recurrence ----

    private val supportedRuleKeys = setOf("FREQ", "INTERVAL", "COUNT", "UNTIL", "BYDAY", "BYMONTHDAY", "BYMONTH", "BYSETPOS", "WKST")
    private val frequencies = setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")
    private val dayCode = Regex("^[+-]?\\d{0,2}(MO|TU|WE|TH|FR|SA|SU)$")

    /** The rule as the app stores it (UNTIL made unambiguous), or null for a rule that is wrong or not supported. */
    private fun rrule(raw: String, allDay: Boolean, zone: ZoneId, issues: IcsIssues): String? {
        val body = raw.trim().removePrefix("RRULE:").removePrefix("rrule:")
        val parts = body.split(';').filter { it.isNotBlank() }
        fun bad(): String? {
            issues.add(IcsIssueCode.INVALID_RRULE, body)
            return null
        }
        val map = LinkedHashMap<String, String>()
        for (part in parts) {
            val eq = part.indexOf('=')
            if (eq <= 0) return bad()
            map[part.substring(0, eq).trim().uppercase()] = part.substring(eq + 1).trim().uppercase()
        }
        if (map["FREQ"] !in frequencies) return bad()
        if (!supportedRuleKeys.containsAll(map.keys)) return bad()
        if ("COUNT" in map && "UNTIL" in map) return bad()
        for (key in listOf("INTERVAL", "COUNT")) {
            val n = map[key] ?: continue
            if ((n.toIntOrNull() ?: 0) < 1) return bad()
        }
        map["BYDAY"]?.let { v -> if (!v.split(',').all { dayCode.matches(it) }) return bad() }
        map["BYMONTHDAY"]?.let { v -> if (!v.split(',').all { (it.toIntOrNull() ?: 0) in -31..31 && it.toIntOrNull() != 0 }) return bad() }
        map["BYMONTH"]?.let { v -> if (!v.split(',').all { (it.toIntOrNull() ?: 0) in 1..12 }) return bad() }
        map["BYSETPOS"]?.let { v -> if (!v.split(',').all { it.toIntOrNull() != null && it.toInt() != 0 }) return bad() }
        map["UNTIL"]?.let { until -> map["UNTIL"] = untilValue(until, allDay, zone) ?: return bad() }
        return map.entries.joinToString(";") { it.key + "=" + it.value }
    }

    private fun untilValue(v: String, allDay: Boolean, zone: ZoneId): String? = try {
        val date = v.length == 8
        val z = v.endsWith("Z")
        when {
            allDay -> LocalDate.parse(v.take(8), dateFormat).format(dateFormat)
            z -> utcStamp.format(LocalDateTime.parse(v.dropLast(1), dateTimeFormat).toInstant(ZoneOffset.UTC))
            date -> utcStamp.format(LocalDate.parse(v, dateFormat).atTime(23, 59, 59).atZone(zone).toInstant())
            else -> utcStamp.format(LocalDateTime.parse(v, dateTimeFormat).atZone(zone).toInstant())
        }
    } catch (e: DateTimeException) {
        null
    }

    // ---- reminders ----

    private fun alarms(blocks: List<IcsComponent>, time: EventTime, issues: IcsIssues): List<Reminder> {
        val lengthSeconds = when (time) {
            is EventTime.Timed -> time.end.epochSecond - time.start.epochSecond
            is EventTime.AllDay -> 86_400L * (time.endDay.toEpochDay() - time.startDay.toEpochDay())
        }
        val out = ArrayList<Reminder>()
        for (a in blocks) {
            val trigger = a.property("TRIGGER")
            if (trigger == null) {
                issues.add(IcsIssueCode.INVALID_ALARM)
                continue
            }
            if (trigger.params["VALUE"]?.uppercase() == "DATE-TIME") {
                issues.add(IcsIssueCode.ABSOLUTE_ALARM)
                continue
            }
            val seconds = duration(trigger.value)
            if (seconds == null) {
                issues.add(IcsIssueCode.INVALID_ALARM, trigger.value)
                continue
            }
            val fromStart = if (trigger.params["RELATED"]?.uppercase() == "END") seconds + lengthSeconds else seconds
            val before = -fromStart
            if (before < 0) {
                issues.add(IcsIssueCode.ALARM_AFTER_START)
                continue
            }
            val minutes = (before + 59) / 60
            if (minutes > MAX_MINUTES_BEFORE) {
                issues.add(IcsIssueCode.ALARM_TOO_EARLY)
                continue
            }
            val method = when (a.property("ACTION")?.value?.trim()?.uppercase()) {
                "AUDIO" -> ReminderMethod.ALARM
                "EMAIL" -> ReminderMethod.EMAIL
                else -> ReminderMethod.POPUP
            }
            val r = Reminder(method, minutes.toInt())
            if (r in out) continue
            if (out.size >= MAX_ALARMS) {
                issues.add(IcsIssueCode.TOO_MANY_ALARMS)
                continue
            }
            out.add(r)
        }
        return out
    }

    private val durationPattern = Regex("^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$")

    /** An RFC 5545 duration in seconds, or null if it is not one. */
    internal fun duration(value: String): Long? {
        val v = value.trim().uppercase()
        val m = durationPattern.matchEntire(v) ?: return null
        val g = m.groupValues
        if (g.drop(2).all { it.isEmpty() }) return null
        fun n(i: Int) = g[i].toLongOrNull() ?: 0L
        if (g.drop(2).any { it.length > 9 }) return null
        val total = n(2) * 7 * 86_400 + n(3) * 86_400 + n(4) * 3_600 + n(5) * 60 + n(6)
        return if (g[1] == "-") -total else total
    }

    // ---- text ----

    private fun text(raw: String, max: Int, issues: IcsIssues, singleLine: Boolean): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\' && i + 1 < raw.length) {
                val next = raw[i + 1]
                sb.append(if (next == 'n' || next == 'N') '\n' else next)
                i += 2
                continue
            }
            sb.append(ch)
            i++
        }
        val cleaned = sb.filter { it == '\n' || it >= ' ' && it != '\u007F' }.toString()
        val shaped = if (singleLine) cleaned.replace('\n', ' ') else cleaned
        val trimmed = shaped.trim()
        if (trimmed.length <= max) return trimmed
        issues.add(IcsIssueCode.FIELD_TRUNCATED)
        // Do not cut a surrogate pair in half.
        val cut = if (Character.isHighSurrogate(trimmed[max - 1])) max - 1 else max
        return trimmed.take(cut)
    }

    private fun withGuests(c: IcsComponent, description: String): String {
        fun who(p: IcsProperty) = p.params["CN"]?.takeIf { it.isNotBlank() } ?: p.value.trim().removePrefix("mailto:").removePrefix("MAILTO:")
        val organizer = c.property("ORGANIZER")?.let { "Organizer: " + who(it) }
        val attendees = c.all("ATTENDEE").takeIf { it.isNotEmpty() }?.let { "Attendees: " + it.joinToString(", ") { a -> who(a) } }
        return listOfNotNull(description.takeIf { it.isNotEmpty() }, organizer, attendees).joinToString("\n").take(MAX_DESCRIPTION)
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Outlook and Exchange write Windows zone names; lower case here. */
    private val WINDOWS_ZONES = mapOf(
        "w. europe standard time" to "Europe/Berlin", "central europe standard time" to "Europe/Budapest",
        "central european standard time" to "Europe/Warsaw", "romance standard time" to "Europe/Paris",
        "gmt standard time" to "Europe/London", "greenwich standard time" to "Atlantic/Reykjavik",
        "e. europe standard time" to "Europe/Chisinau", "fle standard time" to "Europe/Kiev",
        "gtb standard time" to "Europe/Bucharest", "turkey standard time" to "Europe/Istanbul",
        "russian standard time" to "Europe/Moscow", "greece standard time" to "Europe/Athens",
        "eastern standard time" to "America/New_York", "central standard time" to "America/Chicago",
        "mountain standard time" to "America/Denver", "pacific standard time" to "America/Los_Angeles",
        "us mountain standard time" to "America/Phoenix", "alaskan standard time" to "America/Anchorage",
        "hawaiian standard time" to "Pacific/Honolulu", "atlantic standard time" to "America/Halifax",
        "newfoundland standard time" to "America/St_Johns", "sa pacific standard time" to "America/Bogota",
        "e. south america standard time" to "America/Sao_Paulo", "argentina standard time" to "America/Argentina/Buenos_Aires",
        "central america standard time" to "America/Guatemala", "mexico standard time" to "America/Mexico_City",
        "canada central standard time" to "America/Regina", "utc" to "UTC", "tokyo standard time" to "Asia/Tokyo",
        "china standard time" to "Asia/Shanghai", "korea standard time" to "Asia/Seoul", "india standard time" to "Asia/Kolkata",
        "singapore standard time" to "Asia/Singapore", "arabian standard time" to "Asia/Dubai", "israel standard time" to "Asia/Jerusalem",
        "aus eastern standard time" to "Australia/Sydney", "e. australia standard time" to "Australia/Brisbane",
        "new zealand standard time" to "Pacific/Auckland", "south africa standard time" to "Africa/Johannesburg",
        "egypt standard time" to "Africa/Cairo", "w. central africa standard time" to "Africa/Lagos",
    )
}
