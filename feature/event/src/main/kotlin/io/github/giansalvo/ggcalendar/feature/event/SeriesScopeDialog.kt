package io.github.giansalvo.ggcalendar.feature.event

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import io.github.giansalvo.ggcalendar.core.data.Occurrence
import io.github.giansalvo.ggcalendar.core.data.SeriesScope
import io.github.giansalvo.ggcalendar.core.model.EventInstance

/** "Only this event / This and following events / All events" (mockup 8), for an edit, a deletion or a drag. */
@Composable
fun SeriesScopeDialog(deleting: Boolean, onDismiss: () -> Unit, onConfirm: (SeriesScope) -> Unit) {
    var scope by remember { mutableStateOf(SeriesScope.THIS) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (deleting) R.string.scope_delete_title else R.string.scope_edit_title)) },
        text = {
            Column {
                listOf(
                    SeriesScope.THIS to R.string.scope_this,
                    SeriesScope.FOLLOWING to R.string.scope_following,
                    SeriesScope.ALL to R.string.scope_all,
                ).forEach { (value, label) ->
                    Row(Modifier.fillMaxWidth().clickable { scope = value }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = scope == value, onClick = { scope = value })
                        Text(stringResource(label))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(scope) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The occurrence an instance on screen stands for. */
fun EventInstance.occurrence(): Occurrence = Occurrence(begin.toEpochMilli(), end.toEpochMilli(), startDay.toEpochDay(), allDay)
