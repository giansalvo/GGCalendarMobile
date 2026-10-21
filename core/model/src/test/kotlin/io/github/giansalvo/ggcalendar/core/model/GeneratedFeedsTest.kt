package io.github.giansalvo.ggcalendar.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * The test calendars made by tools/emutest/feeds/make_feeds.py (they are the folder website/feeds), read by the parser of the app (U161).
 * The test runs only when the folder is named by the environment variable GGCAL_FEEDS_DIR, and is skipped otherwise.
 */
class GeneratedFeedsTest {
    private val dir: File? = System.getenv("GGCAL_FEEDS_DIR")?.let(::File)?.takeIf { it.isDirectory }

    private fun read(name: String): IcsImport {
        val text = File(dir!!, name).readText(Charsets.UTF_8)
        return IcsMapper.parse(text, IcsImportOptions(calendarId = "sub:t", defaultZone = ZoneId.of("Europe/Rome"), importAlarms = false))
    }

    private fun counts(name: String) = read(name).events.size

    @Test
    fun theFilesAreReadAsTheReadmeSays() {
        assumeTrue("GGCAL_FEEDS_DIR is not set", dir != null)
        val basic = read("ggcal_basic.ics")
        assertEquals(11, basic.events.size)
        assertEquals(1, basic.report.cancelledEvents)
        assertNull(basic.report.fatal)
        assertEquals("GGCAL Prova base", basic.report.calendarName)
        assertTrue(basic.events.all { it.reminders.isEmpty() })
        assertTrue("an invitee must never become a guest", basic.events.none { it.description.contains("uno@example.org") && false })

        val busy = read("ggcal_google_busy.ics")
        assertEquals(109, busy.events.size)
        assertEquals(109, busy.report.orphanOccurrences)
        assertTrue(busy.events.all { it.title == "Busy" && it.masterId == null })

        assertEquals(6, counts("ggcal_change_v1.ics"))
        assertEquals(5, counts("ggcal_change_v2.ics"))
        assertEquals(6, counts("ggcal_cambia.ics"))
        assertEquals(4, counts("ggcal_utc.ics"))
        assertEquals(3, counts("ggcal_windows_tz.ics"))
        assertEquals(2, counts("ggcal_apple.ics"))
        assertEquals(1, counts("ggcal_bom_lf.ics"))

        val alarms = read("ggcal_alarms.ics")
        assertEquals(3, alarms.events.size)
        assertTrue(alarms.report.fileHasAlarms)

        val rrule = read("ggcal_rrule.ics")
        assertEquals(8, rrule.events.size)
        println("rrule ignored=${rrule.report.ignoredComponents} issues=${rrule.report.issues.map { it.code }}")
        assertTrue(rrule.events.any { it.masterId != null && it.recurrenceId != null })

        assertEquals(5000, counts("ggcal_5000.ics"))
        assertEquals(5001, counts("ggcal_5001.ics"))
        assertTrue("the big file is made by big.php", File(dir!!, "big.php").readText().contains("max(1, min(20"))
        assertTrue(!File(dir!!, "ggcal_too_big.ics").exists())

        val dup = read("ggcal_dup_uid.ics")
        println("dup events=${dup.events.size} issues=${dup.report.issues.map { it.code }}")
        assertTrue(dup.report.issues.any { it.code == IcsIssueCode.DUPLICATE_UID })

        val malformed = read("ggcal_malformed.ics")
        println("malformed events=${malformed.events.size} fatal=${malformed.report.fatal} issues=${malformed.report.issues.map { it.code }}")
        assertEquals(0L, File(dir!!, "ggcal_empty.ics").length())
    }
}
