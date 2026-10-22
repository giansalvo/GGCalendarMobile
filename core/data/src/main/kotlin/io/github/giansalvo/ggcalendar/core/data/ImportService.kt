package io.github.giansalvo.ggcalendar.core.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.giansalvo.ggcalendar.core.database.CalendarKind
import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.model.EventTime
import io.github.giansalvo.ggcalendar.core.model.IcsImportOptions
import io.github.giansalvo.ggcalendar.core.model.IcsMapper
import io.github.giansalvo.ggcalendar.core.model.IcsReport
import io.github.giansalvo.ggcalendar.core.model.ImportedEvent
import io.github.giansalvo.ggcalendar.core.model.Reminder
import io.github.giansalvo.ggcalendar.core.model.ReminderMethod
import io.github.giansalvo.ggcalendar.core.network.FetchFailure
import io.github.giansalvo.ggcalendar.core.network.FetchResult
import io.github.giansalvo.ggcalendar.core.network.IcsFetcher
import io.github.giansalvo.ggcalendar.core.model.SubscriptionUrls
import io.github.giansalvo.ggcalendar.core.model.UrlCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What goes wrong before anything is written. */
enum class ImportProblem {
    UNREADABLE_FILE, TOO_LARGE, NOT_CALENDAR, EMPTY, NO_EVENTS,
    BAD_ADDRESS, NO_NETWORK, TIMEOUT, TLS, NOT_FOUND, FORBIDDEN, SERVER, OTHER_HTTP, TOO_MANY_REDIRECTS, REDIRECT_NOT_HTTPS,
    NOT_A_LOCAL_CALENDAR,
}

/** What to do with an event that is already in the calendar (same UID there): one choice for the whole file. */
enum class DuplicateAction { SKIP, UPDATE, AS_NEW }

/** The reminders of the events the import makes: those of the file, the default reminder of the settings, or none. */
enum class ReminderChoice { FILE, APP_DEFAULT, NONE }

/** Where the events go: a local calendar that is there, or a new one (made only when the import is done). */
sealed interface ImportTarget {
    data class Existing(val calendarId: String) : ImportTarget
    data class NewCalendar(val name: String, val colorHex: String) : ImportTarget
}

/** The text of a file or of an address, kept to be read again when the destination or the days change. Never printed. */
class ImportSource internal constructor(val label: String, internal val text: String) {
    override fun toString() = "ImportSource(chars=${text.length})"
}

sealed interface ReadOutcome {
    class Ok(val source: ImportSource) : ReadOutcome
    data class Problem(val problem: ImportProblem) : ReadOutcome
}

/** What the file holds, for the summary. */
class ImportSummary(
    val label: String,
    val calendarName: String?,
    /** Events and series (a modified occurrence belongs to its series). */
    val eventCount: Int,
    val seriesCount: Int,
    val firstDay: LocalDate?,
    val lastDay: LocalDate?,
    val ignored: Map<String, Int>,
    val cancelledEvents: Int,
    val orphanOccurrences: Int,
    val method: String?,
    /** Events with at least one reminder written in the file ("15 minutes before"). */
    val eventsWithAlarms: Int,
    val fileHasAlarms: Boolean,
    val hasFloatingTimes: Boolean,
)

sealed interface SummaryOutcome {
    class Ok(val summary: ImportSummary) : SummaryOutcome
    data class Problem(val problem: ImportProblem) : SummaryOutcome
}

/** What an import would do with the days and the destination chosen. */
class ImportPlan(
    /** Events and series kept by the days. */
    val events: Int,
    /** Of those, how many are already in the destination (same UID there). */
    val duplicates: Int,
    /** Over [ImportService.WARN_EVENTS]: the user is asked before it goes on. */
    val needsConfirmation: Boolean,
)

sealed interface ImportOutcome {
    class Done(val calendarId: String, val imported: Int, val updated: Int, val skipped: Int, val ignored: Int, val firstDay: LocalDate?) : ImportOutcome
    data object Cancelled : ImportOutcome
    data class Failed(val problem: ImportProblem) : ImportOutcome
}

class ImportOptions(
    val range: SubscriptionRange = SubscriptionRange(),
    val duplicates: DuplicateAction = DuplicateAction.SKIP,
    val reminders: ReminderChoice = ReminderChoice.APP_DEFAULT,
)

/**
 * Reads a file or an address once and writes its events into a local calendar of the phone: never into a Google calendar, never an
 * outbox entry, never an invitation. Nothing is kept of the address. The write is one transaction: what is not finished is not there.
 */
