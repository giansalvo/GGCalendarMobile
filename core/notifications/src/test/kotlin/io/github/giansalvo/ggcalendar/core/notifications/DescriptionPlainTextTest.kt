package io.github.giansalvo.ggcalendar.core.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * (U320–U328) The description of an event as plain text, for the alarm screen and for the text of a share or of a mail: no tags
 * written, no entities written, the line breaks of the text kept, and the address of a link kept in brackets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35]) // the HTML reader belongs to Android: the oldest version the app supports and the one of the other tests must give the same text (Robolectric does not know 36 yet)
class DescriptionPlainTextTest {
    private fun text(html: String) = DescriptionPlainText.of(html)

    @Test
    fun `a description without tags is left exactly as it is`() {
        val plain = "Riga 1\nRiga 2\r\n\n   rientro di 3 spazi\nSito: https://example.org/a?x=1&y=2 e a <3 b>"
        assertEquals(plain, text(plain))
        assertEquals("", text(""))
    }

    @Test
    fun `styles and attributes give only their text`() {
        assertEquals("Normale, grassetto, corsivo, sottolineato.", text("Normale, <b>grassetto</b>, <I>corsivo</I>, <u class=\"x\">sottolineato</u>."))
    }

    @Test
    fun `br in any spelling is a line break`() {
        assertEquals("Riga 1\nRiga 2\nRiga 3\nRiga 4", text("Riga 1<br>Riga 2<br/>Riga 3<BR />Riga 4"))
    }

    @Test
    fun `the line breaks of the text are kept`() {
        assertEquals("Quando: ore 10\nLuogo: Sala 2\nMeet: ok", text("Quando: ore 10\nLuogo: Sala 2\nMeet: <b>ok</b>"))
        assertEquals("a\nb c", text("a\r\nb <b>c</b>"))
        assertEquals("uno\n\ndue x", text("uno\n\ndue <i>x</i>"))
    }

    @Test
    fun `entities become their letters`() {
        assertEquals("perché è stato approvato & firmato, 5 € <b> ok", text("perch&eacute; &egrave; stato approvato &amp; firmato, 5 &euro; &lt;b&gt; <b>ok</b>"))
    }

    @Test
    fun `a list has a bullet for every item`() {
        assertEquals("Spesa:\n• pane\n• latte\nFine", text("Spesa:<ul><li>pane</li><li>latte</li></ul>Fine"))
    }

    @Test
    fun `the line breaks that only lay out the list in the source are not text`() {
        assertEquals("Elenco:\n• uno\n• due\nFine", text("Elenco:\n<ul>\n  <li>uno</li>\n  <li>due</li>\n</ul>\nFine"))
    }

    @Test
    fun `a numbered list has bullets too, as the event card shows it`() {
        assertEquals("• uno\n• due", text("<ol><li>uno</li><li>due</li></ol>"))
    }

    @Test
    fun `items outside a list and with styles are bullets too`() {
        assertEquals("• voce fuori\n• grassetto e corsivo", text("<li>voce fuori</li><li><b>grassetto</b> e <i>corsivo</i></li>"))
    }

    @Test
    fun `paragraphs and headings are on lines of their own`() {
        assertEquals(listOf("Prima", "Secondo", "Dopo"), text("Prima<p>Secondo</p>Dopo").lines().filter { it.isNotBlank() })
        assertEquals(listOf("Titolo", "testo"), text("<h1>Titolo</h1>testo").lines().filter { it.isNotBlank() })
    }

    @Test
    fun `a link shows its address in brackets when the text says something else`() {
        assertEquals("verbale (https://esempio.it/verbale)", text("<a href=\"https://esempio.it/verbale\">verbale</a>"))
        assertEquals("Il verbale (http://e.it) è pronto", text("Il <a href='http://e.it'>verbale</a> è pronto"))
    }

    @Test
    fun `a link that says its own address is not repeated`() {
        assertEquals("https://esempio.it", text("<a href=\"https://esempio.it\">https://esempio.it</a>"))
        assertEquals("https://esempio.it/", text("<a href=\"https://esempio.it\">https://esempio.it/</a>"))
        assertEquals("info@e.it", text("<a href=\"mailto:info@e.it\">info@e.it</a>"))
    }

    @Test
    fun `a mail link with another text keeps its address`() {
        assertEquals("scrivici (mailto:info@e.it)", text("<a href=\"mailto:info@e.it\">scrivici</a>"))
    }

    @Test
    fun `an ampersand in the address is written as an ampersand`() {
        assertEquals("link (https://e.it/p?x=1&y=2)", text("<a href=\"https://e.it/p?x=1&amp;y=2\">link</a>"))
    }

    @Test
    fun `links to other schemes or to nothing show only their text`() {
        assertEquals("js file vuoto senza", text("<a href=\"javascript:alert(1)\">js</a> <a href=\"file:///c:/x\">file</a> <a href=\"\">vuoto</a> <a name=\"x\">senza</a>"))
    }

    @Test
    fun `an image leaves no placeholder character`() {
        val result = text("Prima <img src=\"https://e.it/i.png\"> dopo")
        assertFalse(result.contains('\uFFFC'))
        assertEquals("Prima  dopo", result)
    }

    @Test
    fun `a description that is only tags gives nothing`() {
        assertEquals("", text("<br><p></p> <b></b>"))
    }

    @Test
    fun `a tag that is never closed does not lose the text`() {
        assertEquals("grassetto mai chiuso corsivo ancora", text("<b>grassetto mai chiuso <i>corsivo</i> ancora"))
    }

    @Test
    fun `too many empty lines are cut to one and the ends are trimmed`() {
        assertEquals("a\n\nb", text("<p>a</p><br><br><br><br><p>b</p>"))
        assertEquals("ciao", text("  <b>ciao</b>  <br>"))
    }

    @Test
    fun `a no break space is a space`() {
        assertEquals("a b", text("a&nbsp;<b>b</b>"))
    }

    @Test
    fun `the case of the share example is readable`() {
        val source = "Riunione di progetto\nQuando: gio 5 nov 2026, 10:00 - 11:00\nLuogo: Sala 2\nMeet: https://meet.google.com/abc-defg-hij\n\n" +
            "H1 Verbale: <a href=\"https://esempio.it/verbale\">verbale</a>. Il <b>budget</b> e scadenze sono approvati. Parola spezzata: a<b>b</b>c"
        assertEquals(
            "Riunione di progetto\nQuando: gio 5 nov 2026, 10:00 - 11:00\nLuogo: Sala 2\nMeet: https://meet.google.com/abc-defg-hij\n\n" +
                "H1 Verbale: verbale (https://esempio.it/verbale). Il budget e scadenze sono approvati. Parola spezzata: abc",
            text(source),
        )
    }
}
