package io.github.giansalvo.ggcalendar.feature.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.giansalvo.ggcalendar.core.model.EventInstance
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Search over title, place, description and guests, with the results as an agenda (day headings and cards). It is a
 * full-screen window above the calendar, so the event detail opens over it and the back button returns to the results.
 */
@Composable
fun SearchScreen(search: suspend (String) -> List<EventInstance>, onOpen: (EventInstance) -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var query by rememberSaveable { mutableStateOf("") }
            var results by remember { mutableStateOf<List<EventInstance>?>(null) }
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            // The search runs shortly after the last key, so that typing does not query for every letter.
            LaunchedEffect(query) {
                if (query.isBlank()) {
                    results = null
                } else {
                    delay(250)
                    results = search(query)
                }
            }
            Box(Modifier.systemBarsPadding().imePadding()) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text(stringResource(R.string.search_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.background,
                                unfocusedContainerColor = MaterialTheme.colorScheme.background,
                                focusedIndicatorColor = MaterialTheme.colorScheme.background,
                                unfocusedIndicatorColor = MaterialTheme.colorScheme.background,
                            ),
                            modifier = Modifier.weight(1f).focusRequester(focus),
                        )
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear))
                            }
                        }
                    }
                    val list = results
                    when {
                        list == null -> Hint(stringResource(R.string.search_prompt))
                        list.isEmpty() -> Hint(stringResource(R.string.search_empty))
                        else -> Results(list, onOpen)
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
    )
}

@Composable
private fun Results(list: List<EventInstance>, onOpen: (EventInstance) -> Unit) {
    val today = LocalDate.now()
    val heading = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL) }
    val grouped = remember(list) { list.groupBy { it.startDay } }
    val days = remember(grouped) { grouped.keys.sorted() }
    // Opens on the first result from today onward: what is coming first, the past above it.
    val firstUpcoming = remember(days) { days.indexOfFirst { !it.isBefore(today) }.takeIf { it >= 0 } ?: 0 }
    val state = rememberLazyListState()
    LaunchedEffect(list) {
        var index = 0
        for ((i, day) in days.withIndex()) {
            if (i == firstUpcoming) break
            index += 1 + grouped.getValue(day).size
        }
        state.scrollToItem(index)
    }
    LazyColumn(state = state, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        days.forEach { day ->
            item(key = "day-$day") {
                Text(
                    day.format(heading),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (day == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 12.dp),
                )
            }
            grouped.getValue(day).forEach { instance ->
                item(key = instance.eventId + instance.begin.toEpochMilli()) { EventCard(instance, onClick = { onOpen(instance) }) }
            }
        }
    }
}
