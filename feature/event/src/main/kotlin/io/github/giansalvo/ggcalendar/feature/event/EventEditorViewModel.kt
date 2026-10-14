package io.github.giansalvo.ggcalendar.feature.event

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.giansalvo.ggcalendar.core.data.EventRepository
import io.github.giansalvo.ggcalendar.core.data.Occurrence
import io.github.giansalvo.ggcalendar.core.data.SeriesScope
import io.github.giansalvo.ggcalendar.core.database.CalendarEntity
import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.model.Attendee
import io.github.giansalvo.ggcalendar.core.model.Reminder
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject

/** What the editor opens on: a new event (from the FAB or a tap on the grid) or an existing one. */
sealed interface EditorRequest {
    data class New(val start: LocalDateTime? = null, val allDay: Boolean = false) : EditorRequest
    /** [occurrence]: the occurrence tapped, when the event is a series (the editor shows its date and times). */
    data class Edit(val eventId: String, val occurrence: Occurrence? = null) : EditorRequest
}

/** The editor's fields; dates and times are wall-clock values in [zoneId]. */
data class Draft(
    val id: String,
    val calendarId: String,
    val title: String = "",
    val allDay: Boolean = false,
    val startDate: LocalDate,
    val startTime: LocalTime,
    val endDate: LocalDate,
    val endTime: LocalTime,
    val zoneId: String = ZoneId.systemDefault().id,
    val rrule: String? = null,
    val location: String = "",
    val description: String = "",
    /** Null: the calendar's default reminders. */
    val reminders: List<Reminder>? = null,
    val color: String? = null,
    val availability: String = "BUSY",
    val visibility: String = "DEFAULT",
    val addMeet: Boolean = false,
    val meetLink: String? = null,
    val attendees: List<Attendee> = emptyList(),
    val sendInvites: Boolean = true,
    val guestsCanModify: Boolean = false,
    val guestsCanInvite: Boolean = true,
    val guestsCanSeeList: Boolean = true,
) {
    val zone: ZoneId get() = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())
    val start: LocalDateTime get() = LocalDateTime.of(startDate, startTime)
    val end: LocalDateTime get() = LocalDateTime.of(endDate, endTime)

    /** Moves the start keeping the duration, as in Google Calendar. */
    fun withStart(date: LocalDate = startDate, time: LocalTime = startTime): Draft {
        val minutes = ChronoUnit.MINUTES.between(start, end)
        val days = ChronoUnit.DAYS.between(startDate, endDate)
        val newStart = LocalDateTime.of(date, time)
        return if (allDay) copy(startDate = date, endDate = date.plusDays(days))
        else newStart.plusMinutes(minutes).let { copy(startDate = date, startTime = time, endDate = it.toLocalDate(), endTime = it.toLocalTime()) }
    }

    /** Sets the end; an end before the start moves the start back by the same amount. */
    fun withEnd(date: LocalDate = endDate, time: LocalTime = endTime): Draft {
        val newEnd = LocalDateTime.of(date, time)
        if (allDay) return if (date < startDate) copy(startDate = date, endDate = date) else copy(endDate = date)
        if (!newEnd.isBefore(start)) return copy(endDate = date, endTime = time)
        val minutes = ChronoUnit.MINUTES.between(start, end)
        val s = newEnd.minusMinutes(minutes)
        return copy(startDate = s.toLocalDate(), startTime = s.toLocalTime(), endDate = date, endTime = time)
    }
}

@HiltViewModel
class EventEditorViewModel @Inject constructor(private val repository: EventRepository) : ViewModel() {
    val calendars: StateFlow<List<CalendarEntity>> =
        repository.writableCalendars.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var draft by mutableStateOf<Draft?>(null)
        private set
    private var initial: Draft? = null
    private var original: EventEntity? = null
    private var loadedFor: EditorRequest? = null
    private var occurrence: Occurrence? = null

    /** Saving an occurrence of a series waits for the user's choice of scope. */
    var askScope by mutableStateOf(false)
        private set

    fun cancelScope() {
        askScope = false
    }

