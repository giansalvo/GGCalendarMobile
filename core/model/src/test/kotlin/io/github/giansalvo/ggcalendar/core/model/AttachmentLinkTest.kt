package io.github.giansalvo.ggcalendar.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of the links of the attachments (issue #3): https only, the limits, the name and the type taken from the address. */
class AttachmentLinkTest {
    private val pdf = "https://example.com/files/verbale.pdf"
    private val drive = "https://drive.google.com/open?id=FAKEFILEID0123456789abcdef&authuser=1"

    // ---- which addresses are valid

    @Test fun anHttpsAddressIsValid() = assertTrue(AttachmentLink.isValid(pdf))
    @Test fun theSchemeIsRead_withoutCapitals() = assertTrue(AttachmentLink.isValid("HTTPS://Example.com/a.pdf"))
    @Test fun aPortIsAllowed() = assertTrue(AttachmentLink.isValid("https://example.com:8443/a.pdf"))
    @Test fun aQueryAndAFragmentAreAllowed() = assertTrue(AttachmentLink.isValid("https://example.com/a.pdf?v=2&x=%C3%A8#top"))
    @Test fun anAddressWithoutAPathIsValid() = assertTrue(AttachmentLink.isValid("https://example.com"))

    @Test fun httpIsNotValid() = assertFalse(AttachmentLink.isValid("http://example.com/a.pdf"))
    @Test fun ftpIsNotValid() = assertFalse(AttachmentLink.isValid("ftp://example.com/a.zip"))
    @Test fun fileIsNotValid() = assertFalse(AttachmentLink.isValid("file:///sdcard/Download/segreto.txt"))
    @Test fun javascriptIsNotValid() = assertFalse(AttachmentLink.isValid("javascript:alert(1)"))
    @Test fun dataIsNotValid() = assertFalse(AttachmentLink.isValid("data:text/plain;base64,SGVsbG8="))
    @Test fun cidIsNotValid() = assertFalse(AttachmentLink.isValid("cid:foto@example.com"))
    @Test fun aRelativeAddressIsNotValid() = assertFalse(AttachmentLink.isValid("example.com/a.pdf"))
    @Test fun theEmptyAddressIsNotValid() = assertFalse(AttachmentLink.isValid(""))
    @Test fun nullIsNotValid() = assertFalse(AttachmentLink.isValid(null))
    @Test fun noHostIsNotValid() = assertFalse(AttachmentLink.isValid("https:///a.pdf"))
    @Test fun credentialsInTheAddressAreNotValid() = assertFalse(AttachmentLink.isValid("https://utente:password@example.com/riservato.pdf"))
    @Test fun aUserNameAloneIsNotValid() = assertFalse(AttachmentLink.isValid("https://utente@example.com/a.pdf"))
    @Test fun aSpaceIsNotValid() = assertFalse(AttachmentLink.isValid("https://example.com/con spazio.pdf"))
    @Test fun aTabIsNotValid() = assertFalse(AttachmentLink.isValid("https://example.com/a\t.pdf"))
    @Test fun aNewLineIsNotValid() = assertFalse(AttachmentLink.isValid("https://example.com/a\n.pdf"))
    @Test fun aControlCharacterIsNotValid() = assertFalse(AttachmentLink.isValid("https://example.com/a\u0007.pdf"))
    @Test fun theDeleteCharacterIsNotValid() = assertFalse(AttachmentLink.isValid("https://example.com/a\u007f.pdf"))

    @Test fun anAddressOf2048CharactersIsValid() {
        val url = "https://example.com/" + "a".repeat(2048 - "https://example.com/".length)
        assertEquals(2048, url.length)
        assertTrue(AttachmentLink.isValid(url))
    }

    @Test fun anAddressOf2049CharactersIsNotValid() = assertFalse(AttachmentLink.isValid("https://example.com/" + "a".repeat(2049 - "https://example.com/".length)))

    // ---- the host, the name and the type

    @Test fun theHostIsInLowerCaseWithoutPort() = assertEquals("example.com", AttachmentLink.host("https://Example.COM:8443/a.pdf"))
    @Test fun aHostIsNotGivenForAnInvalidAddress() = assertNull(AttachmentLink.host("http://example.com"))
    @Test fun theNameIsTheLastPartOfThePath() = assertEquals("verbale.pdf", AttachmentLink.fileName(pdf))
    @Test fun theNameDoesNotHaveTheQuery() = assertEquals("a.pdf", AttachmentLink.fileName("https://example.com/x/a.pdf?versione=2#top"))
    @Test fun theNameIsDecoded() = assertEquals("senza titolo.docx", AttachmentLink.fileName("https://example.com/docs/senza%20titolo.docx"))
    @Test fun aPlusIsKeptInTheName() = assertEquals("a+b.pdf", AttachmentLink.fileName("https://example.com/a+b.pdf"))
    @Test fun accentsInTheNameAreDecoded() = assertEquals("caffè.pdf", AttachmentLink.fileName("https://example.com/caff%C3%A8.pdf"))
    @Test fun aTrailingSlashLeavesTheLastPart() = assertEquals("cartella", AttachmentLink.fileName("https://example.com/cartella/"))
    @Test fun noPathMeansNoName() = assertEquals("", AttachmentLink.fileName("https://example.com"))
    @Test fun aPageOfDriveHasNoName() = assertEquals("", AttachmentLink.fileName(drive))
    @Test fun aPageOfDocsHasNoName() = assertEquals("", AttachmentLink.fileName("https://docs.google.com/document/d/abc/edit"))
    @Test fun aNameIsNotLongerThan256() = assertEquals(256, AttachmentLink.fileName("https://example.com/" + "n".repeat(300) + ".pdf").length)

    @Test fun theTypeComesFromTheExtension() {
        assertEquals("application/pdf", AttachmentLink.mimeType(pdf))
        assertEquals("image/png", AttachmentLink.mimeType("https://example.com/img/schema.png"))
        assertEquals("image/jpeg", AttachmentLink.mimeType("https://example.com/foto.JPG"))
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", AttachmentLink.mimeType("https://example.com/docs/a.docx"))
        assertEquals("application/zip", AttachmentLink.mimeType("https://example.com/a.zip"))
    }

    @Test fun anUnknownExtensionHasNoType() = assertNull(AttachmentLink.mimeType("https://example.com/a.xyz"))
    @Test fun aNameWithoutExtensionHasNoType() = assertNull(AttachmentLink.mimeType("https://example.com/readme"))
    @Test fun aPageOfDriveHasNoType() = assertNull(AttachmentLink.mimeType(drive))

    // ---- what is made from what the person typed

    @Test fun aCreatedAttachmentTakesTheNameOfTheFileAsTitle() {
        val a = AttachmentLink.create("  $pdf  ")!!
        assertEquals(pdf, a.url)
        assertEquals("verbale.pdf", a.title)
        assertEquals("application/pdf", a.mimeType)
    }

    @Test fun theTitleTypedWins() = assertEquals("Verbale del 6 nov", AttachmentLink.create(pdf, "  Verbale del 6 nov ")!!.title)
    @Test fun anAddressThatIsNotValidCreatesNothing() = assertNull(AttachmentLink.create("http://example.com/a.pdf", "x"))
    @Test fun aLinkOfDriveNeedsTheTitleAsName() = assertEquals("", AttachmentLink.create(drive)!!.title)
    @Test fun aTitleIsCutAt256() = assertEquals(256, AttachmentLink.create(pdf, "t".repeat(400))!!.title.length)

    @Test fun theDisplayNameIsTheTitle() = assertEquals("Bilancio", EventAttachment(pdf, "Bilancio").displayName)
    @Test fun withoutTitleTheDisplayNameIsTheFileName() = assertEquals("verbale.pdf", EventAttachment(pdf).displayName)
    @Test fun withoutTitleAndFileNameTheDisplayNameIsTheAddress() = assertEquals(drive, EventAttachment(drive).displayName)
    @Test fun aLinkThatIsNotHttpsCannotBeOpened() = assertFalse(EventAttachment("http://example.com/a.pdf").canOpen)
    @Test fun aLinkOfDriveIsRecognised() = assertTrue(EventAttachment(drive).isDrive)
    @Test fun aFileIdMakesItDrive() = assertTrue(EventAttachment(pdf, fileId = "abc").isDrive)
    @Test fun anOrdinaryLinkIsNotDrive() = assertFalse(EventAttachment(pdf).isDrive)

    // ---- adding to the list

    @Test fun addingAppendsTheAttachment() {
        val r = AttachmentLink.add(emptyList(), pdf, "")
        assertEquals(1, (r as AttachmentAdd.Added).list.size)
    }

    @Test fun theSameAddressTwiceIsOneAttachmentWithoutError() {
        val first = (AttachmentLink.add(emptyList(), pdf, "") as AttachmentAdd.Added).list
        assertEquals(AttachmentAdd.Duplicate, AttachmentLink.add(first, pdf, "altro titolo"))
    }

    @Test fun anAddressThatIsNotValidIsInvalid() = assertEquals(AttachmentAdd.Invalid, AttachmentLink.add(emptyList(), "http://x.org/a", ""))

    @Test fun the26thIsRefused() {
        val full = (1..25).map { EventAttachment("https://example.com/$it.pdf") }
        assertEquals(AttachmentAdd.LimitReached, AttachmentLink.add(full, "https://example.com/26.pdf", ""))
    }

    @Test fun the25thIsAccepted() {
        val list = (1..24).map { EventAttachment("https://example.com/$it.pdf") }
        assertEquals(25, (AttachmentLink.add(list, "https://example.com/25.pdf", "") as AttachmentAdd.Added).list.size)
    }

    @Test fun aDuplicateAt25IsNotAnError() {
        val full = (1..25).map { EventAttachment("https://example.com/$it.pdf") }
        assertEquals(AttachmentAdd.Duplicate, AttachmentLink.add(full, "https://example.com/3.pdf", ""))
    }

    @Test fun cleaningRemovesRepeatedAddressesAndCutsAt25() {
        val list = (1..30).map { EventAttachment("https://example.com/${it % 28}.pdf") }
        val (kept, cut) = AttachmentLink.clean(list)
        assertEquals(25, kept.size)
        assertEquals(30 - 25, cut)
        assertEquals(25, kept.map { it.url }.toSet().size)
    }

    // ---- the search

    @Test fun theSearchTextHasTheTitleAndTheFileNameButNotTheAddress() {
        val text = AttachmentLink.searchText(listOf(EventAttachment("https://example.com/archivio/verbale.pdf", "Riunione")))!!
        assertTrue(text.contains("riunione"))
        assertTrue(text.contains("verbale.pdf"))
        assertFalse(text.contains("example"))
        assertFalse(text.contains("archivio"))
    }

    @Test fun theSearchTextHasNoAccentsOrCapitals() = assertEquals("caffe e citta", AttachmentLink.searchText(listOf(EventAttachment("https://example.com", "Caffè e Città")))!!.trim())
    @Test fun noAttachmentsHaveNoSearchText() {
        assertNull(AttachmentLink.searchText(null))
        assertNull(AttachmentLink.searchText(emptyList()))
    }

    @Test fun theNormalFormRemovesAccentsAndCapitals() = assertEquals("e cosi", AttachmentLink.normalize("È Così"))

    @Test fun theFirstMatchIsFoundInTheTitleOrTheFileName() {
        val list = listOf(EventAttachment("https://example.com/a.pdf", "Bilancio"), EventAttachment("https://example.com/verbale.pdf", "Riunione"))
        assertEquals("Riunione", AttachmentLink.firstMatch(list, "VERBALE")!!.displayName)
        assertEquals("Bilancio", AttachmentLink.firstMatch(list, "bilanc")!!.displayName)
    }

    @Test fun theAddressIsNotSearched() = assertNull(AttachmentLink.firstMatch(listOf(EventAttachment("https://example.com/a.pdf", "Bilancio")), "example"))
    @Test fun aMatchIgnoresAccents() = assertNotNull(AttachmentLink.firstMatch(listOf(EventAttachment("https://example.com/a.pdf", "Caffè")), "caffe"))
    @Test fun aBlankQueryMatchesNothing() = assertNull(AttachmentLink.firstMatch(listOf(EventAttachment("https://example.com/a.pdf", "Bilancio")), "  "))
}
