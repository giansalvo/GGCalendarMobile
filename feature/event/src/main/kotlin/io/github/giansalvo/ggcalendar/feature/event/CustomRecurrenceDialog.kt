package io.github.giansalvo.ggcalendar.feature.event

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.giansalvo.ggcalendar.core.model.Frequency
import io.github.giansalvo.ggcalendar.core.model.RecurrenceRule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private enum class Ending { NEVER, ON_DATE, AFTER }

/**
 * "Custom recurrence" (as in Google Calendar): every N days, weeks, months or years; the weekdays of a weekly rule;
 * a monthly rule on the day of the month or on the n-th weekday; ending never, on a date or after N times.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CustomRecurrenceDialog(start: LocalDate, allDay: Boolean, current: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val res = LocalContext.current.resources
    val locale = Locale.getDefault()
    val initial = RecurrenceRule.parse(current) ?: RecurrenceRule(Frequency.WEEKLY, days = listOf(start.dayOfWeek))
    var frequency by remember { mutableStateOf(initial.frequency) }
    var interval by remember { mutableStateOf(initial.interval.toString()) }
    var days by remember { mutableStateOf(initial.days.ifEmpty { listOf(start.dayOfWeek) }.toSet()) }
    var byWeekday by remember { mutableStateOf(initial.nthWeekday != null) }
    var ending by remember { mutableStateOf(if (initial.count != null) Ending.AFTER else if (initial.until != null) Ending.ON_DATE else Ending.NEVER) }
    var until by remember { mutableStateOf(initial.until ?: start.plusMonths(3)) }
    var count by remember { mutableStateOf((initial.count ?: 10).toString()) }
    var pickingUntil by remember { mutableStateOf(false) }
    var unitMenu by remember { mutableStateOf(false) }

    // The n-th weekday of the start: 5 counts as "last".
    val nth = ((start.dayOfMonth - 1) / 7 + 1).let { if (it >= 5) -1 else it }
    val ordinals = res.getStringArray(R.array.ordinals)
    val n = interval.toIntOrNull()?.coerceIn(1, 999) ?: 1

    fun build(): String = RecurrenceRule(
        frequency = frequency,
        interval = n,
        days = if (frequency == Frequency.WEEKLY) days.toList() else emptyList(),
        monthDay = if (frequency == Frequency.MONTHLY && !byWeekday) start.dayOfMonth else null,
        nthWeekday = if (frequency == Frequency.MONTHLY && byWeekday) nth to start.dayOfWeek else null,
        until = if (ending == Ending.ON_DATE) until else null,
        count = if (ending == Ending.AFTER) count.toIntOrNull()?.coerceIn(1, 999) ?: 1 else null,
    ).toRrule(allDay)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_recurrence)) },
        confirmButton = {
            TextButton(onClick = {
                onPick(build())
                onDismiss()
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.repeat_every))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = interval,
                        onValueChange = { v -> interval = v.filter { it.isDigit() }.take(3) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(72.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    val unit = when (frequency) {
                        Frequency.DAILY -> R.plurals.unit_days
                        Frequency.WEEKLY -> R.plurals.unit_weeks
                        Frequency.MONTHLY -> R.plurals.unit_months
                        Frequency.YEARLY -> R.plurals.unit_years
                    }
                    Text(res.getQuantityString(unit, n), Modifier.clickable { unitMenu = true }.padding(8.dp), color = MaterialTheme.colorScheme.primary)
                    DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                        listOf(
                            Frequency.DAILY to R.plurals.unit_days, Frequency.WEEKLY to R.plurals.unit_weeks,
                            Frequency.MONTHLY to R.plurals.unit_months, Frequency.YEARLY to R.plurals.unit_years,
                        ).forEach { (f, label) ->
                            DropdownMenuItem(text = { Text(res.getQuantityString(label, n)) }, onClick = {
                                frequency = f
                                unitMenu = false
                            })
                        }
                    }
                }
                if (frequency == Frequency.WEEKLY) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val first = firstDayOfWeek()
                        (0 until 7).map { first.plus(it.toLong()) }.forEach { d ->
                            FilterChip(
                                selected = d in days,
                                onClick = { days = if (d in days && days.size > 1) days - d else days + d },
                                label = { Text(d.getDisplayName(TextStyle.SHORT, locale)) },
                            )
                        }
                    }
                }
                if (frequency == Frequency.MONTHLY) {
                    RadioRow(stringResource(R.string.monthly_on_day, start.dayOfMonth), !byWeekday) { byWeekday = false }
                    RadioRow(
                        stringResource(R.string.monthly_on_nth, ordinals[if (nth < 0) 0 else nth], start.dayOfWeek.getDisplayName(TextStyle.FULL, locale)),
                        byWeekday,
                    ) { byWeekday = true }
                }
                Text(stringResource(R.string.ends), style = MaterialTheme.typography.titleSmall)
                RadioRow(stringResource(R.string.ends_never), ending == Ending.NEVER) { ending = Ending.NEVER }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = ending == Ending.ON_DATE, onClick = { ending = Ending.ON_DATE })
                    Text(stringResource(R.string.ends_on))
                    Text(
                        until.format(longDateFormat),
                        Modifier.clickable {
                            ending = Ending.ON_DATE
                            pickingUntil = true
                        }.padding(8.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = ending == Ending.AFTER, onClick = { ending = Ending.AFTER })
                    Text(stringResource(R.string.ends_after))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = count,
                        onValueChange = { v ->
                            count = v.filter { it.isDigit() }.take(3)
                            ending = Ending.AFTER
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(72.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(res.getQuantityString(R.plurals.occurrences, count.toIntOrNull() ?: 0))
                }
            }
        },
    )
    if (pickingUntil) DateDialog(until, { pickingUntil = false }) { until = it }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

internal fun firstDayOfWeek(): DayOfWeek = java.time.temporal.WeekFields.of(Locale.getDefault()).firstDayOfWeek
