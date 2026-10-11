package io.github.giansalvo.ggcalendar.feature.calendar

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.giansalvo.ggcalendar.core.auth.AuthorizationOutcome
import io.github.giansalvo.ggcalendar.core.auth.GoogleAuthorizer
import io.github.giansalvo.ggcalendar.core.data.AccountWithCalendars
import io.github.giansalvo.ggcalendar.core.data.CalendarRepository
import io.github.giansalvo.ggcalendar.core.database.SyncMessageEntity
import io.github.giansalvo.ggcalendar.core.model.EventInstance
import io.github.giansalvo.ggcalendar.feature.event.EditorRequest
import io.github.giansalvo.ggcalendar.feature.event.occurrence
import io.github.giansalvo.ggcalendar.core.data.EventRepository
import io.github.giansalvo.ggcalendar.core.data.SeriesScope
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class CalendarUiState(
    val view: CalendarView = CalendarView.SCHEDULE,
    val date: LocalDate = LocalDate.now(),
    /** Days loaded by the schedule, [scheduleFrom, scheduleTo): they grow as the user scrolls. */
    val scheduleFrom: LocalDate = LocalDate.now().minusMonths(1),
    val scheduleTo: LocalDate = LocalDate.now().plusMonths(4),
    val accounts: List<AccountWithCalendars> = emptyList(),
    val instances: List<EventInstance> = emptyList(),
    val syncing: Boolean = false,
    /** Changes waiting to reach Google. */
    val pending: Int = 0,
    /** Height of one hour in the time grids, in dp (pinch to zoom). */
    val hourHeight: Float = DEFAULT_HOUR_HEIGHT,
    /** First hour shown when a time grid opens on a period without today. */
    val dayStartHour: Int = 7,
    val dimPast: Boolean = true,
    /** Days with events around the month shown by the mini calendar (empty while it is closed). */
    val eventDays: Set<LocalDate> = emptySet(),
    /** The occurrence whose details are open. */
    val selected: EventInstance? = null,
    /** The editor, when open. */
    val editing: EditorRequest? = null,
    /** A dragged occurrence of a series, waiting for the scope. */
    val pendingMove: PendingMove? = null,
    val busy: Boolean = false,
    /** Set when Google needs the user's consent; the screen launches it and calls [CalendarViewModel.onConsentResult]. */
    val consent: AuthorizationOutcome.NeedsConsent? = null,
    val error: String? = null,
    val message: SyncMessageEntity? = null,
)

data class PendingMove(val instance: EventInstance, val begin: Instant, val end: Instant)

private data class Navigation(
    val view: CalendarView,
    val date: LocalDate,
    val scheduleFrom: LocalDate = date.minusMonths(1),
    val scheduleTo: LocalDate = date.plusMonths(4),
) {
    /** Moves to [day]; the schedule starts again around it when it falls outside the loaded months. */
    fun jumpTo(day: LocalDate): Navigation =
        if (day >= scheduleFrom && day < scheduleTo) copy(date = day) else Navigation(view, day)
}

