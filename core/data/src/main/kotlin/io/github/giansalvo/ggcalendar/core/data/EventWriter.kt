package io.github.giansalvo.ggcalendar.core.data

import androidx.room.withTransaction
import io.github.giansalvo.ggcalendar.core.model.Diag
import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.database.OutboxEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only way the app changes events. Each change is written to Room at once (so it works offline and shows
 * immediately), queued in the outbox for Google and followed by a sync request, which WorkManager runs as soon as
 * there is a connection. The events of local calendars stay in Room: no outbox and no sync.
 */
@Singleton
class EventWriter @Inject constructor(
    private val db: GGCalendarDatabase,
    private val engine: SyncEngine,
    private val scheduler: SyncScheduler,
    private val reminders: ReminderHooks,
) {
    private val events get() = db.eventDao()
    private val outbox get() = db.outboxDao()
    private val json get() = OutboxPusher.JSON

    /** Saves a new or edited event (also a modified occurrence of a series: [EventEntity.seriesMasterId] set). */
    suspend fun save(event: EventEntity, sendUpdates: Boolean = true) {
        val toGoogle = db.calendarDao().get(event.calendarId)?.syncsWithGoogle ?: true
        Diag.log { "writer save begin event=${event.id.take(8)} google=$toGoogle allDay=${event.allDay} start=${event.startUtc} end=${event.endUtc}" }
        val saved = if (toGoogle) event.copy(updatedAt = System.currentTimeMillis())
        // No guests, no Meet and no invitations in a calendar that is not Google's.
        else event.copy(updatedAt = System.currentTimeMillis(), attendees = emptyList(), addMeet = false, meetLink = null, sendInvites = false)
        db.withTransaction {
            val existing = events.get(event.id)
            if (!toGoogle) {
                events.upsert(listOf(saved))
                return@withTransaction
            }
            val pending = outbox.getFor(event.id)
            // The Google version the edit starts from: the row as last synced, or the base of the first queued edit.
            val base = if (pending.isEmpty()) existing?.takeIf { it.remoteId != null }?.let { json.encodeToString(it) } else pending.first().base
            events.upsert(listOf(saved))
            outbox.insert(
                OutboxEntity(
                    eventId = saved.id,
                    calendarId = saved.calendarId,
                    operation = if (existing == null && saved.seriesMasterId == null) OutboxEntity.CREATE else OutboxEntity.UPDATE,
                    payload = json.encodeToString(saved),
                    base = base,
                    sendUpdates = sendUpdates,
                ),
            )
        }
        engine.recompute(saved.seriesMasterId ?: saved.id)
        if (toGoogle) scheduler.syncNow()
        reminders.changed()
        Diag.log { "writer save done: '${event.title}' event=${event.id.take(8)} calendar=${event.calendarId}" }
    }

    /**
     * Deletes an event, or a whole series with its modified occurrences. A modified occurrence becomes a cancelled one
     * instead, so that the series does not draw it again.
     */
    suspend fun delete(eventId: String, sendUpdates: Boolean = true) {
        val event = events.get(eventId) ?: return
        val toGoogle = db.calendarDao().get(event.calendarId)?.syncsWithGoogle ?: true
        db.withTransaction {
            outbox.discardFor(eventId)
            if (event.seriesMasterId != null) {
                events.upsert(listOf(event.copy(isCancelled = true, updatedAt = System.currentTimeMillis())))
                if (toGoogle) outbox.insert(OutboxEntity(eventId = eventId, calendarId = event.calendarId, operation = OutboxEntity.DELETE, payload = event.remoteId, sendUpdates = sendUpdates))
            } else {
                events.deleteWithSeries(listOf(eventId))
                if (event.remoteId != null) {
                    outbox.insert(OutboxEntity(eventId = eventId, calendarId = event.calendarId, operation = OutboxEntity.DELETE, payload = event.remoteId, sendUpdates = sendUpdates))
                }
            }
        }
        engine.recompute(event.seriesMasterId ?: eventId)
        if (toGoogle) scheduler.syncNow()
        reminders.changed()
    }

    /**
     * Moves an event (a whole series) to another calendar: Google's move within the same account, otherwise a copy
     * in the new calendar and a deletion from the old one.
     */
    suspend fun move(eventId: String, calendarId: String, sendUpdates: Boolean = true) {
        val event = events.get(eventId) ?: return
        if (event.calendarId == calendarId || event.seriesMasterId != null) return
        val sameAccount = event.calendarId.substringBefore('|') == calendarId.substringBefore('|')
        if (event.remoteId == null || !sameAccount) {
            // Not on Google yet, or another account: delete there (if needed) and create anew in the destination.
            if (event.remoteId != null) delete(eventId, sendUpdates)
            else db.withTransaction {
                outbox.discardFor(eventId)
                events.deleteWithSeries(listOf(eventId))
            }
            save(event.copy(calendarId = calendarId, remoteId = null, etag = null, iCalUid = null, meetLink = null), sendUpdates)
            return
        }
        db.withTransaction {
            events.upsert(listOf(event.copy(calendarId = calendarId, updatedAt = System.currentTimeMillis())))
            outbox.insert(OutboxEntity(eventId = eventId, calendarId = event.calendarId, operation = OutboxEntity.MOVE, payload = calendarId, sendUpdates = sendUpdates))
        }
        engine.recompute(eventId)
        scheduler.syncNow()
        reminders.changed()
    }
}
