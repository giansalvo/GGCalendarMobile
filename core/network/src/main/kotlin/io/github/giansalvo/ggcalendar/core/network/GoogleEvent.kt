package io.github.giansalvo.ggcalendar.core.network

import io.github.giansalvo.ggcalendar.core.model.Attendee
import io.github.giansalvo.ggcalendar.core.model.Availability
import io.github.giansalvo.ggcalendar.core.model.EventTime
import io.github.giansalvo.ggcalendar.core.model.Reminder
import io.github.giansalvo.ggcalendar.core.model.ReminderMethod
import io.github.giansalvo.ggcalendar.core.model.Visibility
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Start of an occurrence before it was modified (Google's originalStartTime): an instant, or a date for all-day series. */
sealed interface OriginalStart {
    data class Timed(val start: Instant) : OriginalStart
    data class AllDay(val day: LocalDate) : OriginalStart
}

/**
 * A Google Calendar v3 event resource, parsed. [cancelled] events carry only the ids (and the original start, for a
 * removed occurrence of a series); every other field is meaningful only when it is false.
 */
data class GoogleEvent(
    val id: String,
    val etag: String?,
    val iCalUid: String?,
    val cancelled: Boolean,
    val recurringEventId: String?,
    val originalStart: OriginalStart?,
    val time: EventTime?,
    val title: String = "",
    val location: String = "",
    val description: String = "",
    /** RRULE value without the "RRULE:" prefix. */
    val rrule: String? = null,
    val exdates: List<OriginalStart> = emptyList(),
    val attendees: List<Attendee> = emptyList(),
    val organizerEmail: String? = null,
    /** Null = the calendar's default reminders. */
    val reminders: List<Reminder>? = null,
    val availability: Availability = Availability.BUSY,
    val visibility: Visibility = Visibility.DEFAULT,
    val colorId: String? = null,
    val meetLink: String? = null,
    /** True also while Google is still creating the conference (no link yet). */
    val hasConference: Boolean = false,
    val guestsCanModify: Boolean = false,
    val guestsCanInvite: Boolean = true,
    val guestsCanSeeList: Boolean = true,
    /** Minutes of the popup reminders that are alarms (extendedProperties.private.ggAlarm), shared with the desktop app. */
    val alarmMinutes: Set<Int> = emptySet(),
    /** "default", "birthday", "focusTime", "outOfOffice", "workingLocation" or "fromGmail". */
    val eventType: String = "default",
)

/** Conversion of Google Calendar v3 event resources, ported from the desktop app's GoogleEventMapper. */
object GoogleEventMapper {
    const val ALARM_PROPERTY = "ggAlarm"

    /** Google's classic event palette (colorId → color); events without colorId use the calendar color. */
    val EVENT_COLORS = mapOf(
        "1" to "#7986CB", "2" to "#33B679", "3" to "#8E24AA", "4" to "#E67C73", "5" to "#F6BF26", "6" to "#F4511E",
        "7" to "#039BE5", "8" to "#616161", "9" to "#3F51B5", "10" to "#0B8043", "11" to "#D50000",
    )

    fun fromGoogle(e: JsonObject): GoogleEvent? {
        val id = e.str("id") ?: return null
        val originalStart = e.obj("originalStartTime")?.let(::parseOriginal)
        val base = GoogleEvent(
            id = id,
            etag = e.str("etag"),
            iCalUid = e.str("iCalUID"),
            cancelled = e.str("status") == "cancelled",
            recurringEventId = e.str("recurringEventId"),
            originalStart = originalStart,
            time = null,
        )
        if (base.cancelled) return base

        val time = parseTime(e.obj("start"), e.obj("end")) ?: return null

        val attendees = e.arr("attendees")?.mapNotNull { a ->
            val o = a.jsonObject
            if (o.bool("resource") == true) return@mapNotNull null
            Attendee(
                email = o.str("email") ?: return@mapNotNull null,
                optional = o.bool("optional") ?: false,
                displayName = o.str("displayName"),
                response = o.str("responseStatus"),
            )
        } ?: emptyList()

        val remindersObj = e.obj("reminders")
        val reminders = when {
            remindersObj == null || remindersObj.bool("useDefault") == true -> null
            else -> remindersObj.arr("overrides")?.map { parseReminder(it.jsonObject) } ?: emptyList()
        }

        var rrule: String? = null
        val exdates = mutableListOf<OriginalStart>()
        e.arr("recurrence")?.mapNotNull { (it as? JsonPrimitive)?.content }?.forEach { line ->
            when {
                line.startsWith("RRULE:", ignoreCase = true) -> if (rrule == null) rrule = line.substring(6)
                line.startsWith("EXDATE", ignoreCase = true) -> exdates += parseExDates(line)
            }
        }

        // Google creates the conference asynchronously: right after the request there is conferenceData but no link yet.
        val meet = e.str("hangoutLink") ?: meetUri(e)
        val alarms = e.obj("extendedProperties")?.obj("private")?.str(ALARM_PROPERTY)
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: emptySet()

        return base.copy(
            time = time,
            title = e.str("summary") ?: "",
            location = e.str("location") ?: "",
            description = e.str("description") ?: "",
            rrule = rrule,
            exdates = exdates,
            attendees = attendees,
            organizerEmail = e.obj("organizer")?.str("email"),
            reminders = reminders,
            availability = if (e.str("transparency") == "transparent") Availability.FREE else Availability.BUSY,
            visibility = when (e.str("visibility")) {
                "public" -> Visibility.PUBLIC
                "private" -> Visibility.PRIVATE
                else -> Visibility.DEFAULT
            },
            colorId = e.str("colorId"),
            meetLink = meet,
            hasConference = meet != null || e.containsKey("conferenceData"),
            guestsCanModify = e.bool("guestsCanModify") ?: false,
            guestsCanInvite = e.bool("guestsCanInviteOthers") ?: true,
            guestsCanSeeList = e.bool("guestsCanSeeOtherGuests") ?: true,
            alarmMinutes = alarms,
            eventType = e.str("eventType") ?: "default",
        )
    }

    /** The colorId of one of [EVENT_COLORS], or null for the calendar color. */
    fun colorIdOf(hex: String?): String? = hex?.let { h -> EVENT_COLORS.entries.firstOrNull { it.value.equals(h, ignoreCase = true) }?.key }

    /** Id of one occurrence of a series: the master id plus the original start (UTC, or the date for all-day series). */
    fun instanceId(masterRemoteId: String, originalStart: OriginalStart): String = masterRemoteId + "_" + when (originalStart) {
        is OriginalStart.AllDay -> originalStart.day.format(DateTimeFormatter.BASIC_ISO_DATE)
        is OriginalStart.Timed -> UTC_BASIC.format(originalStart.start)
    }

    /**
     * Request body for an insert or a patch, ported from the desktop ToGoogle. Reminders: null keeps the calendar's
     * defaults; alarms travel as popups listed in `extendedProperties.private.ggAlarm` ([GoogleEvent.alarmMinutes]).
     * In a [patch] Google merges objects with what it has, so fields being cleared are sent as null: the unused form of
     * start/end (all-day ⇄ timed), the color, the recurrence of a former series and, with
     * [removeMeet], the conference.
     */
    fun toGoogle(
        ev: GoogleEvent,
        includeMeetRequest: Boolean,
        patch: Boolean,
        defaultZone: ZoneId = ZoneId.systemDefault(),
        removeMeet: Boolean = false,
    ): JsonObject =
        buildJsonObject {
            put("summary", ev.title)
            put("location", ev.location)
            put("description", ev.description)
            when (val t = ev.time) {
                is EventTime.AllDay -> {
                    put("start", allDayTime(t.startDay))
                    put("end", allDayTime(t.endDay))
                }
                is EventTime.Timed -> {
                    val zone = t.zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: defaultZone
                    put("start", timedTime(t.start, zone))
                    put("end", timedTime(t.end, zone))
                }
                null -> Unit
            }
            put("transparency", if (ev.availability == Availability.FREE) "transparent" else "opaque")
            put(
                "visibility",
                when (ev.visibility) {
                    Visibility.PUBLIC -> "public"
                    Visibility.PRIVATE -> "private"
                    else -> "default"
                },
            )
            val colorId = ev.colorId
            if (colorId != null) put("colorId", colorId) else if (patch) put("colorId", JsonNull)
            put("guestsCanModify", ev.guestsCanModify)
            put("guestsCanInviteOthers", ev.guestsCanInvite)
            put("guestsCanSeeOtherGuests", ev.guestsCanSeeList)
            putJsonArray("attendees") {
                ev.attendees.forEach { a ->
                    addJsonObject {
                        put("email", a.email)
                        if (a.optional) put("optional", true)
                    }
                }
            }
            val reminders = ev.reminders
            putJsonObject("reminders") {
                if (reminders == null) {
                    put("useDefault", true)
                } else {
                    put("useDefault", false)
                    putJsonArray("overrides") {
                        reminders.forEach { r ->
                            addJsonObject {
                                put("method", if (r.method == ReminderMethod.EMAIL) "email" else "popup")
                                put("minutes", r.minutes)
                            }
                        }
                    }
                }
            }
            if (ev.alarmMinutes.isNotEmpty() || patch) {
                putJsonObject("extendedProperties") {
                    putJsonObject("private") {
                        // Google rejects null private properties (400 "Required"): an empty value means no alarm.
                        if (ev.alarmMinutes.isEmpty()) put(ALARM_PROPERTY, "")
                        else put(ALARM_PROPERTY, ev.alarmMinutes.sorted().joinToString(","))
                    }
                }
            }
            val rrule = ev.rrule
            if (rrule != null) {
                putJsonArray("recurrence") {
                    add("RRULE:$rrule")
                    exdateLine(ev.exdates, ev.time is EventTime.AllDay)?.let { add(it) }
                }
            } else if (patch && ev.recurringEventId == null) {
                // A former series becoming a single event; occurrences of a series never carry a recurrence.
                put("recurrence", JsonArray(emptyList()))
            }
            if (removeMeet && patch) put("conferenceData", JsonNull)
            if (includeMeetRequest) {
                putJsonObject("conferenceData") {
                    putJsonObject("createRequest") {
                        put("requestId", UUID.randomUUID().toString())
                        putJsonObject("conferenceSolutionKey") { put("type", "hangoutsMeet") }
                    }
                }
            }
        }

    private fun allDayTime(day: LocalDate) = buildJsonObject {
        put("date", day.toString())
        put("dateTime", JsonNull)
        put("timeZone", JsonNull)
    }

    private fun timedTime(at: Instant, zone: ZoneId) = buildJsonObject {
        put("date", JsonNull)
        put("dateTime", at.atZone(zone).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        put("timeZone", zone.id)
    }

    private val UTC_BASIC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    private fun exdateLine(exdates: List<OriginalStart>, allDay: Boolean): String? {
        if (exdates.isEmpty()) return null
        return if (allDay) {
            "EXDATE;VALUE=DATE:" + exdates.mapNotNull { (it as? OriginalStart.AllDay)?.day?.format(DateTimeFormatter.BASIC_ISO_DATE) }.joinToString(",")
        } else {
            "EXDATE:" + exdates.mapNotNull { (it as? OriginalStart.Timed)?.start?.let(UTC_BASIC::format) }.joinToString(",")
        }
    }

    fun parseDefaultReminders(calendarListEntry: JsonObject): List<Reminder> =
        calendarListEntry.arr("defaultReminders")?.map { parseReminder(it.jsonObject) } ?: emptyList()

    private fun parseReminder(o: JsonObject) = Reminder(
        method = if (o.str("method") == "email") ReminderMethod.EMAIL else ReminderMethod.POPUP,
        minutes = o["minutes"]?.jsonPrimitive?.intOrNull ?: 10,
    )

    private fun parseTime(s: JsonObject?, e: JsonObject?): EventTime? {
        if (s == null || e == null) return null
        s.str("date")?.let { sd ->
            val start = LocalDate.parse(sd)
            return EventTime.AllDay(start, e.str("date")?.let(LocalDate::parse) ?: start.plusDays(1))
        }
        val sdt = s.str("dateTime") ?: return null
        val edt = e.str("dateTime") ?: return null
        return EventTime.Timed(OffsetDateTime.parse(sdt).toInstant(), OffsetDateTime.parse(edt).toInstant(), s.str("timeZone"))
    }

    private fun parseOriginal(o: JsonObject): OriginalStart? {
        o.str("date")?.let { return OriginalStart.AllDay(LocalDate.parse(it)) }
        return o.str("dateTime")?.let { OriginalStart.Timed(OffsetDateTime.parse(it).toInstant()) }
    }

    private fun meetUri(e: JsonObject): String? =
        e.obj("conferenceData")?.arr("entryPoints")
            ?.map { it.jsonObject }
            ?.firstOrNull { it.str("entryPointType") == "video" }
            ?.str("uri")

    private val BASIC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /** EXDATE[;VALUE=DATE|;TZID=zone]:v1,v2,... with UTC ("Z"), zoned or date values. */
    internal fun parseExDates(line: String): List<OriginalStart> {
        val colon = line.indexOf(':')
        if (colon < 0) return emptyList()
        val zone = line.substring(0, colon).split(';')
            .firstOrNull { it.startsWith("TZID=", ignoreCase = true) }?.substring(5)
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        return line.substring(colon + 1).split(',').mapNotNull { raw ->
            val v = raw.trim()
            runCatching {
                when {
                    v.length == 8 -> OriginalStart.AllDay(LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE))
                    v.endsWith("Z") -> OriginalStart.Timed(LocalDateTime.parse(v.dropLast(1), BASIC).toInstant(ZoneOffset.UTC))
                    else -> OriginalStart.Timed(LocalDateTime.parse(v, BASIC).atZone(zone ?: ZoneId.systemDefault()).toInstant())
                }
            }.getOrNull()
        }
    }

    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
    private fun JsonObject.arr(name: String): JsonArray? = (this[name] as? JsonElement)?.let { it as? JsonArray }
}
