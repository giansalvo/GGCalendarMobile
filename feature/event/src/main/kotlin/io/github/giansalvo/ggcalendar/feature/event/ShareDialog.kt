package io.github.giansalvo.ggcalendar.feature.event

import io.github.giansalvo.ggcalendar.core.notifications.exposeTestTags
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Share an event: as text, or as an `.ics` file. For an event that repeats the file can hold the whole series
 * (the default: the receiver gets the repetition) or only the occurrence that was opened.
 */
@Composable
fun ShareDialog(recurring: Boolean, onDismiss: () -> Unit, onText: () -> Unit, onIcs: (wholeSeries: Boolean) -> Unit) {
    var wholeSeries by remember { mutableStateOf(true) }
    AlertDialog(
        modifier = Modifier.exposeTestTags(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_title)) },
        text = {
            Column {
                TextButton(onClick = onText, Modifier.testTag("share_text").fillMaxWidth()) { Text(stringResource(R.string.share_as_text), style = MaterialTheme.typography.titleMedium) }
                TextButton(onClick = { onIcs(wholeSeries) }, Modifier.testTag("share_ics").fillMaxWidth()) { Text(stringResource(R.string.share_as_ics), style = MaterialTheme.typography.titleMedium) }
                if (recurring) {
                    Spacer(Modifier.height(8.dp))
                    ScopeOption(stringResource(R.string.share_scope_all), selected = wholeSeries) { wholeSeries = true }
                    ScopeOption(stringResource(R.string.share_scope_this), selected = !wholeSeries) { wholeSeries = false }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun ScopeOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton).clickable(onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
