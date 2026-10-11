package io.github.giansalvo.ggcalendar.core.notifications

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class ActiveAlarmsViewModel @Inject constructor(private val scheduler: ReminderScheduler) : ViewModel() {
    suspend fun load(): List<ActiveAlarm> = scheduler.upcomingAlarms()
}

/** "Active alarms": the alarms that will ring in the coming weeks, with when, for which event and how long is left. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveAlarmsSheet(onDismiss: () -> Unit, onOpenEvent: (eventId: String, beginUtc: Long) -> Unit, viewModel: ActiveAlarmsViewModel = hiltViewModel()) {
    var alarms by remember { mutableStateOf<List<ActiveAlarm>?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // The list is read again every minute (an alarm that rang leaves it); the time left follows the clock more often.
    LaunchedEffect(Unit) {
        while (true) {
            alarms = viewModel.load()
            delay(60_000)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.active_alarms_title), style = MaterialTheme.typography.titleLarge)
            val list = alarms
            if (list != null && list.isEmpty()) {
                Text(
                    stringResource(R.string.active_alarms_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            LazyColumn(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                items(list.orEmpty(), key = { it.fireAt.toString() + it.eventId + it.minutesBefore + it.snoozed }) { alarm ->
                    AlarmRow(alarm, now) {
                        onOpenEvent(alarm.eventId, alarm.beginUtc)
                        onDismiss()
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun AlarmRow(alarm: ActiveAlarm, now: Long, onClick: () -> Unit) {
    val context = LocalContext.current
    val whenText = DateUtils.formatDateTime(
        context, alarm.fireAt,
        DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL or DateUtils.FORMAT_SHOW_TIME,
    )
    val details = buildString {
        if (alarm.snoozed) append(stringResource(R.string.snoozed)).append(" · ")
        if (alarm.allDay) append(stringResource(R.string.all_day))
        else append(DateFormat.getTimeFormat(context).format(Date(alarm.beginUtc)))
        if (alarm.minutesBefore > 0 && !alarm.snoozed) append(" · ").append(pluralStringResource(R.plurals.minutes_before, alarm.minutesBefore, alarm.minutesBefore))
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Alarm, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 16.dp))
        Column(Modifier.weight(1f)) {
            Text(whenText, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                alarm.title.ifBlank { stringResource(R.string.alarm_title) },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(remaining(alarm.fireAt - now), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
    }
}

/** "2 g 3 h", "1 h 15 min", "7 min", "< 1 min": how long until the alarm rings. */
@Composable
private fun remaining(millis: Long): String {
    val minutes = (millis / 60_000).coerceAtLeast(0)
    return when {
        millis < 60_000 -> stringResource(R.string.remaining_less_than_minute)
        minutes >= 24 * 60 -> stringResource(R.string.remaining_days_hours, (minutes / (24 * 60)).toInt(), (minutes / 60 % 24).toInt())
        minutes >= 60 -> stringResource(R.string.remaining_hours_minutes, (minutes / 60).toInt(), (minutes % 60).toInt())
        else -> stringResource(R.string.remaining_minutes, minutes.toInt())
    }
}
