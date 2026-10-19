package io.github.giansalvo.ggcalendar.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsReaderTest {
    private fun wrap(vararg lines: String, eol: String = "\r\n") =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0") + lines + "END:VCALENDAR").joinToString(eol) + eol

    private fun events(doc: IcsDocument) = doc.components.filter { it.name == "VEVENT" }

    // U101
    @Test
    fun foldedLinesAreJoinedWithSpaceOrTabAndLfOnlyFilesWork() {
        val crlf = IcsReader.read(wrap("BEGIN:VEVENT", "SUMMARY:Riunione di", "  lavoro", "DESCRIPTION:uno", "\tdue", "END:VEVENT"))
        val e = events(crlf).single()
        assertEquals("Riunione di lavoro", e.property("SUMMARY")!!.value)
        assertEquals("unodue", e.property("DESCRIPTION")!!.value)
        val lf = IcsReader.read(wrap("BEGIN:VEVENT", "SUMMARY:solo", " LF", "END:VEVENT", eol = "\n"))
        assertEquals("soloLF", events(lf).single().property("SUMMARY")!!.value)
    }

    // U102 (the reader keeps the raw value; the escapes are undone by the mapper)
    @Test
    fun theValueKeepsItsEscapesAndColons() {
        val e = events(IcsReader.read(wrap("BEGIN:VEVENT", "SUMMARY:a\\, b\\; c\\\\ d\\nx: y", "END:VEVENT"))).single()
        assertEquals("a\\, b\\; c\\\\ d\\nx: y", e.property("SUMMARY")!!.value)
    }

    // U103
    @Test
    fun utf8CharactersOutsideTheBasicPlaneAndABomAreKept() {
        val doc = IcsReader.read("﻿" + wrap("BEGIN:VEVENT", "SUMMARY:Festa 🎉 è già", "END:VEVENT"))
        assertNull(doc.fatal)
        assertEquals("Festa 🎉 è già", events(doc).single().property("SUMMARY")!!.value)
    }

    // U104
    @Test
    fun quotedParametersMayContainColonsAndSemicolons() {
        val p = IcsReader.parseLine("ATTENDEE;CN=\"A:B;C\";ROLE=REQ-PARTICIPANT:mailto:a@b.it")!!
        assertEquals("ATTENDEE", p.name)
        assertEquals("A:B;C", p.params["CN"])
        assertEquals("REQ-PARTICIPANT", p.params["ROLE"])
        assertEquals("mailto:a@b.it", p.value)
        val lower = IcsReader.parseLine("dtstart;tzid=Europe/Rome:20261008T100000")!!
        assertEquals("DTSTART", lower.name)
        assertEquals("Europe/Rome", lower.params["TZID"])
    }

    // U105
    @Test
    fun nestedAndUnknownComponentsAreKept() {
        val doc = IcsReader.read(
            wrap(
                "BEGIN:VEVENT", "SUMMARY:x", "X-CUSTOM;FOO=1:bar",
                "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT10M", "END:VALARM",
                "BEGIN:X-NEW", "A:b", "END:X-NEW", "END:VEVENT",
                "BEGIN:X-OTHER", "END:X-OTHER",
            ),
        )
        val e = events(doc).single()
        assertEquals("bar", e.property("X-CUSTOM")!!.value)
        assertEquals("-PT10M", e.children("VALARM").single().property("TRIGGER")!!.value)
        assertEquals(1, e.children("X-NEW").size)
        assertEquals(listOf("VEVENT", "X-OTHER"), doc.components.map { it.name })
    }

    // U106
    @Test
    fun aTruncatedFileKeepsTheCompletedEvents() {
        val text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:1\r\nSUMMARY:uno\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:2\r\nSUMMARY:du"
        val doc = IcsReader.read(text)
        assertNull(doc.fatal)
        assertEquals(listOf("uno"), events(doc).map { it.property("SUMMARY")!!.value })
        assertTrue(doc.issues.any { it.code == IcsIssueCode.TRUNCATED })
    }

    @Test
    fun aMissingEndOfAnInnerBlockIsReportedAndTheFileStillReads() {
        val doc = IcsReader.read(wrap("BEGIN:VEVENT", "UID:1", "BEGIN:VALARM", "TRIGGER:-PT5M", "END:VEVENT", "BEGIN:VEVENT", "UID:2", "END:VEVENT"))
        assertEquals(2, events(doc).size)
        assertTrue(doc.issues.any { it.code == IcsIssueCode.MISMATCHED_END })
    }

    // U107
    @Test
    fun limitsRejectWithoutExceptions() {
        val hugeLine = "SUMMARY:" + "x".repeat(IcsReader.MAX_LINE + 10)
        val longDoc = IcsReader.read(wrap("BEGIN:VEVENT", "UID:1", hugeLine, "END:VEVENT"))
        assertTrue(longDoc.issues.any { it.code == IcsIssueCode.LINE_TOO_LONG })
        assertNull(events(longDoc).single().property("SUMMARY"))

        val foldedHuge = IcsReader.read(wrap("BEGIN:VEVENT", "SUMMARY:a", *Array(2_000) { " " + "y".repeat(100) }, "END:VEVENT"))
        assertTrue(foldedHuge.issues.any { it.code == IcsIssueCode.LINE_TOO_LONG })

        val deep = StringBuilder()
        repeat(40) { deep.append("BEGIN:X-D$it\r\n") }
        repeat(40) { deep.append("END:X-D${39 - it}\r\n") }
        val deepDoc = IcsReader.read("BEGIN:VCALENDAR\r\n${deep}BEGIN:VEVENT\r\nUID:ok\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")
        assertTrue(deepDoc.issues.any { it.code == IcsIssueCode.TOO_DEEP })
        assertEquals("ok", events(deepDoc).single().property("UID")!!.value)

        val many = StringBuilder("BEGIN:VCALENDAR\r\n")
        repeat(IcsReader.MAX_COMPONENTS + 5) { many.append("BEGIN:VEVENT\r\nEND:VEVENT\r\n") }
        many.append("END:VCALENDAR\r\n")
        val manyDoc = IcsReader.read(many.toString())
        assertTrue(manyDoc.issues.any { it.code == IcsIssueCode.TOO_MANY_COMPONENTS })
        assertTrue(events(manyDoc).size <= IcsReader.MAX_COMPONENTS)
    }

    // U108
    @Test
    fun filesThatAreNotCalendarsAreRefused() {
        for (text in listOf("", "   \n", "<html><body>Login</body></html>", "{\"a\": 1}", "\u0000\u0001\u0002binary", "BEGIN:VEVENT\r\nEND:VEVENT\r\n")) {
            val doc = IcsReader.read(text)
            assertEquals(text, IcsIssueCode.NOT_A_CALENDAR, doc.fatal)
            assertTrue(doc.components.isEmpty())
        }
        assertNotNull(IcsReader.read(wrap()).components)
        assertNull(IcsReader.read(wrap()).fatal)
    }

    @Test
    fun malformedLinesAreSkippedAndCounted() {
        val doc = IcsReader.read(wrap("BEGIN:VEVENT", "no colon here", "UID:1", "END:VEVENT"))
        assertEquals(1, doc.issues.single { it.code == IcsIssueCode.MALFORMED_LINE }.count)
        assertEquals("1", events(doc).single().property("UID")!!.value)
    }
}
