package io.github.giansalvo.ggcalendar.core.data

import androidx.room.withTransaction
import io.github.giansalvo.ggcalendar.core.database.CalendarEntity
import io.github.giansalvo.ggcalendar.core.database.CalendarKind
import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.model.Diag
import io.github.giansalvo.ggcalendar.core.model.EventTime
import io.github.giansalvo.ggcalendar.core.model.IcsImportOptions
import io.github.giansalvo.ggcalendar.core.model.IcsMapper
import io.github.giansalvo.ggcalendar.core.model.SubscriptionUrls
import io.github.giansalvo.ggcalendar.core.model.UrlCheck
import io.github.giansalvo.ggcalendar.core.network.FetchFailure
import io.github.giansalvo.ggcalendar.core.network.FetchResult
import io.github.giansalvo.ggcalendar.core.network.IcsFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Why a subscription could not be made or brought up to date. The names are stored as the last error of the calendar. */
enum class SubscriptionProblem {
    BAD_ADDRESS, NO_NETWORK, TIMEOUT, TLS, NOT_FOUND, FORBIDDEN, SERVER, OTHER_HTTP, TOO_MANY_REDIRECTS, REDIRECT_NOT_HTTPS,
    TOO_LARGE, NOT_CALENDAR, EMPTY,
    /** More events than [SubscriptionService.MAX_EVENTS]. */
    TOO_MANY_EVENTS,
    /** The file is a calendar but could not be read. */
    UNREADABLE,
    ALREADY_SUBSCRIBED, TOO_MANY_SUBSCRIPTIONS,
    /** The last day is before the first. */
    BAD_RANGE,
    /** The stored address cannot be decrypted (the Keystore key is gone): the subscription has to be made again. */
    ADDRESS_LOST,
}

/** What a download shows before anything is saved. It holds the address: never print it ([toString] does not). */
class SubscriptionPreview internal constructor(
    internal val url: String,
    val name: String,
    val eventCount: Int,
    val firstDay: LocalDate?,
    val lastDay: LocalDate?,
    /** Components of the file that the app does not read, with how many of each. */
    val ignored: Map<String, Int>,
    val orphanOccurrences: Int,
    val isSecretAddress: Boolean,
    internal val fetched: FetchResult.Fetched,
) {
    override fun toString() = "SubscriptionPreview(events=$eventCount)"
}

sealed interface PreviewResult {
    class Ok(val preview: SubscriptionPreview) : PreviewResult
    data class Problem(val problem: SubscriptionProblem, val httpStatus: Int? = null) : PreviewResult
}

/** What a copy of a calendar changed in the database. */
data class SubscriptionChange(val added: Int, val updated: Int, val removed: Int) {
    val isEmpty: Boolean get() = added == 0 && updated == 0 && removed == 0
}

sealed interface AddResult {
    class Added(val calendarId: String, val change: SubscriptionChange) : AddResult
    data class Problem(val problem: SubscriptionProblem) : AddResult
}

sealed interface RefreshResult {
    data class Updated(val change: SubscriptionChange) : RefreshResult
    /** Nothing changed on the other side (304 or the same content). */
    data object Unchanged : RefreshResult
    data class Failed(val problem: SubscriptionProblem) : RefreshResult
}

/**
 * Calendars read from an address (a public calendar of Google or any iCal address), shown in the app and never changed from it.
 * A subscription is a calendar of kind "subscription" with the role "reader": no reminders, no guests, no outbox, not exported.
 * The address is kept encrypted. A new copy replaces the events by difference, in one transaction, and a copy with the same hash
 * writes nothing.
 */
