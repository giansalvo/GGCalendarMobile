package io.github.giansalvo.ggcalendar.core.data

import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Edits, deletions and drags of one occurrence of a series, for the scope the user chose (see [SeriesOps]). */
@Singleton
class SeriesEditor @Inject constructor(
    private val db: GGCalendarDatabase,
    private val writer: EventWriter,
) {
    private val events get() = db.eventDao()

    suspend fun save(masterId: String, occ: Occurrence, edited: EventEntity, scope: SeriesScope, sendUpdates: Boolean = true) {
        val master = events.get(masterId) ?: return
        val zone = ZoneId.systemDefault()
        when {
            scope == SeriesScope.THIS -> writer.save(SeriesOps.exception(master, occ, edited), sendUpdates)
            scope == SeriesScope.ALL || SeriesOps.isFirst(master, occ) -> {
                val shifted = SeriesOps.all(master, occ, edited, events.getExceptions(masterId), zone)
                // Modified occurrences only follow locally: Google moves them with the series.
                if (shifted.children.isNotEmpty()) events.upsert(shifted.children)
                writer.save(shifted.master, sendUpdates)
            }
            else -> {
                val split = SeriesOps.following(master, occ, edited, before(master, occ, zone), zone)
                writer.save(split.master, sendUpdates)
                writer.save(split.following, sendUpdates)
            }
        }
    }

    suspend fun delete(masterId: String, occ: Occurrence, scope: SeriesScope, sendUpdates: Boolean = true) {
        val master = events.get(masterId) ?: return
        when (scope) {
            SeriesScope.THIS -> writer.save(SeriesOps.deleteThis(master, occ), sendUpdates)
            SeriesScope.FOLLOWING -> SeriesOps.deleteFollowing(master, occ, ZoneId.systemDefault())
                ?.let { writer.save(it, sendUpdates) } ?: writer.delete(masterId, sendUpdates)
            SeriesScope.ALL -> writer.delete(masterId, sendUpdates)
        }
    }

    /** Occurrences of the series before [occ], to keep a COUNT when the series is split. */
    private suspend fun before(master: EventEntity, occ: Occurrence, zone: ZoneId): Int {
        val start = master.startUtc ?: master.startDay?.let { java.time.LocalDate.ofEpochDay(it).atStartOfDay(zone).toInstant().toEpochMilli() } ?: return 0
        val window = RecurrenceExpander.Window(Instant.ofEpochMilli(start), Instant.ofEpochMilli(occ.beginUtc))
        return RecurrenceExpander.expand(master, emptyList(), window, zone).count { it.beginUtc < occ.beginUtc }
    }
}
