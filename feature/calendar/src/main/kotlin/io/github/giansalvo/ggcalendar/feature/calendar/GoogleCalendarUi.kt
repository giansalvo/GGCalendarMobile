package io.github.giansalvo.ggcalendar.feature.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.giansalvo.ggcalendar.core.data.GoogleCalendarProblem
import io.github.giansalvo.ggcalendar.core.data.GoogleCalendars
import io.github.giansalvo.ggcalendar.core.model.CalendarInfo
import io.github.giansalvo.ggcalendar.core.notifications.exposeTestTags

/** What the dialogs of Google calendars are doing: making one ([Kind.NEW]), changing one, or deleting one. Kept by the view model. */
data class GoogleCalendarAction(
    val kind: Kind,
    val email: String,
    val calendarId: String? = null,
    /** The request is on its way: the dialog cannot be left. */
    val busy: Boolean = false,
    /** Why the last try did not go through; shown in the dialog, which stays open. */
    val problem: GoogleCalendarProblem? = null,
) {
    enum class Kind { NEW, EDIT, DELETE }
}

/** The calendars of Google offer "Edit" and, when the person made them, "Delete". Never the primary calendar and the read-only ones. */
internal fun googleCalendarActions(calendar: CalendarInfo, onEdit: () -> Unit, onDelete: () -> Unit): CalendarActions? = when {
    calendar.isPrimary -> null
    calendar.isOwner -> CalendarActions(canDelete = true, onEdit = onEdit, onDelete = onDelete)
    // somebody else's calendar that the person can write in: only the color is theirs
    calendar.canWrite -> CalendarActions(canDelete = false, onEdit = onEdit, onDelete = onDelete)
    else -> null
}

/** The name of an account of the side menu. A long press offers a new calendar of that account, and to take the account off the phone. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AccountHeader(email: String, onNewCalendar: () -> Unit, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val new = stringResource(R.string.gcal_new)
    val remove = stringResource(R.string.account_menu_remove)
    Box {
        Row(
            Modifier.testTag("drawer_account").fillMaxWidth()
                .combinedClickable(onLongClick = { open = true }, onClick = {})
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(new) { onNewCalendar(); true },
                        CustomAccessibilityAction(remove) { onRemove(); true },
                    )
                }
                .padding(16.dp, 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(email)
            Column(Modifier.padding(start = 16.dp)) {
                Text(email, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Google", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.exposeTestTags()) {
            DropdownMenuItem(
                text = { Text(new) },
                onClick = { open = false; onNewCalendar() },
                modifier = Modifier.testTag("account_menu_new"),
            )
            DropdownMenuItem(
                text = { Text(remove, color = MaterialTheme.colorScheme.error) },
                onClick = { open = false; onRemove() },
                modifier = Modifier.testTag("account_menu_remove"),
            )
        }
    }
}

@Composable
internal fun GoogleCalendarProblem.text(): String = stringResource(
    when (this) {
        GoogleCalendarProblem.EMPTY_NAME -> R.string.gcal_problem_empty_name
        GoogleCalendarProblem.NO_ACCOUNT -> R.string.gcal_problem_no_account
        GoogleCalendarProblem.NO_NETWORK -> R.string.gcal_problem_no_network
        GoogleCalendarProblem.MAYBE_DONE -> R.string.gcal_problem_maybe_done
        GoogleCalendarProblem.RATE_LIMITED -> R.string.gcal_problem_rate_limited
        GoogleCalendarProblem.NOT_ALLOWED -> R.string.gcal_problem_not_allowed
        GoogleCalendarProblem.AUTH_EXPIRED -> R.string.gcal_problem_auth_expired
        GoogleCalendarProblem.SERVER -> R.string.gcal_problem_server
        GoogleCalendarProblem.PRIMARY -> R.string.gcal_problem_primary
        GoogleCalendarProblem.NOT_OWNER -> R.string.gcal_problem_not_owner
        GoogleCalendarProblem.OTHER -> R.string.gcal_problem_other
    },
)

/** The dialog that makes, changes or deletes a calendar of Google, as the view model says ([action]). [calendars] are the ones of every account. */
@Composable
internal fun GoogleCalendarDialogs(
    action: GoogleCalendarAction,
    calendars: List<CalendarInfo>,
    onSave: (name: String, colorHex: String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val calendar = calendars.firstOrNull { it.id == action.calendarId }
    val problem = action.problem?.text()
    if (action.kind != GoogleCalendarAction.Kind.NEW && calendar == null) {
        // The calendar is gone (deleted elsewhere and seen by a sync): nothing is left to ask about.
        LaunchedEffect(action) { if (!action.busy) onDismiss() }
        return
    }
    when (action.kind) {
        GoogleCalendarAction.Kind.NEW -> CalendarDialog(
            title = stringResource(R.string.gcal_new),
            subtitle = stringResource(R.string.gcal_account_line, action.email),
            initialName = "",
            initialColor = LocalCalendarColors.first(),
            maxName = GoogleCalendars.MAX_NAME,
            tagPrefix = "gcal",
            busy = action.busy,
            busyText = stringResource(R.string.gcal_creating),
            problem = problem,
            onConfirm = onSave,
            onDismiss = onDismiss,
        )
        GoogleCalendarAction.Kind.EDIT -> CalendarDialog(
            title = stringResource(R.string.local_calendar_edit),
            subtitle = stringResource(R.string.gcal_account_line, action.email),
            initialName = calendar!!.name,
            initialColor = calendar.colorHex,
            maxName = GoogleCalendars.MAX_NAME,
            tagPrefix = "gcal",
            nameEditable = calendar.isOwner,
            nameHint = stringResource(R.string.gcal_name_locked),
            requireChange = true,
            busy = action.busy,
            busyText = stringResource(R.string.gcal_saving),
            problem = problem,
            onConfirm = onSave,
            onDismiss = onDismiss,
        )
        GoogleCalendarAction.Kind.DELETE -> DeleteCalendarDialog(
            title = stringResource(R.string.gcal_delete_title, calendar!!.name),
            message = stringResource(R.string.gcal_delete_message),
            tagPrefix = "gcal",
            busy = action.busy,
            busyText = stringResource(R.string.gcal_deleting),
            problem = problem,
            onConfirm = onDelete,
            onDismiss = onDismiss,
        )
    }
}
