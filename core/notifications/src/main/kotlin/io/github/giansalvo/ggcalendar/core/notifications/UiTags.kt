package io.github.giansalvo.ggcalendar.core.notifications

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * Makes the `testTag`s below this node visible to UI Automator as resource ids, so that the emulator tests
 * (`tools/emutest`) can find controls whatever the language. Each window (screen, sheet, dialog) is a separate
 * tree, so it is applied once at the root of each one.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.exposeTestTags(): Modifier = semantics { testTagsAsResourceId = true }
