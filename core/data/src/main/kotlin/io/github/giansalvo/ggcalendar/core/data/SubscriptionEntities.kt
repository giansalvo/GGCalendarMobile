package io.github.giansalvo.ggcalendar.core.data

import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.model.EventTime
import io.github.giansalvo.ggcalendar.core.model.ImportedEvent
import java.time.Instant
import java.time.LocalDate

/**
 * The row of an event read from a file, in a calendar that only reads (a subscription). It has no reminders of its own and
 * no guests, and never an outbox entry: the file is the only source. [updatedAt] is left at 0 so that two reads of the same
 * file give equal rows (the difference between two copies is made on the rows).
 */
internal fun ImportedEvent.toSubscriptionEntity(calendarId: String): EventEntity {
    val base = EventEntity(
        id = id,
        calendarId = calendarId,
        iCalUid = uid,
        title = title,
        location = location,
        description = description,
        rrule = rrule,
        exdates = exdates,
        seriesMasterId = masterId,
        availability = if (busy) "BUSY" else "FREE",
        visibility = visibility.name,
        sendInvites = false,
        reminders = emptyList(),
        hasAlarm = false,
        updatedAt = 0,
    )
    val withTime = when (val t = time) {
        is EventTime.Timed -> base.copy(allDay = false, startUtc = t.start.toEpochMilli(), endUtc = t.end.toEpochMilli(), timeZone = t.zoneId)
        is EventTime.AllDay -> base.copy(allDay = true, startDay = t.startDay.toEpochDay(), endDay = t.endDay.toEpochDay())
    }
    val key = recurrenceId ?: return withTime
    return if ('T' in key) withTime.copy(originalStartUtc = Instant.parse(key).toEpochMilli())
    else withTime.copy(originalStartDay = LocalDate.parse(key).toEpochDay())
}
