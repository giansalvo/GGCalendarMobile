package io.github.giansalvo.ggcalendar.feature.event

import android.content.res.Resources
import io.github.giansalvo.ggcalendar.core.data.ExportRow
import io.github.giansalvo.ggcalendar.core.model.CsvWriter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The export as a table, one row per occurrence (a series gives a row for each day it falls in the chosen days).
 * Dates and times go in separate columns, as `yyyy-MM-dd` and `HH:mm`, which spreadsheets recognize.
 */
internal object CsvExport {
    fun csv(rows: List<ExportRow>, res: Resources): String {
        val header = listOf(
            R.string.csv_calendar, R.string.csv_title, R.string.csv_start_date, R.string.csv_start_time, R.string.csv_end_date,
            R.string.csv_end_time, R.string.csv_all_day, R.string.csv_time_zone, R.string.csv_location, R.string.csv_description,
            R.string.csv_meet, R.string.csv_guests, R.string.csv_repeats, R.string.csv_reminders,
        ).map(res::getString)
        return CsvWriter.write(listOf(header) + rows.map { row(it, res) })
    }

    private fun row(r: ExportRow, res: Resources): List<String> {
        val e = r.event
        val i = r.instance
        val zone = e.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        val (startDate, startTime, endDate, endTime) = if (i.allDay) {
            // The end day of an all-day event is exclusive: the table shows the last day.
            listOf(LocalDate.ofEpochDay(i.startDay).toString(), "", LocalDate.ofEpochDay(maxOf(i.startDay, i.endDay - 1)).toString(), "")
        } else {
            val s = Instant.ofEpochMilli(i.beginUtc).atZone(zone)
            val en = Instant.ofEpochMilli(i.endUtc).atZone(zone)
            listOf(s.toLocalDate().toString(), s.toLocalTime().toString(), en.toLocalDate().toString(), en.toLocalTime().toString())
        }
        val reminders = (r.series.reminders ?: r.calendar.defaultReminders).joinToString(" | ") { "${methodText(it, res)}: ${beforeText(it.minutes, res)}" }
        return listOf(
            r.calendar.name.takeIf { !r.calendar.isPrimary } ?: r.calendar.accountEmail ?: r.calendar.name,
            e.title,
            startDate, startTime, endDate, endTime,
            res.getString(if (i.allDay) R.string.csv_yes else R.string.csv_no),
            if (i.allDay) "" else zone.id,
            e.location,
            e.description,
            e.meetLink.orEmpty(),
            e.attendees.joinToString(", ") { it.email },
            r.series.rrule.orEmpty().removePrefix("RRULE:"),
            reminders,
        )
    }
}
