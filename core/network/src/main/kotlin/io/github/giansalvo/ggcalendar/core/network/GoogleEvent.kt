package io.github.giansalvo.ggcalendar.core.network

import io.github.giansalvo.ggcalendar.core.model.Attendee
import io.github.giansalvo.ggcalendar.core.model.Availability
import io.github.giansalvo.ggcalendar.core.model.EventTime
import io.github.giansalvo.ggcalendar.core.model.Reminder
import io.github.giansalvo.ggcalendar.core.model.ReminderMethod
import io.github.giansalvo.ggcalendar.core.model.Visibility
import kotlinx.serialization.json.JsonArray
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
        )
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
