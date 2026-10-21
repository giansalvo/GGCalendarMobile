package io.github.giansalvo.ggcalendar.core.data

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.database.OutboxEntity
import io.github.giansalvo.ggcalendar.core.database.SyncMessageEntity
import io.github.giansalvo.ggcalendar.core.model.Diag
import io.github.giansalvo.ggcalendar.core.network.GoogleApiException
import io.github.giansalvo.ggcalendar.core.network.GoogleCalendarApi
import io.github.giansalvo.ggcalendar.core.network.GoogleEvent
import io.github.giansalvo.ggcalendar.core.network.GoogleEventMapper
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends the queued local changes of one account to Google, ported from the desktop `GoogleSyncService.PushAsync` and
 * `ResolveConflict`. Updates apply only to the Google version they started from (etag); when Google changed the event
 * meanwhile, Google's version wins and the local one is kept as a "(conflict)" copy.
 */
@Singleton
class OutboxPusher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: GGCalendarDatabase,
    private val api: GoogleCalendarApi,
) {
    private enum class Outcome { DONE, LATER, CONFLICT }

    private val events get() = db.eventDao()
    private val calendars get() = db.calendarDao()
    private val outbox get() = db.outboxDao()
    private val messages get() = db.syncMessageDao()

    /**
     * Pushes the queue of [email] in order. [recompute] rebuilds an event's instances. Returns true if conflict copies
     * were queued (the caller pushes again after the pull, so that they reach Google in the same sync).
     */
    suspend fun push(email: String, accessToken: String, recompute: suspend (String) -> Unit): Boolean {
        var conflicts = false
        val conflicted = HashSet<String>()
        val queue = outbox.getAll()
        Diag.log {
            "outbox push ${Diag.who(email)}: ${queue.size} queued [" + queue.joinToString("; ") {
                "#${it.seq} ${it.operation} cal=${if (it.calendarId.startsWith("local:")) "local" else Diag.who(it.calendarId.substringBefore('|'))} ev=${it.eventId.takeLast(6)} tries=${it.attempts} err=${it.lastError?.take(50)}"
            } + "]"
        }
        for (item in queue) {
            if (item.calendarId.substringBefore('|') != email || item.eventId in conflicted) continue
            val remoteCalendar = calendars.get(item.calendarId)?.remoteId
            if (remoteCalendar == null) {
                outbox.complete(item.seq) // the calendar is gone
                continue
            }
            try {
                val outcome = when (item.operation) {
                    OutboxEntity.DELETE -> delete(item, remoteCalendar, accessToken)
                    OutboxEntity.MOVE -> move(item, remoteCalendar, accessToken)
                    else -> sendSnapshot(item, remoteCalendar, email, accessToken, recompute)
                }
                when (outcome) {
                    Outcome.DONE -> outbox.complete(item.seq)
                    Outcome.LATER -> Unit
                    Outcome.CONFLICT -> {
                        conflicted += item.eventId
                        conflicts = true
                    }
                }
            } catch (e: GoogleApiException) {
                if (!e.isRateLimit && (e.status == 400 || e.status == 403 || e.status == 404)) {
                    // A permanent rejection must not block the rest of the queue.
                    outbox.complete(item.seq)
                    // The local change will never reach Google: the next pull of this calendar is a full one, so that
                    // the phone shows Google's version again.
                    calendars.setSyncToken(item.calendarId, null)
                    messages.insert(SyncMessageEntity(accountEmail = email, kind = "error", text = context.getString(R.string.message_rejected, e.status, e.message)))
                } else {
                    outbox.failed(item.seq, e.message)
                    return conflicts // 401, 5xx, 429 and 403 "rate limit": try again at the next sync, keeping the order
                }
            } catch (e: IOException) {
                outbox.failed(item.seq, e.message)
                return conflicts
            }
        }
        return conflicts
    }

    private suspend fun delete(item: OutboxEntity, remoteCalendar: String, accessToken: String): Outcome {
        // A cancelled occurrence never pushed has no id of its own: it is the series' instance at its original start.
        val target = item.payload ?: run {
            val tombstone = events.get(item.eventId) ?: return Outcome.DONE
            val master = tombstone.seriesMasterId?.let { events.get(it) } ?: return Outcome.DONE
            val masterRemote = master.remoteId ?: return Outcome.LATER
            GoogleEventMapper.instanceId(masterRemote, tombstone.originalStart() ?: return Outcome.DONE)
        }
        api.deleteEvent(remoteCalendar, target, item.sendUpdates, accessToken)
        return Outcome.DONE
    }

    private suspend fun move(item: OutboxEntity, remoteCalendar: String, accessToken: String): Outcome {
        val current = events.get(item.eventId) ?: return Outcome.DONE
        val remoteId = current.remoteId ?: return Outcome.DONE
        val destination = item.payload?.let { calendars.get(it) }?.remoteId ?: return Outcome.DONE
        val moved = api.moveEvent(remoteCalendar, remoteId, destination, item.sendUpdates, accessToken)
        events.setRemote(current.id, moved.id, moved.etag)
        return Outcome.DONE
    }

    private suspend fun sendSnapshot(
        item: OutboxEntity,
        remoteCalendar: String,
        email: String,
        accessToken: String,
        recompute: suspend (String) -> Unit,
    ): Outcome {
        val snapshot = item.payload?.let { JSON.decodeFromString<EventEntity>(it) } ?: return Outcome.DONE
        val current = events.get(item.eventId) ?: return Outcome.DONE // deleted meanwhile
        var target = current.remoteId
        var masterRemote: String? = null
        snapshot.seriesMasterId?.let { masterId ->
            // A modified occurrence is an existing Google instance of the series: patch it instead of inserting.
            val master = events.get(masterId) ?: return Outcome.DONE
            masterRemote = master.remoteId ?: return Outcome.LATER // the series has not reached Google yet
            if (target == null) target = GoogleEventMapper.instanceId(masterRemote!!, snapshot.originalStart() ?: return Outcome.DONE)
        }
        val base = item.base?.let { JSON.decodeFromString<EventEntity>(it) }
        val google = snapshot.toGoogleEvent(masterRemote)
        val meetRequest = snapshot.addMeet && snapshot.meetLink == null && base?.addMeet != true
        val removeMeet = !snapshot.addMeet && base?.addMeet == true

        val result: GoogleEvent = if (target == null) {
            api.insertEvent(remoteCalendar, GoogleEventMapper.toGoogle(google, meetRequest, patch = false), item.sendUpdates, accessToken)
        } else {
            val body = GoogleEventMapper.toGoogle(google, meetRequest, patch = true, removeMeet = removeMeet)
            // Patch only the version we last saw; a modified occurrence's instance id has no known version.
            val etag = if (target == current.remoteId) current.etag else null
            try {
                api.patchEvent(remoteCalendar, target!!, body, item.sendUpdates, accessToken, etag)
            } catch (e: GoogleApiException) {
                if (e.status != 412) throw e
                val remote = api.getEvent(remoteCalendar, target!!, accessToken)
                val defaults = calendars.get(item.calendarId)?.defaultReminders.orEmpty()
                val remoteEntity = remote?.toEntity(current.id, current.calendarId, current.seriesMasterId, defaults)
                // Google also changes the version on its own (Meet conference, a guest's reply): if what the user sees
                // is still what the edit started from, it is not a conflict.
                var patched: GoogleEvent? = null
                if (remoteEntity != null && base != null && EventContent.same(remoteEntity, base, ignoreGuest = email)) {
                    patched = try {
                        api.patchEvent(remoteCalendar, target!!, body, item.sendUpdates, accessToken, remote.etag)
                    } catch (again: GoogleApiException) {
                        if (again.status != 412) throw again
                        null // changed again meanwhile: a real conflict
                    }
                }
                patched ?: run {
                    resolveConflict(item, email, remoteEntity, recompute)
                    return Outcome.CONFLICT
                }
            }
        }

        if (outbox.hasPendingAfter(item.eventId, item.seq)) {
            events.setRemote(item.eventId, result.id, result.etag)
        } else {
            events.upsert(
                listOf(
                    snapshot.copy(
                        remoteId = result.id,
                        etag = result.etag,
                        meetLink = result.meetLink ?: snapshot.meetLink,
                        addMeet = snapshot.addMeet || result.hasConference,
                    ),
                ),
            )
        }
        return Outcome.DONE
    }

    /**
     * The event changed on Google after the version the local edit was based on. Google's version ([remote], null if
     * deleted there) is applied and the local edit becomes a separate "(conflict)" event in the same calendar, so
     * neither is lost.
     */
    private suspend fun resolveConflict(item: OutboxEntity, email: String, remote: EventEntity?, recompute: suspend (String) -> Unit) {
        // The last queued snapshot carries every local edit made since the last sync.
        val local = outbox.getFor(item.eventId)
            .filter { it.operation == OutboxEntity.CREATE || it.operation == OutboxEntity.UPDATE }
            .mapNotNull { it.payload?.let { p -> JSON.decodeFromString<EventEntity>(p) } }
            .lastOrNull() ?: return
        val title = local.title.ifBlank { context.getString(R.string.untitled) }
        val copy = local.copy(
            id = UUID.randomUUID().toString(),
            title = title + context.getString(R.string.conflict_suffix),
            remoteId = null,
            etag = null,
            iCalUid = null,
            attendees = emptyList(),
            sendInvites = false,
            addMeet = false,
            meetLink = null,
            seriesMasterId = null,
            originalStartUtc = null,
            originalStartDay = null,
            updatedAt = System.currentTimeMillis(),
        )
        db.withTransaction {
            outbox.discardFor(item.eventId)
            if (remote == null) events.deleteWithSeries(listOf(item.eventId)) else events.upsert(listOf(remote))
            events.upsert(listOf(copy))
            outbox.insert(
                OutboxEntity(eventId = copy.id, calendarId = copy.calendarId, operation = OutboxEntity.CREATE, payload = JSON.encodeToString(copy), sendUpdates = false),
            )
            messages.insert(SyncMessageEntity(accountEmail = email, kind = "conflict", text = context.getString(R.string.message_conflict, title, copy.title)))
        }
        if (remote != null) recompute(remote.seriesMasterId ?: remote.id)
        recompute(copy.id)
    }

    internal companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