@Singleton
class ImportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: GGCalendarDatabase,
    private val fetcher: IcsFetcher,
    private val engine: SyncEngine,
    private val reminders: ReminderHooks,
    private val prefs: AppPreferences,
    private val locals: LocalCalendars,
) {
    // ---- reading

    /** Reads a file the system gave us (a content Uri), once, without keeping any permission on it. */
    suspend fun readFile(uri: Uri, label: String? = null): ReadOutcome = withContext(Dispatchers.IO) {
        val bytes = try {
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext ReadOutcome.Problem(ImportProblem.UNREADABLE_FILE)
            input.use { IcsSources.readLimited(it) } ?: return@withContext ReadOutcome.Problem(ImportProblem.TOO_LARGE)
        } catch (e: java.io.IOException) {
            return@withContext ReadOutcome.Problem(ImportProblem.UNREADABLE_FILE)
        } catch (e: SecurityException) {
            return@withContext ReadOutcome.Problem(ImportProblem.UNREADABLE_FILE)
        }
        if (bytes.isEmpty()) return@withContext ReadOutcome.Problem(ImportProblem.EMPTY)
        ReadOutcome.Ok(ImportSource(label ?: displayName(uri) ?: "", IcsSources.decode(bytes)))
    }

    /** Downloads an address once (https, or webcal turned into https). The address is not kept. */
    suspend fun readAddress(address: String): ReadOutcome {
        val url = when (val check = SubscriptionUrls.normalize(address)) {
            is UrlCheck.Ok -> check.url
            is UrlCheck.Rejected -> return ReadOutcome.Problem(ImportProblem.BAD_ADDRESS)
        }
        return when (val result = fetcher.fetch(url)) {
            is FetchResult.Fetched -> ReadOutcome.Ok(ImportSource("", result.body))
            is FetchResult.NotModified -> ReadOutcome.Problem(ImportProblem.OTHER_HTTP)
            is FetchResult.Failed -> ReadOutcome.Problem(result.reason.toProblem())
        }
    }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    // ---- the summary

    suspend fun summarize(source: ImportSource): SummaryOutcome = withContext(Dispatchers.Default) {
        val parsed = IcsMapper.parse(source.text, IcsImportOptions(calendarId = PREVIEW, importAlarms = true))
        problemOf(parsed.report)?.let { return@withContext SummaryOutcome.Problem(it) }
        if (parsed.events.none { it.masterId == null }) return@withContext SummaryOutcome.Problem(ImportProblem.NO_EVENTS)
        val zone = ZoneId.systemDefault()
        val roots = parsed.events.filter { it.masterId == null }
        val days = parsed.events.map { dayOf(it.time, zone) }
        SummaryOutcome.Ok(
            ImportSummary(
                label = source.label,
                calendarName = parsed.report.calendarName?.trim()?.takeIf { it.isNotEmpty() }?.take(LocalCalendars.MAX_NAME),
                eventCount = roots.size,
                seriesCount = roots.count { it.rrule != null },
                firstDay = days.minOrNull(), lastDay = days.maxOrNull(),
                ignored = parsed.report.ignoredComponents,
                cancelledEvents = parsed.report.cancelledEvents + parsed.report.cancelledOccurrences,
                orphanOccurrences = parsed.report.orphanOccurrences,
                method = parsed.report.method,
                eventsWithAlarms = roots.count { it.hasAlarm },
                fileHasAlarms = parsed.report.fileHasAlarms,
                hasFloatingTimes = parsed.report.issues.any { it.code == io.github.giansalvo.ggcalendar.core.model.IcsIssueCode.UNKNOWN_TZID } ||
                    roots.any { (it.time as? EventTime.Timed)?.zoneId == null },
            ),
        )
    }

    /** How many events the days keep and how many of them are already in [calendarId] (null: a new calendar, none). */
    suspend fun plan(source: ImportSource, calendarId: String?, range: SubscriptionRange): ImportPlan = withContext(Dispatchers.Default) {
        val id = calendarId ?: PREVIEW
        val kept = keep(source, id, range, null) ?: return@withContext ImportPlan(0, 0, false)
        val existing = if (calendarId == null) emptySet() else db.eventDao().getForCalendar(calendarId).mapTo(HashSet()) { it.id }
        val roots = kept.filter { it.seriesMasterId == null }
        ImportPlan(roots.size, roots.count { it.id in existing }, roots.size > WARN_EVENTS)
    }

    // ---- the import

    /**
     * Writes the events. The preparation (reading, days, duplicates) reports its progress and can be cancelled; the write is one
     * transaction that cannot, and it is short. [onProgress] gets (done, total) of the preparation.
     */
    suspend fun import(
        source: ImportSource,
        target: ImportTarget,
        options: ImportOptions,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): ImportOutcome {
        val newId = "local:" + UUID.randomUUID()
        val calendarId = when (target) {
            is ImportTarget.Existing -> {
                val calendar = db.calendarDao().get(target.calendarId)
                if (calendar == null || calendar.kind != CalendarKind.LOCAL) return ImportOutcome.Failed(ImportProblem.NOT_A_LOCAL_CALENDAR)
                calendar.id
            }
            is ImportTarget.NewCalendar -> newId
        }
        val appDefault = AppPreferences.remindersFor(prefs.defaultReminderMinutes.first())
        val prepared = withContext(Dispatchers.Default) {
            val kept = keep(source, calendarId, options.range, onProgress, isCancelled) ?: return@withContext null
            kept
        } ?: return if (isCancelled()) ImportOutcome.Cancelled else ImportOutcome.Failed(ImportProblem.NOT_CALENDAR)
        if (isCancelled()) return ImportOutcome.Cancelled

        val now = System.currentTimeMillis()
        val result = db.withTransaction {
            if (target is ImportTarget.NewCalendar) locals.create(target.name.ifBlank { "Calendar" }, target.colorHex, prefs.defaultReminderMinutes.first(), newId)
            val existing = db.eventDao().getForCalendar(calendarId).associateBy { it.id }
            val roots = prepared.filter { it.seriesMasterId == null }
            // The UIDs that already have a row here: what the choice about duplicates is about.
            val duplicateUids = prepared.filter { it.id in existing }.mapNotNull { it.iCalUid }.toSet()
            val asNew = options.duplicates == DuplicateAction.AS_NEW && duplicateUids.isNotEmpty()
            val suffix = UUID.randomUUID().toString().take(8)
            fun renamed(e: EventEntity): EventEntity {
                if (!asNew || e.iCalUid !in duplicateUids) return e
                val from = "$calendarId|${e.iCalUid}"
                val to = "$from~$suffix"
                fun fix(id: String) = if (id == from || id.startsWith("$from|")) to + id.removePrefix(from) else id
                return e.copy(id = fix(e.id), seriesMasterId = e.seriesMasterId?.let(::fix))
            }
            val toWrite = ArrayList<EventEntity>(prepared.size)
            var imported = 0
            var updated = 0
            var skipped = 0
            for (e in prepared) {
                val row = renamed(e)
                val had = existing[row.id]
                when {
                    had == null -> {
                        toWrite += row.copy(reminders = remindersOf(options.reminders, e, appDefault), hasAlarm = hasAlarm(remindersOf(options.reminders, e, appDefault)), updatedAt = now)
                        if (e.seriesMasterId == null) imported++
                    }
                    options.duplicates == DuplicateAction.UPDATE -> {
                        // The event that is there keeps its own reminders and color: the choice about reminders is for the events made now.
                        toWrite += row.copy(reminders = had.reminders, hasAlarm = had.hasAlarm, color = had.color, updatedAt = now)
                        if (e.seriesMasterId == null) updated++
                    }
                    else -> if (e.seriesMasterId == null) skipped++
                }
            }
            if (toWrite.isNotEmpty()) db.eventDao().upsert(toWrite)
            val rootIds = toWrite.map { it.seriesMasterId ?: it.id }.toSet()
            if (rootIds.isNotEmpty()) engine.recomputeMany(rootIds)
            Counts(imported, updated, skipped, roots.size, toWrite)
        }
        // The instances are rebuilt and the alarms planned once, for the whole import.
        if (result.written.isNotEmpty()) reminders.changed()
        val zone = ZoneId.systemDefault()
        val first = result.written.filter { it.seriesMasterId == null }.mapNotNull { eventDay(it, zone) }.minOrNull()
        val ignored = IcsMapper.parse(source.text, IcsImportOptions(calendarId = PREVIEW, importAlarms = false)).report.let { r -> r.ignoredComponents.values.sum() + r.cancelledEvents }
        return ImportOutcome.Done(calendarId, result.imported, result.updated, result.skipped, ignored, first)
    }

    private class Counts(val imported: Int, val updated: Int, val skipped: Int, val total: Int, val written: List<EventEntity>)

    // ---- helpers

    /** The events of the source, as rows of [calendarId], that the days keep; null when it cannot be read or the work was cancelled. */
    private fun keep(
        source: ImportSource,
        calendarId: String,
        range: SubscriptionRange,
        onProgress: ((Int, Int) -> Unit)?,
        isCancelled: () -> Boolean = { false },
    ): List<EventEntity>? {
        val parsed = IcsMapper.parse(source.text, IcsImportOptions(calendarId = calendarId, importAlarms = true))
        if (problemOf(parsed.report) != null) return null
        val total = parsed.events.size
        val rows = ArrayList<EventEntity>(total)
        parsed.events.forEachIndexed { i, e ->
            if (i % 500 == 0) {
                if (isCancelled()) return null
                onProgress?.invoke(i, total)
            }
            rows += e.toLocalEntity(calendarId)
        }
        onProgress?.invoke(total, total)
        return DateRangeFilter.keep(rows, range, ZoneId.systemDefault())
    }

    private fun remindersOf(choice: ReminderChoice, e: EventEntity, appDefault: List<Reminder>): List<Reminder> = when (choice) {
        ReminderChoice.FILE -> e.reminders.orEmpty()
        ReminderChoice.APP_DEFAULT -> appDefault
        ReminderChoice.NONE -> emptyList()
    }

    private fun hasAlarm(list: List<Reminder>) = list.any { it.method == ReminderMethod.ALARM }

    private fun problemOf(report: IcsReport): ImportProblem? = if (report.fatal != null) ImportProblem.NOT_CALENDAR else null

    private fun dayOf(time: EventTime, zone: ZoneId): LocalDate = when (time) {
        is EventTime.Timed -> Instant.ofEpochMilli(time.start.toEpochMilli()).atZone(zone).toLocalDate()
        is EventTime.AllDay -> time.startDay
    }

    private fun eventDay(e: EventEntity, zone: ZoneId): LocalDate? = when {
        e.allDay -> e.startDay?.let(LocalDate::ofEpochDay)
        else -> e.startUtc?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    }

    private fun FetchFailure.toProblem(): ImportProblem = when (this) {
        FetchFailure.BAD_ADDRESS -> ImportProblem.BAD_ADDRESS
        FetchFailure.NO_NETWORK -> ImportProblem.NO_NETWORK
        FetchFailure.TIMEOUT -> ImportProblem.TIMEOUT
        FetchFailure.TLS -> ImportProblem.TLS
        FetchFailure.NOT_FOUND -> ImportProblem.NOT_FOUND
        FetchFailure.FORBIDDEN -> ImportProblem.FORBIDDEN
        FetchFailure.SERVER -> ImportProblem.SERVER
        FetchFailure.OTHER_HTTP -> ImportProblem.OTHER_HTTP
        FetchFailure.TOO_MANY_REDIRECTS -> ImportProblem.TOO_MANY_REDIRECTS
        FetchFailure.REDIRECT_NOT_HTTPS -> ImportProblem.REDIRECT_NOT_HTTPS
        FetchFailure.TOO_LARGE -> ImportProblem.TOO_LARGE
        FetchFailure.NOT_CALENDAR -> ImportProblem.NOT_CALENDAR
        FetchFailure.EMPTY -> ImportProblem.EMPTY
    }

    companion object {
        /** Over this many events (after the days) the user is asked before the import goes on. */
        const val WARN_EVENTS = 20_000
        private const val PREVIEW = "local:preview"
    }
}

/**
 * The row of an event read from a file, in a local calendar: a normal event of the user (writable, no guests, no Meet, never an
 * outbox entry). Its reminders are set by the caller from the choice of the import.
 */
internal fun ImportedEvent.toLocalEntity(calendarId: String): EventEntity {
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
        reminders = reminders,
        hasAlarm = false,
    )
    val withTime = when (val t = time) {
        is EventTime.Timed -> base.copy(allDay = false, startUtc = t.start.toEpochMilli(), endUtc = t.end.toEpochMilli(), timeZone = t.zoneId)
        is EventTime.AllDay -> base.copy(allDay = true, startDay = t.startDay.toEpochDay(), endDay = t.endDay.toEpochDay())
    }
    val key = recurrenceId ?: return withTime
    return if ('T' in key) withTime.copy(originalStartUtc = Instant.parse(key).toEpochMilli())
    else withTime.copy(originalStartDay = LocalDate.parse(key).toEpochDay())
}