    /**
     * Saving an existing event in another calendar waits for the user's confirmation, so that a stray tap on the calendar
     * field cannot move it (to another account, it is created again there). An occurrence of a series keeps its calendar.
     */
    var askMove by mutableStateOf(false)
        private set
    private var moveConfirmed = false
    val originalCalendarId: String? get() = original?.calendarId

    fun cancelMove() {
        askMove = false
    }

    fun confirmMove(onDone: () -> Unit) {
        moveConfirmed = true
        save(onDone)
    }

    /** Saving an event that has guests (or had them) waits for the answer on whether to tell them. */
    var askNotify by mutableStateOf(false)
        private set
    private var pendingScope: SeriesScope? = null

    fun cancelNotify() {
        askNotify = false
    }

    /** The answer to the question about notifying the guests: save with it. */
    fun saveNotifying(onDone: () -> Unit, notify: Boolean) = save(onDone, pendingScope, notify)

    var suggestions by mutableStateOf<List<Attendee>>(emptyList())
        private set

    val dirty: Boolean get() = draft != initial
    val isNew: Boolean get() = original == null

    /** The editor was closed without saving: the next one must start from scratch, not from this draft. */
    fun forget() {
        loadedFor = null
    }

    fun load(request: EditorRequest) {
        if (loadedFor == request) return
        loadedFor = request
        moveConfirmed = false
        askMove = false
        // This view model outlives the editor: what the previous event left (the occurrence of a series being edited, a
        // pending question) must not reach the next one. Without this, a new event created after editing an occurrence of a
        // series asked "Edit recurring event" on Save and was never written (0.4.7, found on API 34).
        occurrence = null
        askScope = false
        askNotify = false
        draft = null
        viewModelScope.launch {
            val d = when (request) {
                is EditorRequest.New -> newDraft(request)
                is EditorRequest.Edit -> editDraft(request.eventId, request.occurrence) ?: return@launch
            }
            initial = d
            draft = d
            suggestions = repository.knownAttendees()
        }
    }

    /** Whether to ask for the contacts permission when the guest field is first used. */
    val needsContacts: Boolean get() = !repository.contacts.granted

    fun reloadSuggestions() {
        viewModelScope.launch { suggestions = repository.knownAttendees() }
    }

    fun update(transform: (Draft) -> Draft) {
        val before = draft?.calendarId
        draft = draft?.let(transform)
        if (draft?.calendarId != before) moveConfirmed = false // a new choice of calendar needs its own confirmation
    }

    private suspend fun newDraft(request: EditorRequest.New): Draft {
        val calendarId = repository.defaultCalendarId() ?: repository.writableCalendars.first().first().id
        // Next full hour, one hour long (as in the desktop app).
        val start = request.start ?: LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(1)
        val end = start.plusHours(1)
        original = null
        return Draft(
            id = UUID.randomUUID().toString(),
            calendarId = calendarId,
            allDay = request.allDay,
            startDate = start.toLocalDate(),
            startTime = start.toLocalTime(),
            endDate = if (request.allDay) start.toLocalDate() else end.toLocalDate(),
            endTime = end.toLocalTime(),
        )
    }

    private suspend fun editDraft(eventId: String, occurrence: Occurrence?): Draft? {
        val detail = repository.observe(eventId).first() ?: return null
        original = detail.event
        // A series shows the tapped occurrence; saving then asks which occurrences the edit is for.
        this.occurrence = occurrence?.takeIf { detail.event.rrule != null }
        val e = this.occurrence?.let { o ->
            if (detail.event.allDay) detail.event.copy(startDay = o.startDay, endDay = o.startDay + ((detail.event.endDay ?: 0) - (detail.event.startDay ?: 0)))
            else detail.event.copy(startUtc = o.beginUtc, endUtc = o.endUtc)
        } ?: detail.event
        val zone = e.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        val (sd, st, ed, et) = if (e.allDay) {
            val s = LocalDate.ofEpochDay(e.startDay ?: 0)
            val last = LocalDate.ofEpochDay((e.endDay ?: (s.toEpochDay() + 1)) - 1)
            Quad(s, LocalTime.of(9, 0), maxOf(last, s), LocalTime.of(10, 0))
        } else {
            val s = Instant.ofEpochMilli(e.startUtc ?: 0).atZone(zone).toLocalDateTime()
            val en = Instant.ofEpochMilli(e.endUtc ?: e.startUtc ?: 0).atZone(zone).toLocalDateTime()
            Quad(s.toLocalDate(), s.toLocalTime(), en.toLocalDate(), en.toLocalTime())
        }
        return Draft(
            id = e.id, calendarId = e.calendarId, title = e.title, allDay = e.allDay,
            startDate = sd, startTime = st, endDate = ed, endTime = et, zoneId = zone.id,
            rrule = e.rrule, location = e.location, description = e.description, reminders = e.reminders,
            color = e.color, availability = e.availability, visibility = e.visibility,
            addMeet = e.addMeet, meetLink = e.meetLink, attendees = e.attendees, sendInvites = e.sendInvites,
            guestsCanModify = e.guestsCanModify, guestsCanInvite = e.guestsCanInvite, guestsCanSeeList = e.guestsCanSeeList,
        )
    }

