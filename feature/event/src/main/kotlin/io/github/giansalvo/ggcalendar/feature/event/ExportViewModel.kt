package io.github.giansalvo.ggcalendar.feature.event

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.giansalvo.ggcalendar.core.data.EventRepository
import io.github.giansalvo.ggcalendar.core.model.IcsWriter
import java.time.LocalDate
import javax.inject.Inject

/** The text of an export and how many events it holds. */
class ExportResult(val content: String, val count: Int)

@HiltViewModel
class ExportViewModel @Inject constructor(private val repository: EventRepository) : ViewModel() {
    /** One `.ics` with every event of [calendarIds] that has an occurrence from [from] to [to] (both included). */
    suspend fun ics(calendarIds: Collection<String>, from: LocalDate, to: LocalDate): ExportResult {
        val items = repository.forExport(calendarIds, from, to)
        val events = items.flatMap { EventShare.seriesEvents(it.event, it.calendar, it.exceptions) }
        return ExportResult(IcsWriter.write(events), items.size)
    }
}
