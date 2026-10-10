package io.github.giansalvo.ggcalendar.feature.calendar

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.giansalvo.ggcalendar.core.auth.AuthorizationOutcome
import io.github.giansalvo.ggcalendar.core.auth.GoogleAuthorizer
import io.github.giansalvo.ggcalendar.core.model.CalendarInfo
import io.github.giansalvo.ggcalendar.core.network.GoogleCalendarApi
import io.github.giansalvo.ggcalendar.core.network.GoogleCalendarEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class AccountSection(val email: String, val calendars: List<CalendarInfo>)

data class CalendarUiState(
    val view: CalendarView = CalendarView.WEEK,
    val date: LocalDate = LocalDate.now(),
    val accounts: List<AccountSection> = emptyList(),
    val busy: Boolean = false,
    /** Set when Google needs the user's consent; the screen launches it and calls [CalendarViewModel.onConsentResult]. */
    val consent: AuthorizationOutcome.NeedsConsent? = null,
    val error: String? = null,
)

/**
 * Milestone M0: view switching and Google sign-in with the calendar list kept in memory.
 * Room, sync and the real views arrive in M1 and M2.
 */
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val authorizer: GoogleAuthorizer,
    private val api: GoogleCalendarApi,
) : ViewModel() {
    private val _state = MutableStateFlow(CalendarUiState())

    // The account whose consent screen is showing: the state's consent is cleared as soon as it is launched.
    private var consentEmail: String? = null
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    fun selectView(view: CalendarView) = _state.update { it.copy(view = view) }

    fun goToToday() = _state.update { it.copy(date = LocalDate.now()) }

    fun chooseAccountIntent(): Intent = authorizer.chooseAccountIntent()

    fun onAccountChosen(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) return
        launchAction {
            when (val outcome = authorizer.authorize(authorizer.accountFromPicker(data))) {
                is AuthorizationOutcome.Authorized -> loadCalendars(outcome.email, outcome.accessToken)
                is AuthorizationOutcome.NeedsConsent -> {
                    consentEmail = outcome.email
                    _state.update { it.copy(consent = outcome) }
                }
            }
        }
    }

    fun consentLaunched() = _state.update { it.copy(consent = null) }

    fun onConsentResult(resultCode: Int, data: Intent?) {
        val email = consentEmail ?: return
        consentEmail = null
        if (resultCode != Activity.RESULT_OK) return
        launchAction { loadCalendars(email, authorizer.tokenFromConsent(data)) }
    }

    fun toggleCalendar(id: String) = _state.update { s ->
        s.copy(accounts = s.accounts.map { a ->
            a.copy(calendars = a.calendars.map { if (it.id == id) it.copy(isVisible = !it.isVisible) else it })
        })
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private suspend fun loadCalendars(email: String, token: String) {
        val calendars = api.listCalendars(token)
            .sortedWith(compareByDescending<GoogleCalendarEntry> { it.isPrimary }.thenBy { it.name })
            .map { CalendarInfo(CalendarInfo.googleId(email, it.id), email, it.name, it.colorHex, canWrite = it.canWrite) }
        _state.update { s -> s.copy(accounts = s.accounts.filter { it.email != email } + AccountSection(email, calendars)) }
    }

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                block()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
