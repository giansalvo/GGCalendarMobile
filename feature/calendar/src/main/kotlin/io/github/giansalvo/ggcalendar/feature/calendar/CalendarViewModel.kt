package io.github.giansalvo.ggcalendar.feature.calendar

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.giansalvo.ggcalendar.core.auth.AuthorizationOutcome
import io.github.giansalvo.ggcalendar.core.auth.GoogleAuthorizer
import io.github.giansalvo.ggcalendar.core.data.AccountWithCalendars
import io.github.giansalvo.ggcalendar.core.data.AddResult
import io.github.giansalvo.ggcalendar.core.data.PreviewResult
import io.github.giansalvo.ggcalendar.core.data.SubscriptionPreview
import io.github.giansalvo.ggcalendar.core.data.CalendarRepository
import io.github.giansalvo.ggcalendar.core.model.Diag
import io.github.giansalvo.ggcalendar.core.database.SyncMessageEntity
import io.github.giansalvo.ggcalendar.core.model.EventInstance
import io.github.giansalvo.ggcalendar.feature.event.WeekSettings
import io.github.giansalvo.ggcalendar.feature.event.EditorRequest
import io.github.giansalvo.ggcalendar.feature.event.occurrence
import io.github.giansalvo.ggcalendar.core.data.EventRepository
import io.github.giansalvo.ggcalendar.core.data.LocalCalendars
import io.github.giansalvo.ggcalendar.core.data.SeriesScope
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
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
    /** The calendar made from the birthdays of the phone's contacts, when turned on. */
    val birthdays: io.github.giansalvo.ggcalendar.core.model.CalendarInfo? = null,
    /** The calendars kept on this phone only. */
    val localCalendars: List<io.github.giansalvo.ggcalendar.core.model.CalendarInfo> = emptyList(),
    /** The calendars read from an address, with how their last download went. */
    val subscriptions: List<io.github.giansalvo.ggcalendar.core.model.SubscriptionInfo> = emptyList(),
    val instances: List<EventInstance> = emptyList(),
    val syncing: Boolean = false,
    /** Changes waiting to reach Google. */
    val pending: Int = 0,
    /** Height of one hour in the time grids, in dp (pinch to zoom). */
    val hourHeight: Float = DEFAULT_HOUR_HEIGHT,
    /** First hour shown when a time grid opens on a period without today. */
    val dayStartHour: Int = 7,
    val dimPast: Boolean = true,
    /** The second column of hours of the time grids, when turned on. */
    val secondZone: io.github.giansalvo.ggcalendar.core.data.SecondZone? = null,
    val firstDay: java.time.DayOfWeek = WeekSettings.firstDay,
    /** Days with events around the month shown by the mini calendar (empty while it is closed). */
    val eventDays: Set<LocalDate> = emptySet(),
    /** The occurrence whose details are open. */
    val selected: EventInstance? = null,
    /** The editor, when open. */
    val editing: EditorRequest? = null,
    /** A dragged occurrence of a series, waiting for the scope. */
    val pendingMove: PendingMove? = null,
    val busy: Boolean = false,
    /** The account whose access is being asked of Google: the screen says so until it is in. */
    val addingAccount: String? = null,
    /** An account for which Google did not answer in time: the screen offers to try again. */
    val accountTimeout: String? = null,
    /** Set when Google needs the user's consent; the screen launches it and calls [CalendarViewModel.onConsentResult]. */
    val consent: AuthorizationOutcome.NeedsConsent? = null,
    val error: String? = null,
    val noCalendar: Boolean = false,
    val message: SyncMessageEntity? = null,
)

/**
 * A drag that waits for answers: the scope first (a series), then whether to tell the guests (an event with guests).
 */
data class PendingMove(
    val instance: EventInstance,
    val begin: Instant,
    val end: Instant,
    val recurring: Boolean,
    val guests: Boolean,
    val scope: SeriesScope? = null,
) {
    val needsScope: Boolean get() = recurring && scope == null
}

/** The calendars of the side menu, taken together. */
private data class Calendars(
    val accounts: List<AccountWithCalendars>,
    val birthdays: io.github.giansalvo.ggcalendar.core.model.CalendarInfo?,
    val locals: List<io.github.giansalvo.ggcalendar.core.model.CalendarInfo>,
    val subscriptions: List<io.github.giansalvo.ggcalendar.core.model.SubscriptionInfo>,
)

