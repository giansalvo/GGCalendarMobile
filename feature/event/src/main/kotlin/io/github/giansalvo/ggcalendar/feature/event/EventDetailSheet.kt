package io.github.giansalvo.ggcalendar.feature.event

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.giansalvo.ggcalendar.core.data.EventDetail
import io.github.giansalvo.ggcalendar.core.data.SeriesScope
import io.github.giansalvo.ggcalendar.core.model.Attendee
import io.github.giansalvo.ggcalendar.core.model.EventInstance
import io.github.giansalvo.ggcalendar.core.model.ReminderMethod
import io.github.giansalvo.ggcalendar.core.notifications.ReminderPermissionWarning
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Details of the tapped occurrence (mockup 5), in a bottom sheet. [onEdit] opens the editor; deleting asks first.
 * Read-only calendars and Google's special events (birthdays) show no edit or delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailSheet(
    instance: EventInstance,
    onDismiss: () -> Unit,
    onEdit: (EventDetail, EventInstance) -> Unit,
    viewModel: EventDetailViewModel = hiltViewModel(key = "detail-${instance.eventId}"),
) {
    val detail by remember(instance.eventId) { viewModel.observe(instance.eventId) }.collectAsStateWithLifecycle(null)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        detail?.let { d ->
            DetailContent(
                instance = instance,
                detail = d,
                onClose = onDismiss,
                onEdit = { onEdit(d, instance) },
                onDelete = { scope ->
                    if (scope == null) viewModel.delete(d.event.id) else viewModel.deleteOccurrence(d.event.id, instance.occurrence(), scope)
                    onDismiss()
                },
            )
        } ?: Spacer(Modifier.height(120.dp))
    }
}

@Composable
private fun DetailContent(instance: EventInstance, detail: EventDetail, onClose: () -> Unit, onEdit: () -> Unit, onDelete: (SeriesScope?) -> Unit) {
    val event = detail.event
    val res = LocalContext.current.resources
    val uri = LocalUriHandler.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val color = parseColor(instance.colorHex)

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.close)) }
            Spacer(Modifier.weight(1f))
            if (detail.canWrite) {
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, stringResource(R.string.edit)) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.more_options)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = {
                            menu = false
                            confirmDelete = true
                        })
                    }
                }
            } else {
                Text(stringResource(R.string.read_only), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 16.dp))
            }
        }

        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Box(Modifier.padding(top = 8.dp).size(16.dp).background(color, RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(20.dp))
            Column {
                Text(event.title.ifBlank { stringResource(R.string.untitled) }, style = MaterialTheme.typography.headlineSmall)
                Text(
                    whenText(instance.allDay, instance.begin, instance.end, instance.startDay, instance.endDay),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val zone = event.timeZone
                val name = zone?.let { runCatching { ZoneId.of(it).getDisplayName(TextStyle.FULL, Locale.getDefault()) }.getOrDefault(it) }
                // Only when it differs from the phone's (Europe/Rome and Europe/Berlin are both "Central European Time").
                if (!event.allDay && name != null && name != ZoneId.systemDefault().getDisplayName(TextStyle.FULL, Locale.getDefault())) {
                    Text(stringResource(R.string.time_zone_of, name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                describe(event.rrule ?: detail.series?.rrule, res)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        val meet = event.meetLink
        if (meet != null) {
            Button(onClick = { uri.openUri(meet) }, modifier = Modifier.padding(start = 56.dp, top = 12.dp, end = 20.dp)) {
                Icon(Icons.Outlined.Videocam, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.join_meet))
            }
        } else if (event.addMeet) {
            InfoRow(Icons.Outlined.Videocam) { Text(stringResource(R.string.meet_pending), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        if (event.location.isNotBlank()) {
            InfoRow(Icons.Outlined.LocationOn, onClick = { uri.openUri("geo:0,0?q=" + android.net.Uri.encode(event.location)) }) {
                Text(event.location)
            }
        }

        val reminders = event.reminders
        if (reminders == null) {
            if (detail.calendar.defaultReminders.isNotEmpty()) {
                InfoRow(Icons.Outlined.Notifications) {
                    Column {
                        detail.calendar.defaultReminders.forEach { Text("${methodText(it, res)} · ${beforeText(it.minutes, res)}") }
                        Text(stringResource(R.string.calendar_defaults), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            reminders.sortedBy { it.minutes }.forEach { r ->
                val icon = when (r.method) {
                    ReminderMethod.ALARM -> Icons.Outlined.Alarm
                    ReminderMethod.EMAIL -> Icons.Outlined.Email
                    ReminderMethod.POPUP -> Icons.Outlined.Notifications
                }
                InfoRow(icon) { Text("${methodText(r, res)} · ${beforeText(r.minutes, res)}") }
            }
        }

        val rings = (event.reminders ?: detail.calendar.defaultReminders).any { it.method != ReminderMethod.EMAIL }
        if (rings) ReminderPermissionWarning(Modifier.padding(start = 56.dp, end = 20.dp))

        if (event.attendees.isNotEmpty()) Guests(event.attendees, event.organizerEmail)

        if (event.description.isNotBlank()) {
            InfoRow(Icons.Outlined.Notes) { Text(descriptionText(event.description), style = MaterialTheme.typography.bodyMedium) }
        }

        InfoRow(Icons.Outlined.CalendarToday) {
            Column {
                Text(detail.calendar.name)
                detail.calendar.accountEmail?.takeIf { it != detail.calendar.name }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (confirmDelete && event.rrule != null) {
        SeriesScopeDialog(deleting = true, onDismiss = { confirmDelete = false }) { scope ->
            confirmDelete = false
            onDelete(scope)
        }
    } else if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.delete_event_question)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(null)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Guests(attendees: List<Attendee>, organizer: String?) {
    val yes = attendees.count { it.response == "accepted" }
    val no = attendees.count { it.response == "declined" }
    val maybe = attendees.count { it.response == "tentative" }
    val waiting = attendees.size - yes - no - maybe
    InfoRow(Icons.Outlined.Group) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(pluralStringResource(R.plurals.guests, attendees.size, attendees.size))
            Text(stringResource(R.string.guests_answers, yes, no, maybe, waiting), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            attendees.sortedByDescending { it.email.equals(organizer, ignoreCase = true) }.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (icon, tint) = when (a.response) {
                        "accepted" -> Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.primary
                        "declined" -> Icons.Outlined.Cancel to MaterialTheme.colorScheme.error
                        "tentative" -> Icons.Outlined.HelpOutline to MaterialTheme.colorScheme.onSurfaceVariant
                        else -> Icons.Outlined.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(a.displayName ?: a.email, style = MaterialTheme.typography.bodyMedium)
                        val notes = listOfNotNull(
                            a.email.takeIf { a.displayName != null },
                            stringResource(R.string.organizer).takeIf { a.email.equals(organizer, ignoreCase = true) },
                            stringResource(R.string.optional).takeIf { a.optional },
                        )
                        if (notes.isNotEmpty()) {
                            Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

private val URL = Regex("""https?://[^\s<>"]+""")

/** Google descriptions are often HTML; plain text gets its web addresses made clickable. */
@Composable
private fun descriptionText(text: String): AnnotatedString {
    val links = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    if (Regex("<[a-zA-Z/][^>]*>").containsMatchIn(text)) return AnnotatedString.fromHtml(text, links)
    return buildAnnotatedString {
        var last = 0
        URL.findAll(text).forEach { m ->
            append(text.substring(last, m.range.first))
            withLink(LinkAnnotation.Url(m.value, links)) { append(m.value) }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}
