package io.github.giansalvo.ggcalendar.core.data

import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.model.EventTime
import io.github.giansalvo.ggcalendar.core.model.Reminder
import io.github.giansalvo.ggcalendar.core.model.ReminderMethod
import io.github.giansalvo.ggcalendar.core.network.GoogleEvent
import io.github.giansalvo.ggcalendar.core.network.GoogleEventMapper
import io.github.giansalvo.ggcalendar.core.network.OriginalStart

/** Conversion of a parsed Google event into the local row (ids are local; [masterId] links an occurrence to its series). */
internal fun GoogleEvent.toEntity(id: String, calendarId: String, masterId: String?, calendarDefaults: List<Reminder>): EventEntity {
    val effective = reminders ?: calendarDefaults
    // Popups whose minutes are listed in ggAlarm are alarms (shared with the desktop app through Google).
    val local = reminders?.map { if (it.method == ReminderMethod.POPUP && it.minutes in alarmMinutes) it.copy(method = ReminderMethod.ALARM) else it }
    val base = EventEntity(
        id = id,
        calendarId = calendarId,
        remoteId = this.id,
        etag = etag,
        iCalUid = iCalUid,
        title = title,
        location = location,
        description = description,
        rrule = rrule,
        exdates = exdates.map { it.iso() },
        seriesMasterId = masterId,
        originalStartUtc = (originalStart as? OriginalStart.Timed)?.start?.toEpochMilli(),
        originalStartDay = (originalStart as? OriginalStart.AllDay)?.day?.toEpochDay(),
        color = colorId?.let(GoogleEventMapper.EVENT_COLORS::get),
        availability = availability.name,
        visibility = visibility.name,
        meetLink = meetLink,
        addMeet = hasConference,
        guestsCanModify = guestsCanModify,
        guestsCanInvite = guestsCanInvite,
        guestsCanSeeList = guestsCanSeeList,
        organizerEmail = organizerEmail,
        attendees = attendees,
        reminders = local,
        hasAlarm = effective.any { it.method == ReminderMethod.POPUP && it.minutes in alarmMinutes },
    )
    return when (val t = time) {
        is EventTime.Timed -> base.copy(allDay = false, startUtc = t.start.toEpochMilli(), endUtc = t.end.toEpochMilli(), timeZone = t.zoneId)
        is EventTime.AllDay -> base.copy(allDay = true, startDay = t.startDay.toEpochDay(), endDay = t.endDay.toEpochDay())
        null -> base
    }
}

/** A removed occurrence of a series, kept so that the series does not draw it again. */
internal fun GoogleEvent.toTombstone(id: String, calendarId: String, masterId: String) = EventEntity(
    id = id,
    calendarId = calendarId,
    remoteId = this.id,
    etag = etag,
    seriesMasterId = masterId,
    allDay = originalStart is OriginalStart.AllDay,
    originalStartUtc = (originalStart as? OriginalStart.Timed)?.start?.toEpochMilli(),
    originalStartDay = (originalStart as? OriginalStart.AllDay)?.day?.toEpochDay(),
    isCancelled = true,
)

private fun OriginalStart.iso(): String = when (this) {
    is OriginalStart.Timed -> start.toString()
    is OriginalStart.AllDay -> day.toString()
}
