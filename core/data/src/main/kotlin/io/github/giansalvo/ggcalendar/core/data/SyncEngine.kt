package io.github.giansalvo.ggcalendar.core.data

import androidx.room.withTransaction
import io.github.giansalvo.ggcalendar.core.auth.AuthorizationOutcome
import io.github.giansalvo.ggcalendar.core.auth.GoogleAuthorizer
import io.github.giansalvo.ggcalendar.core.database.CalendarEntity
import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.database.SyncMessageEntity
import io.github.giansalvo.ggcalendar.core.model.CalendarInfo
import io.github.giansalvo.ggcalendar.core.network.GoogleApiException
import io.github.giansalvo.ggcalendar.core.network.GoogleCalendarApi
import io.github.giansalvo.ggcalendar.core.network.GoogleSyncPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read sync with Google Calendar (milestone M1), ported from the desktop app's GoogleSyncService: calendar list, then
 * each calendar's events through its sync token. The push of local changes (outbox) arrives with the editor in M3.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val db: GGCalendarDatabase,
    private val api: GoogleCalendarApi,
    private val authorizer: GoogleAuthorizer,
    private val prefs: AppPreferences,
) {
    private val mutex = Mutex()
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val accounts get() = db.accountDao()
    private val calendars get() = db.calendarDao()
    private val events get() = db.eventDao()
    private val instances get() = db.instanceDao()
    private val messages get() = db.syncMessageDao()

    /** The days the instances cover; null until first read from the preferences. */
    @Volatile private var window: InstanceWindow? = null

    suspend fun syncAll() = mutex.withLock {
        _syncing.value = true
        try {
            updateWindowLocked(null, null)
            accounts.getAll().forEach { syncAccount(it.email) }
        } finally {
            _syncing.value = false
        }
    }

    private suspend fun syncAccount(email: String) {
        try {
            val token = when (val outcome = authorizer.authorize(email)) {
                is AuthorizationOutcome.Authorized -> outcome.accessToken
                is AuthorizationOutcome.NeedsConsent -> {
                    messages.insert(SyncMessageEntity(accountEmail = email, kind = "auth", text = "Access to Google Calendar must be granted again"))
                    return
                }
            }
            val remote = api.listCalendars(token)
            val existing = calendars.getForAccount(email).associateBy { it.id }
            val current = remote.map { r ->
                val id = CalendarInfo.googleId(email, r.id)
                // Keep the user's visibility choice and the sync position; names, colors and roles follow Google.
                val entity = existing[id]?.copy(
                    name = r.name, color = r.colorHex, accessRole = r.accessRole, isPrimary = r.isPrimary,
                    timeZone = r.timeZone, defaultReminders = r.defaultReminders,
                ) ?: CalendarEntity(
                    id = id, accountEmail = email, remoteId = r.id, name = r.name, color = r.colorHex,
                    accessRole = r.accessRole, isPrimary = r.isPrimary, timeZone = r.timeZone, defaultReminders = r.defaultReminders,
                )
                calendars.upsert(entity)
                entity
            }
            // Calendars removed or unsubscribed on Google.
            (existing.keys - current.map { it.id }.toSet()).forEach { calendars.delete(it) }

            current.forEach { cal ->
                try {
                    pullCalendar(cal, token)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    messages.insert(SyncMessageEntity(accountEmail = email, kind = "error", text = "${cal.name}: ${e.message}"))
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            messages.insert(SyncMessageEntity(accountEmail = email, kind = "error", text = e.message ?: e.toString()))
        }
    }

    private suspend fun pullCalendar(cal: CalendarEntity, accessToken: String) {
        val remoteId = cal.remoteId ?: return
        val known = events.remoteIds(cal.id)
        var syncToken = cal.syncToken
        // A token without any local event means the events were lost: an incremental sync would never bring them back.
        if (syncToken != null && known.isEmpty()) syncToken = null
        val page: GoogleSyncPage = try {
            api.listEvents(remoteId, syncToken, accessToken)
        } catch (e: GoogleApiException) {
            if (e.status != 410 || syncToken == null) throw e
            syncToken = null
            api.listEvents(remoteId, null, accessToken)
        }

        val links = known.associate { it.remoteId to it.id }.toMutableMap()
        val seen = HashSet<String>()
        val upserts = mutableListOf<EventEntity>()
        val deletes = mutableListOf<String>()

        // Masters first, so the occurrences of a series can be linked to their master within the same page.
        val ordered = page.items.filter { it.recurringEventId == null } + page.items.filter { it.recurringEventId != null }
        for (g in ordered) {
            seen += g.id
            val localId = links[g.id]
            val masterId = g.recurringEventId?.let { links[it] }
            if (g.cancelled) {
                if (g.recurringEventId != null && g.originalStart != null) {
                    if (masterId == null) continue
                    val id = localId ?: UUID.randomUUID().toString()
                    upserts += g.toTombstone(id, cal.id, masterId)
                    links[g.id] = id
                } else if (localId != null) {
                    deletes += localId
                }
                continue
            }
            if (g.time == null) continue
            val id = localId ?: UUID.randomUUID().toString()
            upserts += g.toEntity(id, cal.id, masterId, cal.defaultReminders)
            links[g.id] = id
        }

        // After a full sync, anything we hold that Google no longer lists was deleted remotely.
        if (syncToken == null) known.filter { it.remoteId !in seen }.mapTo(deletes) { it.id }

        db.withTransaction {
            if (upserts.isNotEmpty()) events.upsert(upserts)
            if (deletes.isNotEmpty()) events.deleteWithSeries(deletes)
            calendars.setSyncToken(cal.id, page.nextSyncToken)
            val affected = if (syncToken == null) {
                events.getForCalendar(cal.id).map { it.seriesMasterId ?: it.id }.toSet()
            } else {
                upserts.map { it.seriesMasterId ?: it.id }.toSet()
            }
            affected.forEach { recomputeInstances(it) }
        }
    }

    /**
     * Moves the instance window to a new day or time zone, or extends it to the days [from, to) that a view shows,
     * and rebuilds the instances that change. Cheap when nothing changed: that check runs without the lock.
     */
    suspend fun updateWindow(from: LocalDate? = null, to: LocalDate? = null) {
        val cached = window
        val unchanged = cached != null &&
            InstanceWindows.plan(cached, LocalDate.now(), ZoneId.systemDefault().id, from, to).scope == InstanceWindows.Scope.NONE
        if (!unchanged) mutex.withLock { updateWindowLocked(from, to) }
    }

    private suspend fun updateWindowLocked(from: LocalDate?, to: LocalDate?) {
        val plan = InstanceWindows.plan(window ?: prefs.instanceWindow(), LocalDate.now(), ZoneId.systemDefault().id, from, to)
        window = plan.window
        if (plan.scope == InstanceWindows.Scope.NONE) return
        db.withTransaction {
            val ids = if (plan.scope == InstanceWindows.Scope.ALL) events.masterIds() else events.seriesIds()
            ids.forEach { recomputeInstances(it) }
        }
        prefs.setInstanceWindow(plan.window)
    }

    /** Rebuilds the instances of an event and of its exceptions (a series' modified or cancelled occurrences). */
    private suspend fun recomputeInstances(eventId: String) {
        val event = events.get(eventId) ?: return
        val zone = ZoneId.systemDefault()
        val days = window ?: InstanceWindows.default(LocalDate.now(), zone.id)
        val range = RecurrenceExpander.Window(days.from.atStartOfDay(zone).toInstant(), days.to.atStartOfDay(zone).toInstant())
        val exceptions = if (event.rrule != null) events.getExceptions(eventId) else emptyList()
        instances.deleteForEvent(eventId)
        instances.insert(RecurrenceExpander.expand(event, exceptions, range, zone))
        exceptions.forEach { ex ->
            instances.deleteForEvent(ex.id)
            RecurrenceExpander.single(ex, zone)?.let { instances.insert(listOf(it)) }
        }
    }
}