private data class Transient(
    val selected: EventInstance? = null,
    val editing: EditorRequest? = null,
    val pendingMove: PendingMove? = null,
    val busy: Boolean = false,
    val consent: AuthorizationOutcome.NeedsConsent? = null,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val authorizer: GoogleAuthorizer,
    private val repository: CalendarRepository,
    private val events: EventRepository,
) : ViewModel() {
    private val navigation = MutableStateFlow(Navigation(CalendarView.SCHEDULE, LocalDate.now()))
    private val transient = MutableStateFlow(Transient())
    private val grid = repository.viewPreferences
    private val miniMonth = MutableStateFlow<YearMonth?>(null)

    private val eventDays = miniMonth.flatMapLatest { month ->
        if (month == null) return@flatMapLatest flowOf(emptySet())
        val from = monthGrid(month.minusMonths(1)).first
        val to = monthGrid(month.plusMonths(1)).second
        repository.instances(from, to).map { list -> list.flatMapTo(HashSet()) { it.daysIn(from, to).asIterable() } }
    }

    // The account whose consent screen is showing: the state's consent is cleared as soon as it is launched.
    private var consentEmail: String? = null

    // Re-query only when the loaded days change, not at every scroll of the schedule.
    private val instances = navigation
        .map { nav -> if (nav.view == CalendarView.SCHEDULE) nav.scheduleFrom to nav.scheduleTo else range(nav.view, nav.date) }
        .distinctUntilChanged()
        .flatMapLatest { (from, to) -> repository.instances(from, to) }

    val state: StateFlow<CalendarUiState> = combine(
        navigation,
        repository.accounts,
        instances,
        combine(repository.syncing, transient, repository.messages.map { it.firstOrNull() }, ::Triple),
        combine(grid, eventDays, repository.pendingChanges, ::Triple),
    ) { nav, accounts, list, (syncing, t, message), (g, days, pending) ->
        CalendarUiState(
            view = nav.view, date = nav.date, scheduleFrom = nav.scheduleFrom, scheduleTo = nav.scheduleTo, accounts = accounts, instances = list, syncing = syncing,
            hourHeight = g.hourHeight, dayStartHour = g.dayStartHour, dimPast = g.dimPast, eventDays = days, pending = pending,
            selected = t.selected, editing = t.editing, pendingMove = t.pendingMove, busy = t.busy, consent = t.consent, error = t.error, message = message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState())

    init {
        repository.syncNow()
        // Reopen the last view used.
        viewModelScope.launch {
            val last = repository.viewPreferences.first().lastView
            CalendarView.entries.firstOrNull { it.name == last }?.let { view -> navigation.update { it.copy(view = view) } }
        }
    }

    fun selectView(view: CalendarView) {
        navigation.update { it.copy(view = view) }
        viewModelScope.launch { repository.setLastView(view.name) }
    }

    fun goToToday() = navigation.update { it.jumpTo(LocalDate.now()) }

    fun goTo(date: LocalDate) = navigation.update { it.jumpTo(date) }

    /** The schedule scrolled to [day]: only the title follows, the loaded months stay. */
    fun scheduleScrolled(day: LocalDate) = navigation.update { it.copy(date = day) }

    /** The schedule is near its first or last loaded day: load three more months on that side. */
    fun extendSchedule(past: Boolean) = navigation.update {
        if (past) it.copy(scheduleFrom = it.scheduleFrom.minusMonths(3)) else it.copy(scheduleTo = it.scheduleTo.plusMonths(3))
    }

    /** The month pager settled on [month]: keep the day of the month when possible. */
    fun showMonth(month: YearMonth) = navigation.update {
        if (YearMonth.from(it.date) == month) it else it.jumpTo(month.atDay(minOf(it.date.dayOfMonth, month.lengthOfMonth())))
    }

    /**
     * A time grid settled on the [days] days from [start]: keep the selected date if it is among them, otherwise
     * select today when shown (so that going back to this week and then to 6 days starts from today), else [start].
     */
    fun showDays(start: LocalDate, days: Int) = navigation.update {
        val end = start.plusDays(days.toLong())
        val today = LocalDate.now()
        when {
            it.date >= start && it.date < end -> it
            today >= start && today < end -> it.jumpTo(today)
            else -> it.jumpTo(start)
        }
    }

    /** The mini calendar shows [month] (null: closed): load the days with events around it. */
    fun showMiniMonth(month: YearMonth?) = miniMonth.update { month }

    fun setHourHeight(height: Float) = viewModelScope.launch { repository.setHourHeight(height) }

    fun openDay(date: LocalDate) {
        navigation.update { it.copy(view = CalendarView.DAY).jumpTo(date) }
        viewModelScope.launch { repository.setLastView(CalendarView.DAY.name) }
    }

    fun refresh() = repository.syncNow()

    /** The occurrence of a reminder notification, also from a calendar hidden in the menu. */
    fun openLink(link: EventLink) {
        viewModelScope.launch { repository.instance(link.eventId, link.beginUtc)?.let { openEvent(it) } }
    }

    suspend fun search(query: String): List<EventInstance> = repository.search(query)

    fun openEvent(instance: EventInstance?) = transient.update { it.copy(selected = instance) }

    /** A drag in a time grid ended: save the new times, asking the scope first for a series. */
    fun moveEvent(instance: EventInstance, begin: Instant, end: Instant) {
        viewModelScope.launch {
            val event = events.observe(instance.eventId).first()?.event ?: return@launch
            if (event.rrule != null) {
                transient.update { it.copy(pendingMove = PendingMove(instance, begin, end)) }
            } else {
                events.writer.save(event.copy(startUtc = begin.toEpochMilli(), endUtc = end.toEpochMilli()))
            }
        }
    }

    /** The scope for [CalendarUiState.pendingMove], or null to cancel the move. */
    fun confirmMove(scope: SeriesScope?) {
        val move = transient.value.pendingMove ?: return
        transient.update { it.copy(pendingMove = null) }
        if (scope == null) return
        viewModelScope.launch {
            val event = events.observe(move.instance.eventId).first()?.event ?: return@launch
            events.series.save(event.id, move.instance.occurrence(), event.copy(startUtc = move.begin.toEpochMilli(), endUtc = move.end.toEpochMilli()), scope)
        }
    }

    /** Opens the editor (closing the details) or closes it with null. */
    fun openEditor(request: EditorRequest?) = transient.update { it.copy(editing = request, selected = if (request != null) null else it.selected) }

    fun chooseAccountIntent(): Intent = authorizer.chooseAccountIntent()

    fun onAccountChosen(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) return
        launchAction {
            authorize(authorizer.accountFromPicker(data))
        }
    }

    /** Asks Google again for an account whose access was lost (sync message of kind "auth"). */
    fun reauthorize(email: String) = launchAction { authorize(email) }

    fun consentLaunched() = transient.update { it.copy(consent = null) }

    fun onConsentResult(resultCode: Int, data: Intent?) {
        val email = consentEmail ?: return
        consentEmail = null
        if (resultCode != Activity.RESULT_OK) return
        launchAction {
            authorizer.tokenFromConsent(data)
            repository.addAccount(email)
        }
    }

    fun toggleCalendar(id: String, visible: Boolean) = viewModelScope.launch { repository.setCalendarVisible(id, visible) }

    fun dismissError() = transient.update { it.copy(error = null) }

    fun dismissMessage(id: Long) = viewModelScope.launch { repository.markMessageRead(id) }

    private suspend fun authorize(email: String) {
        when (val outcome = authorizer.authorize(email)) {
            is AuthorizationOutcome.Authorized -> repository.addAccount(outcome.email)
            is AuthorizationOutcome.NeedsConsent -> {
                consentEmail = outcome.email
                transient.update { it.copy(consent = outcome) }
            }
        }
    }

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            transient.update { it.copy(busy = true) }
            try {
                block()
            } catch (e: Exception) {
                transient.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                transient.update { it.copy(busy = false) }
            }
        }
    }

    private companion object {
        /** Days loaded for the month view (three grids) and the time grids; the schedule has its own months. */
        fun range(view: CalendarView, date: LocalDate): Pair<LocalDate, LocalDate> = when (view) {
            CalendarView.MONTH, CalendarView.MONTH_LIST -> YearMonth.from(date).minusMonths(1).atDay(1).minusDays(7) to
                YearMonth.from(date).plusMonths(1).atEndOfMonth().plusDays(14)
            // Time grids: the page with the date and its neighbours, whatever the alignment of the pages.
            else -> date.minusDays(14) to date.plusDays(21)
        }
    }
}
