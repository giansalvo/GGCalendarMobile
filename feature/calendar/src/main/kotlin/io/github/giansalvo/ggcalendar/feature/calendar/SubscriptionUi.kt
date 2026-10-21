package io.github.giansalvo.ggcalendar.feature.calendar

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.giansalvo.ggcalendar.core.data.SubscriptionProblem
import io.github.giansalvo.ggcalendar.core.model.CalendarInfo
import io.github.giansalvo.ggcalendar.core.model.SubscriptionInfo
import io.github.giansalvo.ggcalendar.core.notifications.exposeTestTags
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The text that tells a subscription problem (a message of the dialog or the reason on the row of the menu). */
@Composable
internal fun subscriptionProblemText(problem: SubscriptionProblem): String = stringResource(
    when (problem) {
        SubscriptionProblem.BAD_ADDRESS -> R.string.subscription_problem_bad_address
        SubscriptionProblem.NO_NETWORK -> R.string.subscription_problem_no_network
        SubscriptionProblem.TIMEOUT -> R.string.subscription_problem_timeout
        SubscriptionProblem.TLS -> R.string.subscription_problem_tls
        SubscriptionProblem.NOT_FOUND -> R.string.subscription_problem_not_found
        SubscriptionProblem.FORBIDDEN -> R.string.subscription_problem_forbidden
        SubscriptionProblem.SERVER -> R.string.subscription_problem_server
        SubscriptionProblem.OTHER_HTTP -> R.string.subscription_problem_other_http
        SubscriptionProblem.TOO_MANY_REDIRECTS -> R.string.subscription_problem_redirects
        SubscriptionProblem.REDIRECT_NOT_HTTPS -> R.string.subscription_problem_redirect_not_https
        SubscriptionProblem.TOO_LARGE -> R.string.subscription_problem_too_large
        SubscriptionProblem.NOT_CALENDAR -> R.string.subscription_problem_not_calendar
        SubscriptionProblem.EMPTY -> R.string.subscription_problem_empty
        SubscriptionProblem.TOO_MANY_EVENTS -> R.string.subscription_problem_too_many_events
        SubscriptionProblem.UNREADABLE -> R.string.subscription_problem_unreadable
        SubscriptionProblem.ALREADY_SUBSCRIBED -> R.string.subscription_problem_already
        SubscriptionProblem.TOO_MANY_SUBSCRIPTIONS -> R.string.subscription_problem_too_many
        SubscriptionProblem.ADDRESS_LOST -> R.string.subscription_problem_address_lost
    },
)

