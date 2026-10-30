package io.github.giansalvo.ggcalendar.feature.event

import io.github.giansalvo.ggcalendar.core.model.Attendee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestInputTest {
    private fun valid(text: String) = (GuestInput.parse(text) as GuestInput.Result.Valid).guests
    private fun emails(text: String) = valid(text).map { it.email }
    private fun invalid(text: String) = (GuestInput.parse(text) as GuestInput.Result.Invalid).text

    @Test fun oneAddress() {
        assertEquals(listOf("anna@example.com"), emails("anna@example.com"))
    }

    @Test fun emptyOrBlankTextIsNoGuestAndNoError() {
        assertEquals(emptyList<String>(), emails(""))
        assertEquals(emptyList<String>(), emails("  \n ; , "))
    }

    @Test fun spacesAroundAreIgnored() {
        assertEquals(listOf("anna@example.com"), emails("  anna@example.com \n"))
    }

    @Test fun severalAddressesWithEverySeparator() {
        assertEquals(
            listOf("a@x.it", "b@x.it", "c@x.it", "d@x.it"),
            emails("a@x.it, b@x.it; c@x.it\nd@x.it"),
        )
        assertEquals(listOf("a@x.it", "b@x.it"), emails("a@x.it b@x.it"))
    }

    @Test fun theSameAddressTwiceIsOneGuestWhateverTheCase() {
        assertEquals(listOf("Anna@Example.com"), emails("Anna@Example.com, anna@example.COM"))
    }

    @Test fun nameAndAddressAsCopiedFromMail() {
        val g = valid("Anna Rossi <anna@example.com>, \"Bianchi, Luca\" <luca@example.com>, <no@example.com>")
        assertEquals(listOf("anna@example.com", "luca@example.com", "no@example.com"), g.map { it.email })
        assertEquals("Anna Rossi", g[0].displayName)
        assertEquals("Bianchi, Luca", g[1].displayName)
        assertEquals(null, g[2].displayName)
    }

    @Test fun mailtoPrefixIsDropped() {
        assertEquals(listOf("anna@example.com"), emails("mailto:anna@example.com"))
    }

    @Test fun aPieceThatIsNotAnAddressIsReportedAsTyped() {
        assertEquals("anna", invalid("anna"))
        assertEquals("ann@", invalid("ok@example.com, ann@"))
    }

    @Test fun oneBadAddressRejectsTheWholeText() {
        assertTrue(GuestInput.parse("a@x.it b") is GuestInput.Result.Invalid)
    }

    @Test fun validAddresses() {
        for (a in listOf("a@b.co", "first.last@example.com", "a+tag@example.com", "o'brien@example.ie", "x_y-z@sub.example.co.uk", "ünï@exämple.de", "a@xn--p1ai.xn--p1ai", "1@2.it")) {
            assertTrue(a, GuestInput.isValid(a))
        }
    }

    @Test fun invalidAddresses() {
        for (a in listOf(
            "", "a", "a@", "@b.it", "a@b", "a@b.", "a@.it", "a@b..it", ".a@b.it", "a.@b.it", "a..b@b.it", "a b@c.it", "a@b c.it",
            "a@@b.it", "a@b@c.it", "a@-b.it", "a@b-.it", "a@b.i", "a@1.2.3.4", "a@b.it,", "<a@b.it>", "a@b_c.it", "a@b.it\u0000",
        )) {
            assertFalse(a, GuestInput.isValid(a))
        }
    }

    @Test fun lengthsAreLimited() {
        val local64 = "a".repeat(64)
        assertTrue(GuestInput.isValid("$local64@example.com"))
        assertFalse(GuestInput.isValid("${local64}a@example.com"))
        assertFalse(GuestInput.isValid("a@" + "b".repeat(64) + ".it"))
        assertFalse(GuestInput.isValid("a@" + "bcd.".repeat(70) + "it"))
    }

    @Test fun veryLongBadInputIsRejectedQuickly() {
        val start = System.nanoTime()
        assertFalse(GuestInput.isValid("a".repeat(5000) + "@" + "b-".repeat(2500)))
        assertFalse(GuestInput.isValid("a.".repeat(3000) + "@x"))
        assertTrue("took too long", (System.nanoTime() - start) / 1_000_000 < 500)
    }

    @Test fun mergeKeepsTheOrderAndSkipsGuestsAlreadyThere() {
        val current = listOf(Attendee("a@x.it"), Attendee("B@x.it", optional = true))
        val merged = GuestInput.merge(current, listOf(Attendee("b@x.it"), Attendee("c@x.it"), Attendee("C@x.it")))
        assertEquals(listOf("a@x.it", "B@x.it", "c@x.it"), merged.map { it.email })
        assertTrue(merged[1].optional)
    }

    @Test fun mergeOfNothingIsTheSameList() {
        val current = listOf(Attendee("a@x.it"))
        assertEquals(current, GuestInput.merge(current, emptyList()))
    }
}
