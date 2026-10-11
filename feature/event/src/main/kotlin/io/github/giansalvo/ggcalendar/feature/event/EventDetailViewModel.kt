package io.github.giansalvo.ggcalendar.feature.event

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.giansalvo.ggcalendar.core.data.EventDetail
import io.github.giansalvo.ggcalendar.core.data.EventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EventDetailViewModel @Inject constructor(private val repository: EventRepository) : ViewModel() {
    fun observe(eventId: String): Flow<EventDetail?> = repository.observe(eventId)

    fun delete(eventId: String) {
        viewModelScope.launch { repository.writer.delete(eventId) }
    }
}
