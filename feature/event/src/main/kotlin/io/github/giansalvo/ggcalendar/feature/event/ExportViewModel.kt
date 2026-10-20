package io.github.giansalvo.ggcalendar.feature.event

import android.content.res.Resources
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.giansalvo.ggcalendar.core.data.EventRepository
import io.github.giansalvo.ggcalendar.core.model.IcsWriter
import java.time.LocalDate
import javax.inject.Inject

/** The text of an export and how many events (or rows, when [occurrences]) it holds. */
class ExportResult(val content: String, val count: Int, val occurrences: Boolean = false)

@HiltViewModel
class ExportViewModel @Inject constructor(private val repository: EventRepository) : ViewModel() {
    /** A CSV table with a row for each occurrence from [from] to [to] (both included) of the events of [calendarIds]. */
    suspend fun csv(calendarIds: Collection<String>, from: LocalDate, to: LocalDate, res: Resources): ExportResult {
        val found = repository.occurrencesForExport(calendarIds, from, to)
        return ExportResult(CsvExport.csv(found.rows, res), found.rows.size, occurrences = true)
    }

    /**
     * One `.ics` with every event of [calendarIds] that has an occurrence from [from] to [to] (both included). Each event keeps
     * its status (busy or free) and its visibility; private events are in, in full, marked `CLASS:PRIVATE`.
     */
    suspend fun ics(calendarIds: Collection<String>, from: LocalDate, to: LocalDate): ExportResult {
        val items = repository.forExport(calendarIds, from, to)
        val events = items.flatMap { item -> EventShare.seriesEvents(item.event, item.calendar, item.exceptions, forExport = true) }
        return ExportResult(IcsWriter.write(events), items.size)
    }
}