/**
 * The dialog that adds a subscription. The address can be a secret: these classes never print it ([toString]) and the dialog does not
 * save it with the screen state.
 */
sealed interface SubscriptionDialog {
    class Input(val address: String = "", val problem: io.github.giansalvo.ggcalendar.core.data.SubscriptionProblem? = null) : SubscriptionDialog {
        override fun toString() = "Input"
    }

    class Checking(val address: String) : SubscriptionDialog {
        override fun toString() = "Checking"
    }

    class Preview(
        val address: String,
        val preview: SubscriptionPreview,
        val problem: io.github.giansalvo.ggcalendar.core.data.SubscriptionProblem? = null,
        /** The days chosen (empty sides = from the first event, to the last one) and how many events they keep. */
        val range: io.github.giansalvo.ggcalendar.core.data.SubscriptionRange = io.github.giansalvo.ggcalendar.core.data.SubscriptionRange(),
        val kept: Int = preview.eventCount,
    ) : SubscriptionDialog {
        override fun toString() = "Preview"
    }
}

/** The address of a subscription as the "show address" dialog has it: masked, and whole once the user asked for it. */
class AddressShown(val calendarName: String, val masked: String, val full: String?) {
    fun copy(full: String?) = AddressShown(calendarName, masked, full)
    override fun toString() = "AddressShown"
}

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
    val addingAccount: String? = null,
    val accountTimeout: String? = null,
    val consent: AuthorizationOutcome.NeedsConsent? = null,
    val error: String? = null,
    val noCalendar: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val authorizer: GoogleAuthorizer,
    private val repository: CalendarRepository,
    private val events: EventRepository,
    private val localCalendars: LocalCalendars,
    private val diagnosticLog: io.github.giansalvo.ggcalendar.core.notifications.DiagnosticLog,
) : ViewModel() {
    /** Whether the diagnostic log is on: the menu entry that sends it is shown only then. */
    val logEnabled: StateFlow<Boolean> get() = diagnosticLog.enabled

    fun logMail(context: android.content.Context) = diagnosticLog.mailIntent(context)

    fun saveLog(target: android.net.Uri) = diagnosticLog.saveTo(target)

    fun logFileName() = diagnosticLog.suggestedFileName()

    private val navigation = MutableStateFlow(Navigation(CalendarView.SCHEDULE, LocalDate.now()))
    private val transient = MutableStateFlow(Transient())
    private val grid = repository.viewPreferences

    /** The waiting time of the public calendars, as the dialog that adds one tells it. */
    val subscriptionMinutes: StateFlow<Int> = repository.subscriptionMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), io.github.giansalvo.ggcalendar.core.data.AppPreferences.DEFAULT_SUBSCRIPTION_MINUTES)
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
        combine(repository.accounts, repository.birthdayCalendar, repository.localCalendars, repository.subscriptions, ::Calendars),
        instances,
        combine(repository.syncing, transient, repository.messages.map { it.firstOrNull() }, ::Triple),
        combine(grid, eventDays, repository.pendingChanges, ::Triple),
    ) { nav, calendars, list, (syncing, t, message), (g, days, pending) ->
        val (accounts, birthdays, locals, subscriptions) = calendars
        // Set before the state goes out: the views read the first day of the week while they compose.
        WeekSettings.firstDay = g.weekStart ?: WeekSettings.localeDefault()
        CalendarUiState(
            view = nav.view, date = nav.date, scheduleFrom = nav.scheduleFrom, scheduleTo = nav.scheduleTo, accounts = accounts, birthdays = birthdays, localCalendars = locals, subscriptions = subscriptions, instances = list, syncing = syncing,
            hourHeight = g.hourHeight, dayStartHour = g.dayStartHour, dimPast = g.dimPast, secondZone = io.github.giansalvo.ggcalendar.core.data.SecondZones.from(g), firstDay = WeekSettings.firstDay, eventDays = days, pending = pending,
            selected = t.selected, editing = t.editing, pendingMove = t.pendingMove, busy = t.busy, addingAccount = t.addingAccount, accountTimeout = t.accountTimeout, consent = t.consent, error = t.error, noCalendar = t.noCalendar, message = message,
        )
    }.onStart { Diag.log { "vm state upstream START" } }
        .onEach { s -> Diag.log { "vm state out accounts=${s.accounts.size} [${s.accounts.joinToString { a -> Diag.who(a.email) + ":" + a.calendars.size }}] syncing=${s.syncing} adding=${Diag.who(s.addingAccount)} instances=${s.instances.size}" } }
        .onCompletion { Diag.log { "vm state upstream STOP cause=${it?.javaClass?.simpleName}" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState())

    init {
        repository.syncNow()
        viewModelScope.launch { runCatching { repository.syncBirthdays() } }
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

    fun refresh() {
        repository.syncNow()
        viewModelScope.launch { runCatching { repository.refreshSubscriptions() } }
    }

    /** The occurrence of a reminder notification, also from a calendar hidden in the menu. */
    fun openLink(link: EventLink) {
        viewModelScope.launch { repository.instance(link.eventId, link.beginUtc)?.let { openEvent(it) } }
    }

    suspend fun search(query: String): List<EventInstance> = repository.search(query)

    fun openEvent(instance: EventInstance?) = transient.update { it.copy(selected = instance) }

    /** A drag in a time grid ended: save the new times, asking the scope for a series and about notifying the guests. */
    fun moveEvent(instance: EventInstance, begin: Instant, end: Instant) {
        viewModelScope.launch {
            val event = events.observe(instance.eventId).first()?.event ?: return@launch
            val move = PendingMove(instance, begin, end, recurring = event.rrule != null, guests = event.attendees.isNotEmpty())
            if (move.recurring || move.guests) transient.update { it.copy(pendingMove = move) } else commitMove(move, null, true)
        }
    }

    /** The scope for [CalendarUiState.pendingMove], or null to cancel the move. */
    fun confirmMove(scope: SeriesScope?) {
        val move = transient.value.pendingMove ?: return
        when {
            scope == null -> transient.update { it.copy(pendingMove = null) }
            move.guests -> transient.update { it.copy(pendingMove = move.copy(scope = scope)) }
            else -> {
                transient.update { it.copy(pendingMove = null) }
                viewModelScope.launch { commitMove(move, scope, true) }
            }
        }
    }

    /** Whether to tell the guests about [CalendarUiState.pendingMove] (null: cancel the move). */
    fun confirmNotify(notify: Boolean?) {
        val move = transient.value.pendingMove ?: return
        transient.update { it.copy(pendingMove = null) }
        if (notify == null) return
        viewModelScope.launch { commitMove(move, move.scope, notify) }
    }

    private suspend fun commitMove(move: PendingMove, scope: SeriesScope?, notify: Boolean) {
        val event = events.observe(move.instance.eventId).first()?.event ?: return
        val moved = event.copy(startUtc = move.begin.toEpochMilli(), endUtc = move.end.toEpochMilli())
        if (move.recurring && scope != null) events.series.save(event.id, move.instance.occurrence(), moved, scope, notify)
        else events.writer.save(moved, notify)
    }

    /** Opens the editor (closing the details) or closes it with null. */
    fun openEditor(request: EditorRequest?) {
        if (request is EditorRequest.New) {
            // With no writable calendar (no account yet) there is nowhere to put the event.
            viewModelScope.launch {
                if (events.defaultCalendarId() == null) transient.update { it.copy(noCalendar = true) }
                else transient.update { it.copy(editing = request, selected = null) }
            }
        } else {
            transient.update { it.copy(editing = request, selected = if (request != null) null else it.selected) }
        }
    }

    fun dismissNoCalendar() = transient.update { it.copy(noCalendar = false) }

    fun chooseAccountIntent(): Intent = authorizer.chooseAccountIntent()

    fun onAccountChosen(resultCode: Int, data: Intent?) {
        Diag.log { "vm onAccountChosen result=$resultCode hasData=${data != null}" }
        if (resultCode != Activity.RESULT_OK) return
        launchAction {
            authorize(authorizer.accountFromPicker(data))
        }
    }

    /** Asks Google again for an account whose access was lost (sync message of kind "auth"). */
    fun reauthorize(email: String) = launchAction { authorize(email) }

    fun consentLaunched() = transient.update { it.copy(consent = null) }

    fun onConsentResult(resultCode: Int, data: Intent?) {
        Diag.log { "vm onConsentResult result=$resultCode hasData=${data != null} pendingEmail=${Diag.who(consentEmail)}" }
        val email = consentEmail ?: return
        consentEmail = null
        if (resultCode != Activity.RESULT_OK) return
        launchAction {
            transient.update { it.copy(addingAccount = email) }
            try {
                authorizer.tokenFromConsent(data)
                repository.addAccount(email)
            } finally {
                transient.update { it.copy(addingAccount = null) }
            }
        }
    }

    /**
     * Logs the account out of the app only (it stays in Android and Google keeps the app's access: signing in again needs no
     * consent), or also takes the app's access away from the Google account, which holds on every device that uses it.
     */
    fun removeAccount(email: String, revokeEverywhere: Boolean = false) = launchAction {
        Diag.log { "vm removeAccount ${Diag.who(email)} revoke=$revokeEverywhere" }
        repository.removeAccount(email)
        if (revokeEverywhere) authorizer.revoke(email)
    }

    fun createLocalCalendar(name: String, colorHex: String) = viewModelScope.launch {
        // The new calendar keeps the default reminder of this moment; the ones already there are never touched.
        localCalendars.create(name, colorHex, repository.defaultReminderMinutes())
    }

    fun updateLocalCalendar(id: String, name: String, colorHex: String) = viewModelScope.launch { localCalendars.update(id, name, colorHex) }

    fun deleteLocalCalendar(id: String) = viewModelScope.launch { localCalendars.delete(id) }

    // ---- subscriptions (calendars read from an address)

    private val _subscriptionDialog = MutableStateFlow<SubscriptionDialog?>(null)

    /** The dialog that adds a subscription, or null when closed. It lives in the view model: a rotation keeps it, a killed process does not. */
    val subscriptionDialog: StateFlow<SubscriptionDialog?> = _subscriptionDialog.asStateFlow()

    private val _addressShown = MutableStateFlow<AddressShown?>(null)
    val addressShown: StateFlow<AddressShown?> = _addressShown.asStateFlow()
    private var subscriptionJob: Job? = null
    private var shownCalendar: String? = null

    fun openSubscriptionDialog() { _subscriptionDialog.value = SubscriptionDialog.Input() }

    fun checkSubscription(address: String) {
        subscriptionJob?.cancel()
        _subscriptionDialog.value = SubscriptionDialog.Checking(address)
        subscriptionJob = viewModelScope.launch {
            _subscriptionDialog.value = when (val result = repository.previewSubscription(address)) {
                is PreviewResult.Ok -> SubscriptionDialog.Preview(address, result.preview)
                is PreviewResult.Problem -> SubscriptionDialog.Input(address, result.problem)
            }
        }
    }

    /** From the preview (or the wait) back to the address, which is kept. */
    fun backToSubscriptionAddress() {
        subscriptionJob?.cancel()
        val address = when (val d = _subscriptionDialog.value) {
            is SubscriptionDialog.Preview -> d.address
            is SubscriptionDialog.Checking -> d.address
            is SubscriptionDialog.Input -> d.address
            null -> ""
        }
        _subscriptionDialog.value = SubscriptionDialog.Input(address)
    }

    fun addSubscription(name: String, colorHex: String) {
        val dialog = _subscriptionDialog.value as? SubscriptionDialog.Preview ?: return
        subscriptionJob = viewModelScope.launch {
            when (val result = repository.addSubscription(dialog.preview, name, colorHex, dialog.range)) {
                is AddResult.Added -> _subscriptionDialog.value = null
                is AddResult.Problem -> _subscriptionDialog.value = SubscriptionDialog.Preview(dialog.address, dialog.preview, result.problem, dialog.range, dialog.kept)
            }
        }
    }

    fun dismissSubscriptionDialog() {
        subscriptionJob?.cancel()
        _subscriptionDialog.value = null
    }

    fun refreshSubscription(id: String) = viewModelScope.launch { repository.refreshSubscription(id) }

    fun updateSubscription(id: String, name: String, colorHex: String) = viewModelScope.launch { repository.updateSubscription(id, name, colorHex) }

    /** The name, the color and the days of a subscription; new days read the address again (the row says why if that fails). */
    fun updateSubscription(id: String, name: String, colorHex: String, range: io.github.giansalvo.ggcalendar.core.data.SubscriptionRange) = viewModelScope.launch {
        repository.updateSubscription(id, name, colorHex)
        repository.setSubscriptionRange(id, range)
    }

    suspend fun subscriptionRange(id: String) = repository.subscriptionRange(id)

    private var countJob: Job? = null

    /** The days chosen in the dialog of a new subscription: the count of events they keep is worked out again. */
    fun setSubscriptionRange(range: io.github.giansalvo.ggcalendar.core.data.SubscriptionRange) {
        val dialog = _subscriptionDialog.value as? SubscriptionDialog.Preview ?: return
        _subscriptionDialog.value = SubscriptionDialog.Preview(dialog.address, dialog.preview, null, range, dialog.kept)
        countJob?.cancel()
        if (!range.isValid) return
        countJob = viewModelScope.launch {
            val kept = repository.countSubscriptionEvents(dialog.preview, range)
            val now = _subscriptionDialog.value as? SubscriptionDialog.Preview
            if (now != null && now.preview === dialog.preview && now.range == range) {
                _subscriptionDialog.value = SubscriptionDialog.Preview(now.address, now.preview, now.problem, range, kept)
            }
        }
    }

    fun deleteSubscription(id: String) = viewModelScope.launch { repository.deleteSubscription(id) }

    fun showSubscriptionAddress(id: String, calendarName: String) = viewModelScope.launch {
        shownCalendar = id
        _addressShown.value = AddressShown(calendarName, repository.subscriptionAddress(id) ?: "•••", null)
    }

    fun revealSubscriptionAddress() = viewModelScope.launch {
        val id = shownCalendar ?: return@launch
        val current = _addressShown.value ?: return@launch
        _addressShown.value = current.copy(full = repository.revealSubscriptionAddress(id))
    }

    fun hideSubscriptionAddress() {
        shownCalendar = null
        _addressShown.value = null
    }

    fun toggleCalendar(id: String, visible: Boolean) = viewModelScope.launch { repository.setCalendarVisible(id, visible) }

    fun dismissError() = transient.update { it.copy(error = null) }

    fun dismissMessage(id: Long) = viewModelScope.launch { repository.markMessageRead(id) }

    private suspend fun authorize(email: String) {
        // Google can be slow to answer for an account that has just been created: the screen says that the account is being
        // added, and after a while it gives up and offers to try again, instead of staying silent.
        transient.update { it.copy(addingAccount = email, accountTimeout = null) }
        try {
            val outcome = try {
                withTimeout(AUTHORIZATION_TIMEOUT_MILLIS) { authorizer.authorize(email) }
            } catch (e: TimeoutCancellationException) {
                transient.update { it.copy(accountTimeout = email) }
                return
            }
            when (outcome) {
                is AuthorizationOutcome.Authorized -> {
                    Diag.log { "vm authorize outcome=Authorized ${Diag.who(outcome.email)}" }
                    repository.addAccount(outcome.email)
                }
                is AuthorizationOutcome.NeedsConsent -> {
                    Diag.log { "vm authorize outcome=NeedsConsent ${Diag.who(outcome.email)}" }
                    consentEmail = outcome.email
                    transient.update { it.copy(consent = outcome) }
                }
            }
        } finally {
            transient.update { it.copy(addingAccount = null) }
        }
    }

    fun dismissAccountTimeout() = transient.update { it.copy(accountTimeout = null) }

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
        /** How long Google may take to answer an authorization before the screen offers to try again. */
        const val AUTHORIZATION_TIMEOUT_MILLIS = 30_000L

        /** Days loaded for the month view (three grids) and the time grids; the schedule has its own months. */
        fun range(view: CalendarView, date: LocalDate): Pair<LocalDate, LocalDate> = when (view) {
            CalendarView.MONTH, CalendarView.MONTH_LIST -> YearMonth.from(date).minusMonths(1).atDay(1).minusDays(7) to
                YearMonth.from(date).plusMonths(1).atEndOfMonth().plusDays(14)
            // Time grids: the page with the date and its neighbours, whatever the alignment of the pages.
            else -> date.minusDays(14) to date.plusDays(21)
        }
    }
}
