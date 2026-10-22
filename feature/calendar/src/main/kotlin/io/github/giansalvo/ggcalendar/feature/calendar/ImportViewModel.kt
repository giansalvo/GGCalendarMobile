package io.github.giansalvo.ggcalendar.feature.calendar

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.giansalvo.ggcalendar.core.data.CalendarRepository
import io.github.giansalvo.ggcalendar.core.data.DuplicateAction
import io.github.giansalvo.ggcalendar.core.data.ImportOptions
import io.github.giansalvo.ggcalendar.core.data.ImportOutcome
import io.github.giansalvo.ggcalendar.core.data.ImportPlan
import io.github.giansalvo.ggcalendar.core.data.ImportProblem
import io.github.giansalvo.ggcalendar.core.data.ImportService
import io.github.giansalvo.ggcalendar.core.data.ImportSource
import io.github.giansalvo.ggcalendar.core.data.ImportSummary
import io.github.giansalvo.ggcalendar.core.data.ImportTarget
import io.github.giansalvo.ggcalendar.core.data.ReadOutcome
import io.github.giansalvo.ggcalendar.core.data.ReminderChoice
import io.github.giansalvo.ggcalendar.core.data.SubscriptionRange
import io.github.giansalvo.ggcalendar.core.data.SummaryOutcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** A local calendar the events can go to. */
data class ImportLocal(val id: String, val name: String)

enum class ImportStage { CLOSED, SOURCE, READING, SUMMARY, IMPORTING, DONE, ERROR }

/** The window that imports a file or an address into a local calendar: where it is, what the file holds and what the user chose. */
data class ImportState(
    val stage: ImportStage = ImportStage.CLOSED,
    val problem: ImportProblem? = null,
    val summary: ImportSummary? = null,
    val locals: List<ImportLocal> = emptyList(),
    /** The id of a local calendar, or [NEW]. */
    val destination: String = NEW,
    val newName: String = "",
    val newColor: String = LocalCalendarColors[2],
    val range: SubscriptionRange = SubscriptionRange(),
    val duplicates: DuplicateAction = DuplicateAction.SKIP,
    val reminders: ReminderChoice = ReminderChoice.APP_DEFAULT,
    /** The default reminder of the settings (minutes before, or a negative number for none), to say it next to its choice. */
    val appDefaultMinutes: Int = 30,
    val plan: ImportPlan? = null,
    /** (done, total) of the preparation; done == total means the write is on its way and cannot be stopped. */
    val progress: Pair<Int, Int>? = null,
    val confirmLarge: Boolean = false,
    val result: ImportOutcome.Done? = null,
) {
    companion object {
        const val NEW = "new"
    }
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val service: ImportService,
    private val repository: CalendarRepository,
) : ViewModel() {
    private val mutable = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = mutable

    // The text of the file lives here only: it is not saved with the state and is let go when the window closes.
    private var source: ImportSource? = null
    private var planJob: Job? = null
    private var workJob: Job? = null

    @Volatile
    private var cancelled = false

    /** Opens the window on the choice of the source (the menu entry). */
    fun open() {
        reset()
        mutable.value = ImportState(stage = ImportStage.SOURCE)
    }

    fun close() {
        cancelled = true
        reset()
        mutable.value = ImportState()
    }

    private fun reset() {
        planJob?.cancel()
        workJob?.cancel()
        source = null
    }

    /** Reads a file (chosen in the picker, or sent by another app) and goes to the summary; nothing is written. */
    fun readFile(uri: Uri) = start { service.readFile(uri) }

    fun readAddress(address: String) = start { service.readAddress(address.trim()) }

    private fun start(read: suspend () -> ReadOutcome) {
        reset()
        mutable.value = ImportState(stage = ImportStage.READING)
        workJob = viewModelScope.launch {
            when (val r = read()) {
                is ReadOutcome.Problem -> mutable.value = ImportState(stage = ImportStage.ERROR, problem = r.problem)
                is ReadOutcome.Ok -> when (val s = service.summarize(r.source)) {
                    is SummaryOutcome.Problem -> mutable.value = ImportState(stage = ImportStage.ERROR, problem = s.problem)
                    is SummaryOutcome.Ok -> {
                        source = r.source
                        val locals = repository.localCalendars.first().map { ImportLocal(it.id, it.name) }
                        mutable.value = ImportState(
                            stage = ImportStage.SUMMARY,
                            summary = s.summary,
                            locals = locals,
                            destination = ImportState.NEW,
                            newName = s.summary.calendarName ?: s.summary.label.removeSuffix(".ics").removeSuffix(".ICS").ifBlank { "Calendar" },
                            reminders = if (s.summary.fileHasAlarms) ReminderChoice.FILE else ReminderChoice.APP_DEFAULT,
                            appDefaultMinutes = repository.defaultReminderMinutes(),
                        )
                        replan()
                    }
                }
            }
        }
    }

    /** A choice of the summary: the plan (how many events, how many already there) is worked out again. */
    fun change(transform: (ImportState) -> ImportState) {
        mutable.update(transform)
        replan()
    }

    private fun replan() {
        val src = source ?: return
        planJob?.cancel()
        val st = mutable.value
        planJob = viewModelScope.launch {
            val plan = service.plan(src, st.destination.takeIf { it != ImportState.NEW }, st.range)
            mutable.update { if (it.stage == ImportStage.SUMMARY) it.copy(plan = plan) else it }
        }
    }

    /** "Import": asks first when there are too many events; otherwise writes. */
    fun startImport(confirmed: Boolean = false) {
        val st = mutable.value
        val src = source ?: return
        if (st.stage != ImportStage.SUMMARY) return
        if (st.plan?.needsConfirmation == true && !confirmed) {
            mutable.update { it.copy(confirmLarge = true) }
            return
        }
        cancelled = false
        mutable.update { it.copy(stage = ImportStage.IMPORTING, confirmLarge = false, progress = 0 to 0) }
        val target = if (st.destination == ImportState.NEW) ImportTarget.NewCalendar(st.newName.trim().ifBlank { "Calendar" }, st.newColor) else ImportTarget.Existing(st.destination)
        workJob = viewModelScope.launch {
            val outcome = service.import(
                src, target, ImportOptions(st.range, st.duplicates, st.reminders),
                onProgress = { done, total -> mutable.update { it.copy(progress = done to total) } },
                isCancelled = { cancelled },
            )
            when (outcome) {
                is ImportOutcome.Done -> {
                    source = null
                    mutable.update { it.copy(stage = ImportStage.DONE, result = outcome, progress = null) }
                }
                ImportOutcome.Cancelled -> mutable.update { it.copy(stage = ImportStage.SUMMARY, progress = null) }
                is ImportOutcome.Failed -> mutable.value = ImportState(stage = ImportStage.ERROR, problem = outcome.problem)
            }
        }
    }

    fun dismissLargeWarning() = mutable.update { it.copy(confirmLarge = false) }

    /** "Stop" while the import prepares: nothing is written. */
    fun stopImport() {
        cancelled = true
    }

    /** The first day of what was imported, to show it. */
    fun firstDay(): LocalDate? = mutable.value.result?.firstDay
}
