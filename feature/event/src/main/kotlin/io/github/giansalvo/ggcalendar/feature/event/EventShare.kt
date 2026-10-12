package io.github.giansalvo.ggcalendar.feature.event

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import androidx.core.content.FileProvider
import io.github.giansalvo.ggcalendar.core.data.EventDetail
import io.github.giansalvo.ggcalendar.core.database.CalendarEntity
import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.model.EventInstance
import io.github.giansalvo.ggcalendar.core.model.EventTime
import io.github.giansalvo.ggcalendar.core.model.IcsEvent
import io.github.giansalvo.ggcalendar.core.model.IcsWriter
import io.github.giansalvo.ggcalendar.core.model.Visibility
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** How an event leaves the app: as text for any app, or as an `.ics` file that calendars can import. */
internal object EventShare {
    /**
     * The `.ics` of the event [instance] shown in [detail]. For a series, [wholeSeries] writes the series (its rule, the
     * excluded occurrences and the modified ones, from [exceptions]); otherwise only the occurrence is written, as a
     * single event that does not repeat. An event that does not repeat is always written as it is.
     */
    fun ics(detail: EventDetail, instance: EventInstance, wholeSeries: Boolean, exceptions: List<EventEntity>, now: Instant = Instant.now()): String {
        val master = master(detail)
        val reminders = detail.event.reminders ?: detail.calendar.defaultReminders
        if (master != null && wholeSeries) return IcsWriter.write(seriesEvents(master, detail.calendar, exceptions), now)
        val time = if (instance.allDay) EventTime.AllDay(instance.startDay, instance.endDay)
        else EventTime.Timed(instance.begin, instance.end, detail.event.timeZone)
        val base = uidOf(master ?: detail.event)
        // A single occurrence is a new, separate event for whoever receives it: it gets its own UID.
        val uid = if (master != null) "$base-${instance.begin.toEpochMilli()}" else base
        val single = icsEvent(detail.event, uid, reminders, time).copy(rrule = null, exdates = emptyList(), recurrenceId = null)
        return IcsWriter.write(listOf(single), now)
    }

    /** The `VEVENT`s of a series: the series itself (with the cancelled occurrences excluded) and each modified occurrence. */
    fun seriesEvents(master: EventEntity, calendar: CalendarEntity, exceptions: List<EventEntity>): List<IcsEvent> {
        val uid = uidOf(master)
        val cancelled = exceptions.filter { it.isCancelled }.mapNotNull(::originalStart)
        val series = icsEvent(master, uid, master.reminders ?: calendar.defaultReminders, timeOf(master))
            .copy(exdates = (master.exdates + cancelled).distinct())
        val modified = exceptions.filter { !it.isCancelled }.mapNotNull { e ->
            originalStart(e)?.let { icsEvent(e, uid, e.reminders ?: master.reminders ?: calendar.defaultReminders, timeOf(e)).copy(recurrenceId = it) }
        }
        return listOf(series) + modified
    }

    /** The series an event belongs to: itself if it repeats, or the series a modified occurrence replaces. */
    fun master(detail: EventDetail): EventEntity? = detail.series ?: detail.event.takeIf { !it.rrule.isNullOrBlank() }

    fun isRecurring(detail: EventDetail): Boolean = master(detail) != null

    private fun uidOf(e: EventEntity) = e.iCalUid ?: "${e.id}@ggcalendar"

    private fun timeOf(e: EventEntity): EventTime =
        if (e.allDay) EventTime.AllDay(LocalDate.ofEpochDay(e.startDay ?: 0), LocalDate.ofEpochDay(e.endDay ?: ((e.startDay ?: 0) + 1)))
        else EventTime.Timed(Instant.ofEpochMilli(e.startUtc ?: 0), Instant.ofEpochMilli(e.endUtc ?: e.startUtc ?: 0), e.timeZone)

    /** The occurrence a modified or cancelled event replaces, in the form the exdates use. */
    private fun originalStart(e: EventEntity): String? =
        e.originalStartDay?.let { LocalDate.ofEpochDay(it).toString() } ?: e.originalStartUtc?.let { Instant.ofEpochMilli(it).toString() }

    private fun icsEvent(e: EventEntity, uid: String, reminders: List<io.github.giansalvo.ggcalendar.core.model.Reminder>, time: EventTime) = IcsEvent(
        uid = uid,
        title = e.title,
        time = time,
        location = e.location,
        description = e.description,
        rrule = e.rrule,
        exdates = e.exdates,
        organizerEmail = e.organizerEmail,
        attendees = e.attendees,
        reminders = reminders,
        meetLink = e.meetLink,
        busy = e.availability != "FREE",
        visibility = runCatching { Visibility.valueOf(e.visibility) }.getOrDefault(Visibility.DEFAULT),
    )

    /** File name for the `.ics`: the title without characters that file systems and messengers dislike. */
    fun fileName(title: String): String {
        val base = title.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "").replace(Regex("\\s+"), "_").take(60).trim('_', '.')
        return (base.ifEmpty { "evento" }) + ".ics"
    }

    /** Plain text for any messaging or notes app: title, when, repetition, place, Meet link, description. */
    fun text(detail: EventDetail, instance: EventInstance, res: Resources): String {
        val e = detail.event
        val parts = mutableListOf<String>()
        parts += e.title.ifBlank { res.getString(R.string.untitled) }
        parts += whenText(instance.allDay, instance.begin, instance.end, instance.startDay, instance.endDay)
        master(detail)?.rrule?.let { rule -> describe(rule, res)?.let { parts += res.getString(R.string.share_repeats, it) } }
        if (e.location.isNotBlank()) parts += e.location.trim()
        e.meetLink?.takeIf { it.isNotBlank() }?.let { parts += "Google Meet: $it" }
        if (e.description.isNotBlank()) parts += "\n" + e.description.trim()
        return parts.joinToString("\n")
    }

    /**
     * Who to write to: the guests except the user (the account of the calendar) and rooms or other resources, which
     * are not people. Each address once, in the order of the guest list.
     */
    fun mailRecipients(detail: EventDetail): List<String> {
        val me = setOfNotNull(detail.calendar.accountEmail, detail.calendar.remoteId).map { it.lowercase() }
        return detail.event.attendees.map { it.email.trim() }
            .filter { it.contains('@') && !it.endsWith("resource.calendar.google.com", ignoreCase = true) && it.lowercase() !in me }
            .distinctBy { it.lowercase() }
    }

    /** Body of the mail: the same as the text share, without the title, which is the subject. */
    fun mailBody(detail: EventDetail, instance: EventInstance, res: Resources): String =
        text(detail, instance, res).substringAfter('\n', "")

    /** Opens the mail app on a new message to the guests. Nothing is sent from here, and no permission is needed. */
    fun writeToGuests(context: Context, recipients: List<String>, subject: String, body: String) {
        val mail = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, recipients.toTypedArray())
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
        runCatching { context.startActivity(mail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { android.widget.Toast.makeText(context, R.string.write_no_mail_app, android.widget.Toast.LENGTH_LONG).show() }
    }

    fun shareText(context: Context, subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Writes the file in the cache (the system sees it only through the FileProvider) and opens the share sheet. */
    fun shareIcs(context: Context, title: String, content: String) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        // Old files of earlier shares are not needed any more.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, fileName(title)).apply { writeText(content, Charsets.UTF_8) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".share", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/calendar")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
