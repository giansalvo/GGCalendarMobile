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

    fun delete(eventId: String) {
        viewModelScope.launch { repository.writer.delete(eventId) }
    }

    fun deleteOccurrence(masterId: String, occurrence: Occurrence, scope: SeriesScope) {
        viewModelScope.launch { repository.series.delete(masterId, occurrence, scope) }
    }
}
