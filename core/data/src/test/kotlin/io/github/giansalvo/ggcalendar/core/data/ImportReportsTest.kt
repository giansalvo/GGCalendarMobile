package io.github.giansalvo.ggcalendar.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ImportReportsTest {
    // U210
    @Test
    fun theResultComesOnceWhenTheLastEntryIsResolved() {
        val reports = ImportReports()
        reports.begin("Prova", listOf(1, 2, 3))
        assertNull(reports.sent(1))
        assertNull(reports.rejected(2))
        val done = reports.sent(3)
        assertNotNull(done)
        assertEquals("Prova", done!!.calendarName)
        assertEquals(2, done.sent)
        assertEquals(1, done.rejected)
        assertNull("the report is made once", reports.sent(3))
    }

    @Test
    fun entriesOfNoImportAreNotCounted() {
        val reports = ImportReports()
        assertNull(reports.sent(9))
        reports.begin("A", listOf(1))
        reports.begin("B", listOf(2))
        assertEquals("B", reports.sent(2)!!.calendarName)
        assertEquals("A", reports.rejected(1)!!.calendarName)
    }
}