    /** Writes the draft (offline first) and queues it for Google; [scope] for an occurrence of a series. */
    fun save(onDone: () -> Unit, scope: SeriesScope? = null, notify: Boolean? = null) {
        val d = draft ?: return
        if (occurrence != null && scope == null) {
            askScope = true
            return
        }
        askScope = false
        if (original != null && occurrence == null && original?.calendarId != d.calendarId && !moveConfirmed) {
            askMove = true
            return
        }
        askMove = false
        // An existing event with guests: changes, new or removed guests are told only if the user wants.
        if (notify == null && original != null && (d.attendees.isNotEmpty() || original?.attendees.orEmpty().isNotEmpty())) {
            pendingScope = scope
            askNotify = true
            return
        }
        askNotify = false
        val send = notify ?: d.sendInvites
        val base = original ?: EventEntity(id = d.id, calendarId = d.calendarId)
        val entity = if (d.allDay) {
            base.copy(allDay = true, startDay = d.startDate.toEpochDay(), endDay = d.endDate.toEpochDay() + 1, startUtc = null, endUtc = null, timeZone = null)
        } else {
            base.copy(
                allDay = false, startDay = null, endDay = null, timeZone = d.zoneId,
                startUtc = d.start.atZone(d.zone).toInstant().toEpochMilli(),
                endUtc = d.end.atZone(d.zone).toInstant().toEpochMilli(),
            )
        }.copy(
            title = d.title.trim(), rrule = d.rrule, location = d.location.trim(), description = d.description,
            reminders = d.reminders, hasAlarm = d.reminders.orEmpty().any { it.method == io.github.giansalvo.ggcalendar.core.model.ReminderMethod.ALARM },
            color = d.color, availability = d.availability, visibility = d.visibility,
            addMeet = d.addMeet, meetLink = if (d.addMeet) d.meetLink else null,
            attendees = d.attendees, sendInvites = d.sendInvites,
            guestsCanModify = d.guestsCanModify, guestsCanInvite = d.guestsCanInvite, guestsCanSeeList = d.guestsCanSeeList,
            // Exclusions keep their meaning only while the series keeps its rule.
            exdates = if (d.rrule != null && d.rrule == original?.rrule) base.exdates else emptyList(),
        )
        val occ = occurrence
        if (occ != null && scope != null) {
            viewModelScope.launch {
                repository.series.save(original!!.id, occ, entity.copy(calendarId = original!!.calendarId), scope, send)
                loadedFor = null
                onDone()
            }
            return
        }
        viewModelScope.launch {
            val previous = original
            var saved = entity.copy(calendarId = d.calendarId)
            if (previous != null && previous.calendarId != d.calendarId) {
                repository.writer.move(previous.id, d.calendarId, send)
                // Another account: the event is created anew there, without the old Google ids. Nor the old organizer:
                // it is the other account, and an event whose organizer is not the calendar's own is read-only (0.4.7).
                if (previous.calendarId.substringBefore('|') != d.calendarId.substringBefore('|')) {
                    saved = saved.copy(remoteId = null, etag = null, iCalUid = null, meetLink = null, organizerEmail = null)
                }
            }
            repository.writer.save(saved, send)
            loadedFor = null
            onDone()
        }
    }

    private data class Quad(val a: LocalDate, val b: LocalTime, val c: LocalDate, val d: LocalTime)
}
