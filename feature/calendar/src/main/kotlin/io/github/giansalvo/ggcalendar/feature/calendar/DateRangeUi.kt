package io.github.giansalvo.ggcalendar.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.giansalvo.ggcalendar.core.data.SubscriptionRange
import io.github.giansalvo.ggcalendar.core.model.CalendarInfo
import io.github.giansalvo.ggcalendar.core.notifications.exposeTestTags
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The two days that say which events of a calendar read from a file are shown: from the first day to the last, both included. A field
 * left empty has no limit ("from the first event", "to the last event"). It serves the subscription to a public calendar and, when it
 * comes, the one-time import of a file. [near] is the day the date pickers open on when the field is empty.
 */
@Composable
internal fun DateRangeFields(range: SubscriptionRange, near: LocalDate?, onChange: (SubscriptionRange) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DateField(stringResource(R.string.range_from), stringResource(R.string.range_from_hint), range.from, near, "range_from") { onChange(range.copy(from = it)) }
        DateField(stringResource(R.string.range_to), stringResource(R.string.range_to_hint), range.to, range.from ?: near, "range_to") { onChange(range.copy(to = it)) }
        if (!range.isValid) {
            Text(
                stringResource(R.string.subscription_problem_bad_range), Modifier.testTag("range_problem"),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, hint: String, date: LocalDate?, near: LocalDate?, tag: String, onPick: (LocalDate?) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    // A read-only field does not click by itself: the end of a press opens the picker.
    LaunchedEffect(source) { source.interactions.collect { if (it is PressInteraction.Release) picking = true } }
    val format = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    OutlinedTextField(
        value = date?.format(format).orEmpty(),
        onValueChange = {},
        readOnly = true,
        singleLine = true,
        label = { Text(label) },
        placeholder = { Text(hint) },
        trailingIcon = {
            if (date != null) {
                IconButton(onClick = { onPick(null) }, modifier = Modifier.testTag(tag + "_clear")) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.range_clear))
                }
            }
        },
        interactionSource = source,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: near)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            modifier = Modifier.exposeTestTags(),
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("range_picker_ok"),
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                        picking = false
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(android.R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }
}

/** What the "Edit" of a subscription asks: the name, the color and the days. Changing the days reads the address again. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubscriptionEditDialog(
    initial: CalendarInfo,
    initialRange: SubscriptionRange,
    onConfirm: (name: String, colorHex: String, range: SubscriptionRange) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var color by remember { mutableStateOf(initial.colorHex) }
    var range by remember { mutableStateOf(initialRange) }
    AlertDialog(
        modifier = Modifier.exposeTestTags(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subscription_edit)) },
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
                Text(stringResource(R.string.range_title), style = MaterialTheme.typography.titleSmall)
                DateRangeFields(range, near = null) { range = it }
                if (range != initialRange) Text(stringResource(R.string.range_reads_again), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("subscription_edit_confirm"),
                enabled = name.isNotBlank() && range.isValid,
                onClick = { onConfirm(name.trim(), color, range) },
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
