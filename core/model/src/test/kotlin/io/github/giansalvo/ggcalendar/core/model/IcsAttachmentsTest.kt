package io.github.giansalvo.ggcalendar.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** The attachments in a `.ics` (issue #3, phase 3): only https links are read, nothing is downloaded, and the writer gives them back. */
class IcsAttachmentsTest {
    private val options = IcsImportOptions(calendarId = "cal", defaultZone = ZoneId.of("Europe/Rome"))
    private val ex = "https://example.com"
    private val drive = "https://drive.google.com/open?id=FAKEFILEID0123456789abcdef&authuser=1"

    private fun ics(vararg lines: String) =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//test//EN") + lines + "END:VCALENDAR").joinToString("\r\n") + "\r\n"

    private fun event(uid: String, vararg body: String) =
        listOf("BEGIN:VEVENT", "UID:$uid", "DTSTAMP:20261021T000000Z", "DTSTART:20261108T090000Z", "DTEND:20261108T100000Z", "SUMMARY:x", *body, "END:VEVENT")

    private fun read(vararg body: String) = IcsMapper.parse(ics(*event("u1", *body).toTypedArray()), options)

    private fun one(vararg body: String) = read(*body).events.single()

    private fun count(r: IcsImport, code: IcsIssueCode) = r.report.issues.filter { it.code == code }.sumOf { it.count }

    // ---- what is read

    @Test
    fun anHttpsLinkIsKeptWithItsTypeAndName() {
        val a = one("ATTACH;FMTTYPE=application/pdf;FILENAME=verbale.pdf:$ex/files/verbale.pdf").attachments.single()
        assertEquals(EventAttachment("$ex/files/verbale.pdf", "verbale.pdf", "application/pdf"), a)
    }

    @Test
    fun theOrderOfTheParametersDoesNotMatter() {
        val a = one("ATTACH;FILENAME=verbale.pdf;FMTTYPE=application/pdf:$ex/x").attachments.single()
        assertEquals("verbale.pdf", a.title)
        assertEquals("application/pdf", a.mimeType)
    }

    @Test
    fun aQuotedNameWithSeparatorsIsRead() {
        val a = one("ATTACH;FMTTYPE=application/pdf;FILENAME=\"Bilancio 2026, bozza: v2.pdf\":$drive").attachments.single()
        assertEquals("Bilancio 2026, bozza: v2.pdf", a.title)
        assertEquals(drive, a.url)
    }

    @Test
    fun withoutNameOrTypeTheyComeFromTheAddress() {
        val a = one("ATTACH:$ex/img/schema.png").attachments.single()
        assertEquals("schema.png", a.title)
        assertEquals("image/png", a.mimeType)
    }

    @Test
    fun aNameWithAccentsSurvives() = assertEquals("Caffè e città; versione 2.pdf", one("ATTACH;FILENAME=\"Caffè e città; versione 2.pdf\":$ex/a.pdf").attachments.single().title)

    @Test
    fun aLongFoldedAddressIsReadWhole() {
        val long = "$ex/documenti/archivio/2026/ottobre/relazione-annuale-completa-con-un-nome-molto-lungo.pdf?versione=2&sezione=introduzione&token=" + "x".repeat(90)
        val folded = ("ATTACH;FMTTYPE=application/pdf:" + long).chunked(60).joinToString("\r\n ")
        assertEquals(long, one(folded).attachments.single().url)
    }

    @Test
    fun aBadTypeFallsBackToTheExtension() = assertEquals("application/pdf", one("ATTACH;FMTTYPE=not a type:$ex/a.pdf").attachments.single().mimeType)

    @Test
    fun theSameAddressTwiceIsOneAttachmentAndNothingIsIgnored() {
        val r = read("ATTACH:$ex/d.pdf", "ATTACH;FMTTYPE=application/pdf:$ex/d.pdf", "ATTACH;FILENAME=altro.pdf:$ex/d.pdf")
        assertEquals(1, r.events.single().attachments.size)
        assertEquals(0, count(r, IcsIssueCode.ATTACH_IGNORED))
        assertEquals(0, count(r, IcsIssueCode.ATTACH_CUT))
    }

    @Test
    fun theAttachmentsOfAnAlarmAreNotThoseOfTheEvent() {
        val e = one("BEGIN:VALARM", "ACTION:AUDIO", "TRIGGER:-PT15M", "ATTACH;VALUE=URI:Chord", "END:VALARM")
        assertTrue(e.attachments.isEmpty())
    }

    @Test
    fun anEventWithoutAttachmentsHasAnEmptyList() = assertTrue(one("SUMMARY:y").attachments.isEmpty())

    // ---- what is ignored

    private val bad = listOf(
        "ATTACH:http://example.com/insicuro.pdf",
        "ATTACH;ENCODING=BASE64;VALUE=BINARY:SGVsbG8gR0dDYWxlbmRhcg==",
        "ATTACH:javascript:alert(1)",
        "ATTACH:file:///sdcard/Download/segreto.txt",
        "ATTACH:ftp://example.com/x.zip",
        "ATTACH:data:text/plain;base64,SGVsbG8=",
        "ATTACH:https://utente:password@example.com/riservato.pdf",
        "ATTACH:cid:foto@example.com",
        "ATTACH:",
        "ATTACH:https://example.com/con spazio.pdf",
    )

    @Test
    fun everyKindThatIsNotAnHttpsLinkIsIgnoredAndCounted() {
        val r = read(*(bad + "ATTACH:$ex/valido.pdf").toTypedArray())
        assertEquals(listOf("$ex/valido.pdf"), r.events.single().attachments.map { it.url })
        assertEquals(bad.size, count(r, IcsIssueCode.ATTACH_IGNORED))
    }

    @Test
    fun aFileInsideTheCalendarIsNeverKept() = assertTrue(one("ATTACH;ENCODING=BASE64;VALUE=BINARY:SGVsbG8=").attachments.isEmpty())

    @Test
    fun onlyHttpLeavesNoAttachmentAndOneIgnored() {
        val r = read("ATTACH:http://example.com/solo-http.pdf")
        assertTrue(r.events.single().attachments.isEmpty())
        assertEquals(1, count(r, IcsIssueCode.ATTACH_IGNORED))
    }

    // ---- the limit

    private fun many(n: Int, base: String) = (0 until n).map { "ATTACH:$ex/$base/$it.txt" }

    @Test
    fun exactly25AreKeptAndNothingIsCut() {
        val r = read(*many(25, "v").toTypedArray())
        assertEquals(25, r.events.single().attachments.size)
        assertEquals(0, count(r, IcsIssueCode.ATTACH_CUT))
    }

    @Test
    fun the26thAndTheNextAreCut() {
        val r = read(*many(30, "t").toTypedArray())
        assertEquals(25, r.events.single().attachments.size)
        assertEquals(5, count(r, IcsIssueCode.ATTACH_CUT))
        assertEquals(0, count(r, IcsIssueCode.ATTACH_IGNORED))
    }

    @Test
    fun thirtyLinesWith28DifferentAddressesCutThree() {
        val r = read(*(0 until 30).map { "ATTACH:$ex/d/${it % 28}.txt" }.toTypedArray())
        assertEquals(25, r.events.single().attachments.size)
        assertEquals(3, count(r, IcsIssueCode.ATTACH_CUT))
    }

    @Test
    fun theKeptOnesAreTheFirstOnes() = assertEquals((0 until 25).map { "$ex/t/$it.txt" }, one(*many(30, "t").toTypedArray()).attachments.map { it.url })

    // ---- the writer

    private val start = Instant.parse("2026-11-08T09:00:00Z")
    private val end = Instant.parse("2026-11-08T10:00:00Z")
    private val now = Instant.parse("2026-10-12T10:00:00Z")

    private fun write(vararg a: EventAttachment) =
        IcsWriter.write(listOf(IcsEvent("w1@x", "Riunione", EventTime.Timed(start, end, null), attachments = a.toList())), now)

    private fun lines(text: String) = text.replace("\r\n ", "").split("\r\n").filter { it.isNotEmpty() }

    @Test
    fun anAttachmentIsWrittenWithTypeAndName() {
        val l = lines(write(EventAttachment("$ex/files/verbale.pdf", "verbale.pdf", "application/pdf")))
        assertTrue("ATTACH;FMTTYPE=application/pdf;FILENAME=verbale.pdf:$ex/files/verbale.pdf" in l)
    }

    @Test
    fun theTypeAndTheNameAreOnlyWrittenWhenThereAre() {
        val l = lines(write(EventAttachment("$ex/a", "", null)))
        assertTrue("ATTACH:$ex/a" in l)
    }

    @Test
    fun aNameWithSeparatorsIsQuoted() {
        val l = lines(write(EventAttachment(drive, "Bilancio 2026, bozza.pdf", "application/pdf")))
        assertTrue("ATTACH;FMTTYPE=application/pdf;FILENAME=\"Bilancio 2026, bozza.pdf\":$drive" in l)
    }

    @Test
    fun noLineIsLongerThan75Bytes() {
        val long = "$ex/documenti/archivio/2026/ottobre/relazione-annuale-completa-con-un-nome-molto-lungo.pdf?versione=2&token=" + "x".repeat(200)
        val text = write(EventAttachment(long, "Caffè e città con un titolo molto molto lungo per vedere il ripiegamento delle righe.pdf", "application/pdf"))
        text.split("\r\n").forEach { assertTrue("line of ${it.toByteArray().size} bytes", it.toByteArray().size <= 75) }
        assertTrue(lines(text).any { it.startsWith("ATTACH;") && it.endsWith(long) })
    }

    @Test
    fun onlyHttpsLinksAreWritten() {
        val l = lines(write(EventAttachment("http://example.com/a.pdf", "a"), EventAttachment("$ex/b.pdf", "b")))
        assertEquals(1, l.count { it.startsWith("ATTACH") })
        assertFalse(l.any { it.contains("http://") })
    }

    @Test
    fun atMost25AreWrittenWithoutRepeatedAddresses() {
        val list = (1..40).map { EventAttachment("$ex/${it % 30}.pdf", "t$it") }
        assertEquals(25, lines(write(*list.toTypedArray())).count { it.startsWith("ATTACH") })
    }

    @Test
    fun anEventWithoutAttachmentsWritesNone() = assertEquals(0, lines(write()).count { it.startsWith("ATTACH") })

    @Test
    fun whatIsWrittenIsReadBack() {
        val sent = listOf(
            EventAttachment("$ex/files/verbale.pdf", "verbale.pdf", "application/pdf"),
            EventAttachment(drive, "Bilancio 2026, bozza: v2.pdf", "application/pdf"),
            EventAttachment("$ex/img/schema.png", "Schema città", "image/png"),
            EventAttachment("$ex/senza-tipo", "Senza tipo", null),
        )
        val back = IcsMapper.parse(write(*sent.toTypedArray()), options).events.single().attachments
        assertEquals(sent.map { it.url }, back.map { it.url })
        assertEquals(sent.map { it.title }, back.map { it.title })
        assertEquals(sent.map { it.mimeType }, back.map { it.mimeType })
    }

    @Test
    fun theFileOfTheDesktopIsRead() {
        // The desktop app writes the type before the name; Google writes the name first: both are read.
        val a = one("ATTACH;FMTTYPE=image/jpeg;FILENAME=foto.jpg:$ex/f/foto.jpg", "ATTACH;FILENAME=\"Piano, 2026.pdf\";FMTTYPE=application/pdf:$ex/f/piano.pdf").attachments
        assertEquals(listOf("foto.jpg", "Piano, 2026.pdf"), a.map { it.title })
        assertNull(a.firstOrNull { it.url.endsWith("none") })
    }
}
