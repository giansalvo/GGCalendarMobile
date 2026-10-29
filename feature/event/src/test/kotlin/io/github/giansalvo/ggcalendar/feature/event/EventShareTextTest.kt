package io.github.giansalvo.ggcalendar.feature.event

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.giansalvo.ggcalendar.core.data.EventDetail
import io.github.giansalvo.ggcalendar.core.database.CalendarEntity
import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.model.EventInstance
import io.github.giansalvo.ggcalendar.core.model.Reminder
import io.github.giansalvo.ggcalendar.core.model.ReminderMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

/**
 * (U329–U334) The text of a share and the body of a mail show the description as readable text: no tags and no entities written,
 * the line breaks kept, the address of a link kept. The `.ics` of the same event is not touched (the HTML stays for who imports it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventShareTextTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources
    private val start = Instant.parse("2026-10-08T08:30:00Z")
    private val calendar = CalendarEntity(
        "me@x.com|me@x.com", "me@x.com", "me@x.com", "Me", "#1A73E8", "owner",
        defaultReminders = listOf(Reminder(ReminderMethod.POPUP, 30)),
    )

    private fun detail(description: String) = EventDetail(
        EventEntity(
            id = "e1", calendarId = calendar.id, iCalUid = "uid1@google.com", title = "Riunione", location = "Sala 1",
            startUtc = start.toEpochMilli(), endUtc = start.plusSeconds(3600).toEpochMilli(), timeZone = "Europe/Rome", description = description,
        ),
        calendar, null,
    )

    private val instance = EventInstance(
        "e1", calendar.id, "Riunione", "Sala 1", "#1A73E8", false, start, start.plusSeconds(3600),
        LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 9), hasMeet = false, hasAlarm = false, isRecurring = false,
    )

    private fun text(description: String) = EventShare.text(detail(description), instance, resources)

    @Test
    fun `the text of a share shows the description of an HTML event as text`() {
        val result = text("Ordine del giorno: <b>budget</b> e <i>scadenze</i>.<br>Sito: <a href=\"https://esempio.it/odg\">odg</a><br>&egrave; tutto")
        assertTrue(result.endsWith("\nOrdine del giorno: budget e scadenze.\nSito: odg (https://esempio.it/odg)\nè tutto"))
        assertFalse(result.contains("<") || result.contains("&egrave;"))
    }

    @Test
    fun `the lines of a plain description stay as they are`() {
        val result = text("Quando: ore 10\nLuogo: Sala 2\n\n  rientro")
        assertTrue(result.endsWith("\n\nQuando: ore 10\nLuogo: Sala 2\n\n  rientro".trimEnd()))
    }

    @Test
    fun `the head of the text is the same with or without HTML`() {
        val plain = text("x").substringBefore("\n\nx")
        assertEquals(plain, text("<b>x</b>").substringBefore("\n\nx"))
        assertTrue(plain.startsWith("Riunione\n"))
        assertTrue(plain.contains("Sala 1"))
    }

    @Test
    fun `a description with only tags adds nothing`() {
        assertEquals(text(""), text("<br><p></p>"))
        assertFalse(text("<br>").endsWith("\n"))
    }

    @Test
    fun `the body of the mail is the text without the title and shows the description as text`() {
        val d = detail("Verbale: <a href=\"https://esempio.it/v\">verbale</a>. Il <b>budget</b> &egrave; pronto")
        val body = EventShare.mailBody(d, instance, resources)
        assertFalse(body.startsWith("Riunione\n"))
        assertTrue(body.contains("Sala 1"))
        assertTrue(body.endsWith("Verbale: verbale (https://esempio.it/v). Il budget è pronto"))
        assertFalse(body.contains("<") || body.contains("&egrave;"))
    }

    @Test
    fun `the ics of the event keeps the HTML as it is`() {
        val html = "Il <b>budget</b> &egrave; pronto"
        val ics = EventShare.ics(detail(html), instance, wholeSeries = false, exceptions = emptyList(), now = Instant.parse("2026-10-12T10:00:00Z"))
        // In an .ics the semicolon is written with a backslash: that is the only difference from the description.
        assertTrue(ics.replace("\r\n ", "").replace("\\;", ";").contains("DESCRIPTION:Il <b>budget</b> &egrave; pronto"))
    }
}