@Singleton
class SubscriptionService @Inject constructor(
    private val db: GGCalendarDatabase,
    private val fetcher: IcsFetcher,
    private val cipher: UrlCipher,
    private val engine: SyncEngine,
    private val reminders: ReminderHooks,
    private val prefs: AppPreferences,
) {
    private val mutex = Mutex()

    /** Test hook: the clock. */
    internal var clock: () -> Long = System::currentTimeMillis

    // ---- before saving

    /** Downloads and reads the address, writes nothing. */
    suspend fun preview(address: String): PreviewResult {
        val url = when (val check = SubscriptionUrls.normalize(address)) {
            is UrlCheck.Ok -> check.url
            is UrlCheck.Rejected -> return PreviewResult.Problem(SubscriptionProblem.BAD_ADDRESS)
        }
        val fetched = when (val result = fetcher.fetch(url)) {
            is FetchResult.Fetched -> result
            is FetchResult.NotModified -> return PreviewResult.Problem(SubscriptionProblem.OTHER_HTTP)
            is FetchResult.Failed -> return PreviewResult.Problem(result.reason.toProblem(), result.httpStatus)
        }
        val parsed = IcsMapper.parse(fetched.body, IcsImportOptions(calendarId = PREVIEW_CALENDAR, importAlarms = false))
        if (parsed.report.fatal != null) return PreviewResult.Problem(SubscriptionProblem.UNREADABLE)
        // The total may be over the limit: the days chosen in the dialog can bring it under (see [countIn]).
        val zone = ZoneId.systemDefault()
        val days = parsed.events.map { dayOf(it.time, zone) }
        val name = parsed.report.calendarName?.trim()?.takeIf { it.isNotEmpty() }?.take(LocalCalendars.MAX_NAME) ?: "Calendar"
        return PreviewResult.Ok(
            SubscriptionPreview(
                url = url, name = name, eventCount = parsed.events.size, firstDay = days.minOrNull(), lastDay = days.maxOrNull(),
                ignored = parsed.report.ignoredComponents, orphanOccurrences = parsed.report.orphanOccurrences,
                isSecretAddress = SubscriptionUrls.isLikelySecret(url), fetched = fetched,
            ),
        )
    }

    /** How many events the days chosen keep of the copy in [preview]: the number the dialog shows while the days are being chosen. */
    suspend fun countIn(preview: SubscriptionPreview, range: SubscriptionRange): Int = withContext(Dispatchers.Default) {
        val entities = IcsMapper.parse(preview.fetched.body, IcsImportOptions(calendarId = PREVIEW_CALENDAR, importAlarms = false))
            .events.map { it.toSubscriptionEntity(PREVIEW_CALENDAR) }
        DateRangeFilter.keep(entities, range, ZoneId.systemDefault()).size
    }

    // ---- making and removing

    /** Saves the calendar of a [preview] with its events. At most [MAX_SUBSCRIPTIONS], and one per address. */
    suspend fun add(preview: SubscriptionPreview, name: String, colorHex: String, range: SubscriptionRange = SubscriptionRange()): AddResult = mutex.withLock {
        if (!range.isValid) return AddResult.Problem(SubscriptionProblem.BAD_RANGE)
        val existing = db.calendarDao().getByKind(CalendarKind.SUBSCRIPTION)
        if (existing.size >= MAX_SUBSCRIPTIONS) return AddResult.Problem(SubscriptionProblem.TOO_MANY_SUBSCRIPTIONS)
        if (existing.any { addressOf(it) == preview.url }) return AddResult.Problem(SubscriptionProblem.ALREADY_SUBSCRIBED)
        val id = "sub:" + UUID.randomUUID()
        val now = clock()
        // The copy is read with the id of the new calendar (the ids of the events carry it) and cut to the days chosen, before anything is saved.
        val events = DateRangeFilter.keep(
            IcsMapper.parse(preview.fetched.body, IcsImportOptions(calendarId = id, importAlarms = false)).events.map { it.toSubscriptionEntity(id) },
            range, ZoneId.systemDefault(),
        )
        if (events.size > MAX_EVENTS) return AddResult.Problem(SubscriptionProblem.TOO_MANY_EVENTS)
        db.calendarDao().upsert(
            CalendarEntity(
                id = id, accountEmail = null, remoteId = null, name = name.trim().ifEmpty { preview.name }.take(LocalCalendars.MAX_NAME), color = colorHex,
                accessRole = READER, isVisible = true, defaultReminders = emptyList(), sortOrder = db.calendarDao().maxSortOrder(CalendarKind.SUBSCRIPTION) + 1,
                kind = CalendarKind.SUBSCRIPTION, sourceUrl = cipher.encrypt(preview.url), sourceHash = preview.fetched.sha256, sourceEtag = preview.fetched.etag,
                lastFetchedAt = now, lastSyncedAt = now,
                rangeFrom = range.from?.toEpochDay(), rangeTo = range.to?.toEpochDay(),
            ),
        )
        val change = apply(id, events)
        AddResult.Added(id, change)
    }

    /** Removes the calendar and its events. */
    suspend fun delete(id: String) {
        val calendar = db.calendarDao().get(id) ?: return
        if (calendar.kind != CalendarKind.SUBSCRIPTION) return
        db.calendarDao().delete(id)
        reminders.changed()
    }

    suspend fun update(id: String, name: String, colorHex: String) {
        val calendar = db.calendarDao().get(id) ?: return
        if (calendar.kind != CalendarKind.SUBSCRIPTION) return
        db.calendarDao().setNameAndColor(id, name.trim().ifEmpty { calendar.name }.take(LocalCalendars.MAX_NAME), colorHex)
    }

    /**
     * Changes the days of a subscription: the address is read again and the days are applied to the new copy (the old copy is not
     * used). The days are kept only if that works; a failure leaves the days and the events as they were and notes the reason on the
     * calendar. A range equal to the stored one does nothing.
     */
    suspend fun setRange(id: String, range: SubscriptionRange): RefreshResult = mutex.withLock {
        if (!range.isValid) return RefreshResult.Failed(SubscriptionProblem.BAD_RANGE)
        val calendar = db.calendarDao().get(id)?.takeIf { it.kind == CalendarKind.SUBSCRIPTION } ?: return RefreshResult.Unchanged
        if (SubscriptionRange.ofDays(calendar.rangeFrom, calendar.rangeTo) == range) return RefreshResult.Unchanged
        refreshLocked(id, force = true, newRange = range)
    }

    /** The days of a subscription. */
    suspend fun rangeOf(id: String): SubscriptionRange = db.calendarDao().get(id)?.let { SubscriptionRange.ofDays(it.rangeFrom, it.rangeTo) } ?: SubscriptionRange()

    /** The address as it can be shown: no key, no owner. */
    suspend fun maskedAddress(id: String): String? = db.calendarDao().get(id)?.let { addressOf(it) }?.let(SubscriptionUrls::mask)

    /** The whole address, for the one place that asks for it on purpose (the "show address" action). */
    suspend fun revealAddress(id: String): String? = db.calendarDao().get(id)?.let { addressOf(it) }

    // ---- keeping up to date

    /** Downloads the calendar again. A copy that did not change writes nothing; a failure keeps the events and notes the reason. */
    suspend fun refresh(id: String): RefreshResult = mutex.withLock { refreshLocked(id) }

    /**
     * The subscriptions whose last attempt is older than the interval of the settings (the same for all), one after the other;
     * stops if the network is gone.
     */
    suspend fun refreshDue(): Int = mutex.withLock {
        val now = clock()
        val interval = prefs.subscriptionMinutes.first() * 60_000L
        // A worker that runs a little early still counts as due: a tenth of the interval, 2 minutes at most (90 seconds for 15 minutes).
        val tolerance = minOf(TOLERANCE_MS, interval / 10)
        var done = 0
        for (calendar in db.calendarDao().getByKind(CalendarKind.SUBSCRIPTION)) {
            val last = calendar.lastSyncedAt ?: 0L
            if (now - last < interval - tolerance) continue
            val result = refreshLocked(calendar.id)
            done++
            if (result is RefreshResult.Failed && result.problem == SubscriptionProblem.NO_NETWORK) break
        }
        done
    }

    /** Every subscription, now: the refresh button. */
    suspend fun refreshAll(): Int = mutex.withLock {
        var done = 0
        for (calendar in db.calendarDao().getByKind(CalendarKind.SUBSCRIPTION)) {
            val result = refreshLocked(calendar.id)
            done++
            if (result is RefreshResult.Failed && result.problem == SubscriptionProblem.NO_NETWORK) break
        }
        done
    }

    private suspend fun refreshLocked(id: String, force: Boolean = false, newRange: SubscriptionRange? = null): RefreshResult {
        val calendar = db.calendarDao().get(id)?.takeIf { it.kind == CalendarKind.SUBSCRIPTION } ?: return RefreshResult.Unchanged
        val now = clock()
        val url = addressOf(calendar)
        if (url == null) {
            db.calendarDao().setSourceFailed(id, now, SubscriptionProblem.ADDRESS_LOST.name)
            return RefreshResult.Failed(SubscriptionProblem.ADDRESS_LOST)
        }
        val fetched = when (val result = fetcher.fetch(url, etag = if (force) null else calendar.sourceEtag)) {
            is FetchResult.NotModified -> {
                db.calendarDao().setSourceFetched(id, calendar.sourceHash, calendar.sourceEtag, now, now)
                return RefreshResult.Unchanged
            }
            is FetchResult.Failed -> {
                val problem = result.reason.toProblem()
                db.calendarDao().setSourceFailed(id, now, problem.name)
                Diag.log { "subscription refresh failed ${result.reason} status=${result.httpStatus}" }
                return RefreshResult.Failed(problem)
            }
            is FetchResult.Fetched -> result
        }
        if (!force && fetched.sha256 == calendar.sourceHash) {
            db.calendarDao().setSourceFetched(id, fetched.sha256, fetched.etag, now, now)
            return RefreshResult.Unchanged
        }
        val range = newRange ?: SubscriptionRange.ofDays(calendar.rangeFrom, calendar.rangeTo)
        val parsed = IcsMapper.parse(fetched.body, IcsImportOptions(calendarId = id, importAlarms = false))
        val wanted = if (parsed.report.fatal != null) emptyList() else DateRangeFilter.keep(parsed.events.map { it.toSubscriptionEntity(id) }, range, ZoneId.systemDefault())
        val problem = when {
            parsed.report.fatal != null -> SubscriptionProblem.UNREADABLE
            wanted.size > MAX_EVENTS -> SubscriptionProblem.TOO_MANY_EVENTS
            else -> null
        }
        if (problem != null) {
            db.calendarDao().setSourceFailed(id, now, problem.name)
            return RefreshResult.Failed(problem)
        }
        if (newRange != null) db.calendarDao().setRange(id, newRange.from?.toEpochDay(), newRange.to?.toEpochDay())
        val change = apply(id, wanted, rebuildAll = newRange != null)
        db.calendarDao().setSourceFetched(id, fetched.sha256, fetched.etag, now, now)
        return RefreshResult.Updated(change)
    }

    /**
     * Brings the events of the calendar to [wanted]: what is new is added, what differs is replaced, what is not in the copy
     * any more is removed, what is equal is not touched. One transaction; the instances are rebuilt for the events that changed
     * (and for the series whose occurrences changed), once.
     */
    internal suspend fun apply(calendarId: String, wanted: List<EventEntity>, rebuildAll: Boolean = false): SubscriptionChange {
        val result = db.withTransaction {
            val current = db.eventDao().getForCalendar(calendarId).associateBy { it.id }
            val wantedById = wanted.associateBy { it.id }
            val added = wanted.filter { it.id !in current }
            // The stored row has the search text of its attachments (EventDao.upsert), the copy just read has not yet: made equal before comparing.
            val updated = wanted.filter { w -> current[w.id]?.let { it.copy(updatedAt = 0) != w.copy(attachmentText = io.github.giansalvo.ggcalendar.core.model.AttachmentLink.searchText(w.attachments)) } == true }
            val removed = current.keys.filter { it !in wantedById }
            if (removed.isNotEmpty()) db.eventDao().deleteWithSeries(removed)
            val written = added + updated
            if (written.isNotEmpty()) db.eventDao().upsert(written)
            // New days change what every series draws, so then all the events are rebuilt.
            val roots = if (rebuildAll) wanted.map { it.seriesMasterId ?: it.id }.toSet()
            else (written + removed.mapNotNull { current[it] }).map { it.seriesMasterId ?: it.id }.toSet() - removed.toSet()
            if (roots.isNotEmpty()) engine.recomputeMany(roots)
            SubscriptionChange(added.size, updated.size, removed.size)
        }
        Diag.log { "subscription applied +${result.added} ~${result.updated} -${result.removed}" }
        return result
    }

    private fun addressOf(calendar: CalendarEntity): String? = calendar.sourceUrl?.let(cipher::decrypt)

    private fun dayOf(time: EventTime, zone: ZoneId): LocalDate = when (time) {
        is EventTime.Timed -> Instant.ofEpochMilli(time.start.toEpochMilli()).atZone(zone).toLocalDate()
        is EventTime.AllDay -> time.startDay
    }

    private fun FetchFailure.toProblem(): SubscriptionProblem = when (this) {
        FetchFailure.BAD_ADDRESS -> SubscriptionProblem.BAD_ADDRESS
        FetchFailure.NO_NETWORK -> SubscriptionProblem.NO_NETWORK
        FetchFailure.TIMEOUT -> SubscriptionProblem.TIMEOUT
        FetchFailure.TLS -> SubscriptionProblem.TLS
        FetchFailure.NOT_FOUND -> SubscriptionProblem.NOT_FOUND
        FetchFailure.FORBIDDEN -> SubscriptionProblem.FORBIDDEN
        FetchFailure.SERVER -> SubscriptionProblem.SERVER
        FetchFailure.OTHER_HTTP -> SubscriptionProblem.OTHER_HTTP
        FetchFailure.TOO_MANY_REDIRECTS -> SubscriptionProblem.TOO_MANY_REDIRECTS
        FetchFailure.REDIRECT_NOT_HTTPS -> SubscriptionProblem.REDIRECT_NOT_HTTPS
        FetchFailure.TOO_LARGE -> SubscriptionProblem.TOO_LARGE
        FetchFailure.NOT_CALENDAR -> SubscriptionProblem.NOT_CALENDAR
        FetchFailure.EMPTY -> SubscriptionProblem.EMPTY
    }

    companion object {
        const val MAX_EVENTS = 5000
        const val MAX_SUBSCRIPTIONS = 10
        /** A worker that runs a little early still counts as due. */
        private const val TOLERANCE_MS = 2 * 60_000L
        private const val READER = "reader"
        private const val PREVIEW_CALENDAR = "sub:preview"
    }
}
