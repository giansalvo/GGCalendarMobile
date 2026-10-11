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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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
    val accounts: List<AccountWithCalendars> = emptyList(),
    val instances: List<EventInstance> = emptyList(),
    val syncing: Boolean = false,
    val busy: Boolean = false,
    /** Set when Google needs the user's consent; the screen launches it and calls [CalendarViewModel.onConsentResult]. */
    val consent: AuthorizationOutcome.NeedsConsent? = null,
    val error: String? = null,
    val message: SyncMessageEntity? = null,
)

private data class Navigation(val view: CalendarView, val date: LocalDate)

private data class Transient(
    val busy: Boolean = false,
    val consent: AuthorizationOutcome.NeedsConsent? = null,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val authorizer: GoogleAuthorizer,
    private val repository: CalendarRepository,
) : ViewModel() {
    private val navigation = MutableStateFlow(Navigation(CalendarView.SCHEDULE, LocalDate.now()))
    private val transient = MutableStateFlow(Transient())

    // The account whose consent screen is showing: the state's consent is cleared as soon as it is launched.
    private var consentEmail: String? = null

    private val instances = navigation.flatMapLatest { nav ->
        val (from, to) = range(nav.view, nav.date)
        repository.instances(from, to)
    }

    val state: StateFlow<CalendarUiState> = combine(
        navigation,
        repository.accounts,
        instances,
        combine(repository.syncing, transient, repository.messages.map { it.firstOrNull() }, ::Triple),
    ) { nav, accounts, list, (syncing, t, message) ->
        CalendarUiState(
            view = nav.view, date = nav.date, accounts = accounts, instances = list, syncing = syncing,
            busy = t.busy, consent = t.consent, error = t.error, message = message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState())

    init {
        repository.syncNow()
    }

    fun selectView(view: CalendarView) = navigation.update { it.copy(view = view) }

    fun goToToday() = navigation.update { it.copy(date = LocalDate.now()) }

    fun goTo(date: LocalDate) = navigation.update { it.copy(date = date) }

    /** The month pager settled on [month]: keep the day of the month when possible. */
    fun showMonth(month: YearMonth) = navigation.update {
        if (YearMonth.from(it.date) == month) it else it.copy(date = month.atDay(minOf(it.date.dayOfMonth, month.lengthOfMonth())))
    }

    fun openDay(date: LocalDate) = navigation.update { Navigation(CalendarView.DAY, date) }

    fun refresh() = repository.syncNow()

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
        /** Days loaded for each view: the schedule shows four months from the selected day; the month view three grids. */
        fun range(view: CalendarView, date: LocalDate): Pair<LocalDate, LocalDate> = when (view) {
            CalendarView.SCHEDULE -> date to date.plusMonths(4)
            CalendarView.MONTH -> YearMonth.from(date).minusMonths(1).atDay(1).minusDays(7) to
                YearMonth.from(date).plusMonths(1).atEndOfMonth().plusDays(14)
            else -> date.minusDays(7) to date.plusDays(14)
        }
    }
}
