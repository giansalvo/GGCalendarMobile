package io.github.giansalvo.ggcalendar.feature.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.giansalvo.ggcalendar.core.model.CalendarInfo
import io.github.giansalvo.ggcalendar.core.notifications.exposeTestTags

/** Colors offered to a local calendar (those of Google Calendar's palette). */
internal val LocalCalendarColors = listOf(
    "#039BE5", "#7986CB", "#33B679", "#8E24AA", "#E67C73", "#F6BF26", "#F4511E", "#616161", "#3F51B5", "#0B8043", "#D50000", "#795548",
)

/** What a long press on a calendar of the side menu offers. The primary calendar, the read-only ones and the subscriptions have no such menu. */
internal class CalendarActions(val canDelete: Boolean, val onEdit: () -> Unit, val onDelete: () -> Unit)

/**
 * A calendar of the side menu. With [actions] a long press opens a menu with "Edit" and, when it can be deleted, "Delete" (the same two
 * actions go to TalkBack as custom actions, because a long press cannot be seen).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ActionableCalendarRow(calendar: CalendarInfo, onToggle: (String, Boolean) -> Unit, actions: CalendarActions?) {
    if (actions == null) {
        CalendarRow(calendar, onToggle)
        return
    }
    var open by remember { mutableStateOf(false) }
    val edit = stringResource(R.string.calendar_menu_edit)
    val delete = stringResource(R.string.local_calendar_delete)
    Box {
        CalendarRow(
            calendar, onToggle,
            onLongClick = { open = true },
            modifier = Modifier.semantics {
                customActions = buildList {
                    add(CustomAccessibilityAction(edit) { actions.onEdit(); true })
                    if (actions.canDelete) add(CustomAccessibilityAction(delete) { actions.onDelete(); true })
                }
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.exposeTestTags()) {
            DropdownMenuItem(
                text = { Text(edit) },
                onClick = { open = false; actions.onEdit() },
                modifier = Modifier.testTag("calendar_menu_edit"),
            )
            if (actions.canDelete) {
                DropdownMenuItem(
                    text = { Text(delete) },
                    onClick = { open = false; actions.onDelete() },
                    modifier = Modifier.testTag("calendar_menu_delete"),
                )
            }
        }
    }
}

/** The "On this phone" part of the side menu: the local calendars (each with its long press menu) and the way to make a new one. */
@Composable
internal fun LocalCalendarsSection(
    calendars: List<CalendarInfo>,
    onToggle: (String, Boolean) -> Unit,
    onNew: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Row(Modifier.padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.PhoneAndroid, contentDescription = null)
        Text(stringResource(R.string.local_calendars), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
    }
    calendars.forEach { calendar ->
        ActionableCalendarRow(calendar, onToggle, CalendarActions(canDelete = true, onEdit = { onEdit(calendar.id) }, onDelete = { onDelete(calendar.id) }))
    }
    NavigationDrawerItem(
        icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
        label = { Text(stringResource(R.string.local_calendar_new)) },
        selected = false,
        onClick = onNew,
        modifier = Modifier.testTag("drawer_new_local"),
    )
}

/** Name and color of a local calendar, to make or to change ([initial] null = a new one). */
@Composable
internal fun LocalCalendarDialog(initial: CalendarInfo?, onConfirm: (name: String, colorHex: String) -> Unit, onDismiss: () -> Unit) {
    CalendarDialog(
        title = stringResource(if (initial == null) R.string.local_calendar_new else R.string.local_calendar_edit),
        initialName = initial?.name.orEmpty(),
        initialColor = initial?.colorHex ?: LocalCalendarColors.first(),
        maxName = 100,
        tagPrefix = "local",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * The dialog that asks for the name and the color of a calendar, local or of Google. While [busy] it cannot be left and says [busyText];
 * a [problem] stays in it, under the fields, so what was typed is not lost. With [requireChange] OK needs a difference from the start.
 * [nameHint] replaces the name when it cannot change (a calendar of somebody else).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CalendarDialog(
    title: String,
    initialName: String,
    initialColor: String,
    maxName: Int,
    tagPrefix: String,
    onConfirm: (name: String, colorHex: String) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    nameEditable: Boolean = true,
    nameHint: String? = null,
    requireChange: Boolean = false,
    busy: Boolean = false,
    busyText: String? = null,
    problem: String? = null,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var color by rememberSaveable { mutableStateOf(initialColor) }
    val changed = name.trim() != initialName || !color.equals(initialColor, ignoreCase = true)
    AlertDialog(
        modifier = Modifier.exposeTestTags(),
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(maxName) },
                    label = { Text(stringResource(R.string.local_calendar_name)) },
                    singleLine = true,
                    enabled = nameEditable && !busy,
                    modifier = Modifier.testTag("${tagPrefix}_name"),
                )
                if (!nameEditable && nameHint != null) {
                    Text(nameHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LocalCalendarColors.forEachIndexed { i, hex ->
                        Box(
                            Modifier.size(36.dp).background(parseColor(hex), CircleShape)
                                .then(if (hex.equals(color, ignoreCase = true)) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable(enabled = !busy) { color = hex }
                                .testTag("${tagPrefix}_color_$i"),
                        )
                    }
                }
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("${tagPrefix}_busy")) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        busyText?.let { Text(it, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                if (problem != null) {
                    Text(problem, Modifier.testTag("${tagPrefix}_problem"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("${tagPrefix}_save"),
                enabled = !busy && name.isNotBlank() && (!requireChange || changed),
                onClick = { onConfirm(name.trim(), color) },
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(modifier = Modifier.testTag("${tagPrefix}_cancel"), enabled = !busy, onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
internal fun DeleteLocalCalendarDialog(calendar: CalendarInfo, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    DeleteCalendarDialog(
        title = stringResource(R.string.local_calendar_delete_title, calendar.name),
        message = stringResource(R.string.local_calendar_delete_message),
        tagPrefix = "local",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** The question before a calendar is deleted. While [busy] it cannot be left; a [problem] stays in it. */
@Composable
internal fun DeleteCalendarDialog(
    title: String,
    message: String,
    tagPrefix: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
    busyText: String? = null,
    problem: String? = null,
) {
    AlertDialog(
        modifier = Modifier.exposeTestTags(),
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message)
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("${tagPrefix}_busy")) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        busyText?.let { Text(it, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                if (problem != null) {
                    Text(problem, Modifier.testTag("${tagPrefix}_problem"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.testTag("${tagPrefix}_delete_confirm"), enabled = !busy, onClick = onConfirm) {
                Text(stringResource(R.string.local_calendar_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(modifier = Modifier.testTag("${tagPrefix}_delete_cancel"), enabled = !busy, onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
