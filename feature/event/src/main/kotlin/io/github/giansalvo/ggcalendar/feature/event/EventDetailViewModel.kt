package io.github.giansalvo.ggcalendar.feature.event

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.giansalvo.ggcalendar.core.data.EventDetail
import io.github.giansalvo.ggcalendar.core.data.EventRepository
import io.github.giansalvo.ggcalendar.core.data.Occurrence
import io.github.giansalvo.ggcalendar.core.data.SeriesScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EventDetailViewModel @Inject constructor(private val repository: EventRepository) : ViewModel() {
    fun observe(eventId: String): Flow<EventDetail?> = repository.observe(eventId)

    /** The `.ics` of the occurrence or of the whole series, with the series' exceptions read from the database. */
    suspend fun ics(detail: EventDetail, instance: io.github.giansalvo.ggcalendar.core.model.EventInstance, wholeSeries: Boolean): String {
        val master = EventShare.master(detail)
        val exceptions = if (master != null && wholeSeries) repository.exceptionsOf(master.id) else emptyList()
        return EventShare.ics(detail, instance, wholeSeries, exceptions)
    }

    fun delete(eventId: String, notify: Boolean = true) {
        viewModelScope.launch { repository.writer.delete(eventId, notify) }
    }

    fun deleteOccurrence(masterId: String, occurrence: Occurrence, scope: SeriesScope, notify: Boolean = true) {
        viewModelScope.launch { repository.series.delete(masterId, occurrence, scope, notify) }
    }
}