/** The "Subscriptions" part of the side menu: the calendars read from an address, each with its state and its menu, and the way to add one. */
@Composable
internal fun SubscriptionsSection(
    subscriptions: List<SubscriptionInfo>,
    onToggle: (String, Boolean) -> Unit,
    onAdd: () -> Unit,
    onRefresh: (String) -> Unit,
    onEdit: (String) -> Unit,
    onShowAddress: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    if (subscriptions.isNotEmpty()) {
        Row(Modifier.padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Link, contentDescription = null)
            Text(stringResource(R.string.subscriptions_title), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
    subscriptions.forEach { info ->
        val calendar = info.calendar
        CalendarRow(calendar, onToggle) {
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }, modifier = Modifier.testTag("subscription_menu")) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.subscription_options, calendar.name))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.exposeTestTags()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.subscription_refresh_now)) },
                        onClick = { open = false; onRefresh(calendar.id) },
                        modifier = Modifier.testTag("subscription_menu_refresh"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.subscription_edit)) },
                        onClick = { open = false; onEdit(calendar.id) },
                        modifier = Modifier.testTag("subscription_menu_edit"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.subscription_show_address)) },
                        onClick = { open = false; onShowAddress(calendar.id) },
                        modifier = Modifier.testTag("subscription_menu_address"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.subscription_remove)) },
                        onClick = { open = false; onRemove(calendar.id) },
                        modifier = Modifier.testTag("subscription_menu_remove"),
                    )
                }
            }
        }
        Text(
            subscriptionStatus(info),
            Modifier.testTag("subscription_status").padding(start = 52.dp, end = 16.dp, bottom = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (info.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
    NavigationDrawerItem(
        icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
        label = { Text(stringResource(R.string.subscription_add)) },
        selected = false,
        onClick = onAdd,
        modifier = Modifier.testTag("drawer_new_subscription"),
    )
}

@Composable
private fun subscriptionStatus(info: SubscriptionInfo): String {
    val context = LocalContext.current
    fun time(millis: Long): String {
        val flags = if (DateUtils.isToday(millis)) DateUtils.FORMAT_SHOW_TIME else DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL
        return DateUtils.formatDateTime(context, millis, flags)
    }
    val error = info.error?.let { runCatching { SubscriptionProblem.valueOf(it) }.getOrNull() }
    return when {
        error != null -> {
            val problem = subscriptionProblemText(error)
            val at = info.fetchedAt?.let(::time) ?: return problem
            stringResource(R.string.subscription_not_updated, problem, at)
        }
        info.fetchedAt != null -> stringResource(R.string.subscription_updated_at, time(info.fetchedAt!!))
        else -> stringResource(R.string.subscription_never)
    }
}

/** The dialog that adds a subscription: the address, the reading of it, what was found, and the name and color to give. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubscriptionAddDialog(
    dialog: SubscriptionDialog,
    onCheck: (String) -> Unit,
    onAdd: (name: String, colorHex: String) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        is SubscriptionDialog.Input -> {
            // Not saved with the screen state: the address can be a secret.
            var address by remember(dialog) { mutableStateOf(dialog.address) }
            val clipboard = LocalClipboardManager.current
            AlertDialog(
                modifier = Modifier.exposeTestTags(),
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.subscription_add)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it.take(2048) },
                            label = { Text(stringResource(R.string.subscription_address)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth().testTag("subscription_address"),
                        )
                        TextButton(
                            onClick = { clipboard.getText()?.text?.let { address = it.take(2048) } },
                            modifier = Modifier.testTag("subscription_paste"),
                        ) { Text(stringResource(R.string.subscription_paste)) }
                        dialog.problem?.let {
                            Text(subscriptionProblemText(it), Modifier.testTag("subscription_problem"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(stringResource(R.string.subscription_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    TextButton(modifier = Modifier.testTag("subscription_check"), enabled = address.isNotBlank(), onClick = { onCheck(address) }) {
                        Text(stringResource(R.string.subscription_check))
                    }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
            )
        }
        is SubscriptionDialog.Checking -> AlertDialog(
            modifier = Modifier.exposeTestTags(),
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.subscription_add)) },
            text = {
                Row(Modifier.testTag("subscription_checking"), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.subscription_checking), Modifier.padding(start = 16.dp))
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        )
        is SubscriptionDialog.Preview -> {
            val preview = dialog.preview
            var name by remember(dialog) { mutableStateOf(preview.name) }
            var color by remember(dialog) { mutableStateOf(LocalCalendarColors.first()) }
            val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            AlertDialog(
                modifier = Modifier.exposeTestTags(),
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.subscription_add)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(100) },
                            label = { Text(stringResource(R.string.subscription_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("subscription_name"),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            LocalCalendarColors.forEachIndexed { i, hex ->
                                Box(
                                    Modifier.size(32.dp).background(parseColor(hex), CircleShape)
                                        .then(if (hex.equals(color, ignoreCase = true)) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                        .clickable { color = hex }
                                        .testTag("subscription_color_$i"),
                                )
                            }
                        }
                        Text(
                            pluralStringResource(R.plurals.subscription_events, preview.eventCount, preview.eventCount),
                            Modifier.testTag("subscription_count"),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (preview.firstDay != null && preview.lastDay != null) {
                            Text(stringResource(R.string.subscription_range, dates.format(preview.firstDay), dates.format(preview.lastDay)), style = MaterialTheme.typography.bodyMedium)
                        }
                        if (preview.isSecretAddress) Text(stringResource(R.string.subscription_secret), style = MaterialTheme.typography.bodySmall)
                        Text("• " + stringResource(R.string.subscription_read_only), style = MaterialTheme.typography.bodySmall)
                        Text("• " + stringResource(R.string.subscription_updates), style = MaterialTheme.typography.bodySmall)
                        Text("• " + stringResource(R.string.subscription_no_reminders), style = MaterialTheme.typography.bodySmall)
                        if (preview.ignored.isNotEmpty()) {
                            Text(
                                "• " + stringResource(R.string.subscription_ignored, preview.ignored.entries.joinToString(", ") { "${it.key} (${it.value})" }),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        dialog.problem?.let {
                            Text(subscriptionProblemText(it), Modifier.testTag("subscription_problem"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                },
                confirmButton = {
                    TextButton(modifier = Modifier.testTag("subscription_add_confirm"), enabled = name.isNotBlank(), onClick = { onAdd(name.trim(), color) }) {
                        Text(stringResource(R.string.subscription_add_button))
                    }
                },
                dismissButton = { TextButton(modifier = Modifier.testTag("subscription_back"), onClick = onBack) { Text(stringResource(R.string.subscription_back)) } },
            )
        }
    }
}

@Composable
internal fun RemoveSubscriptionDialog(calendar: CalendarInfo, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.exposeTestTags(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subscription_remove_title, calendar.name)) },
        text = { Text(stringResource(R.string.subscription_remove_message)) },
        confirmButton = {
            TextButton(modifier = Modifier.testTag("subscription_remove_confirm"), onClick = onConfirm) { Text(stringResource(R.string.subscription_remove)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** The address of a subscription: masked, and the whole of it only after a tap. */
@Composable
internal fun SubscriptionAddressDialog(shown: AddressShown, onReveal: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.exposeTestTags(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subscription_address_title, shown.calendarName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SelectionContainer {
                    Text(shown.full ?: shown.masked, Modifier.testTag("subscription_address_text"), style = MaterialTheme.typography.bodyMedium)
                }
                if (shown.full == null) {
                    Text(stringResource(R.string.subscription_address_masked_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(modifier = Modifier.testTag("subscription_address_reveal"), onClick = onReveal) { Text(stringResource(R.string.subscription_address_reveal)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.subscription_close)) } },
    )
}
