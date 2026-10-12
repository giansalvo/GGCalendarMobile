package io.github.giansalvo.ggcalendar.feature.event

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.giansalvo.ggcalendar.core.data.EventRepository
import io.github.giansalvo.ggcalendar.core.model.IcsWriter
import java.time.LocalDate
import javax.inject.Inject

/** The text of an export, how many events it holds and how many were left out because they are private. */
class ExportResult(val content: String, val count: Int, val skippedPrivate: Int)

@HiltViewModel
class ExportViewModel @Inject constructor(private val repository: EventRepository) : ViewModel() {
    /** One `.ics` with every event of [calendarIds] that has an occurrence from [from] to [to] (both included). */
    suspend fun ics(calendarIds: Collection<String>, from: LocalDate, to: LocalDate): ExportResult {
        val (privateItems, items) = repository.forExport(calendarIds, from, to).partition { it.event.visibility == "PRIVATE" }
        // A private modified occurrence of a series stays out too; the series then shows its regular one in its place.
        val events = items.flatMap { item ->
            EventShare.seriesEvents(item.event, item.calendar, item.exceptions.filter { it.visibility != "PRIVATE" })
        }
        return ExportResult(IcsWriter.write(events), items.size, privateItems.size)
    }
}
