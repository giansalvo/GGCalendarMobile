package io.github.giansalvo.ggcalendar.core.notifications

import androidx.core.text.HtmlCompat

/**
 * The description of an event as plain text, for the places that cannot show HTML: the alarm screen and the text of a share or
 * of a mail. Google descriptions are often HTML, and written as they are they would show `<b>`, `<br>` and `&egrave;`.
 *
 * - A description without tags is left exactly as it is (its line breaks and spaces are the person's own).
 * - Otherwise Android's own HTML reader gives the text, as the event card does, with these differences: the line breaks of the
 *   text are kept (a plain-text reader cannot put them back), each list item starts with a bullet, and a link also shows its
 *   address in brackets (`text (https://…)`) when that is not what the link says, since the address is not there in plain text.
 * - Only `http`, `https` and `mailto` addresses are written; other links (`javascript:`, `file:`, empty) show their text only.
 */
object DescriptionPlainText {
    /** The same test the event card uses to decide that a description is HTML. */
    private val TAG = Regex("<[a-zA-Z/][^>]*>")

    private const val BLOCKS = "ul|ol|li|p|div|h[1-6]|br|table|tr|td|th|blockquote|hr"

    // A line break next to a block tag is only the layout of the source, not a break of the text.
    private val AFTER_BLOCK = Regex("(?i)(</?(?:$BLOCKS)\\b[^>]*>)[ \\t]*\\n")
    private val BEFORE_BLOCK = Regex("(?i)\\n[ \\t]*(</?(?:$BLOCKS)\\b[^>]*>)")
    private val LINK = Regex("(?is)<a\\s[^>]*?href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')[^>]*>(.*?)</a\\s*>")
    private val ITEM = Regex("(?i)<li(\\s[^>]*)?>")
    private val ANY_TAG = Regex("<[^>]*>")
    private val WRITABLE_ADDRESS = Regex("(?i)^(https?://|mailto:)\\S+")
    private val BLANK_LINES = Regex("\n{3,}")

    /** [description] as plain text, trimmed; empty when nothing is left to read. */
    fun of(description: String): String {
        if (!TAG.containsMatchIn(description)) return description
        var html = description.replace("\r\n", "\n").replace('\r', '\n')
        html = LINK.replace(html) { link(it) }
        html = ITEM.replace(html, "<li>\u2022 ")
        html = AFTER_BLOCK.replace(html, "\$1")
        html = BEFORE_BLOCK.replace(html, "\$1")
        html = html.replace("\n", "<br>")
        val text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
        return text
            .replace("\uFFFC", "") // what the reader puts where an image was
            .replace('\u00A0', ' ')
            .lines().joinToString("\n") { it.trimEnd() }
            .replace(BLANK_LINES, "\n\n")
            .trim()
    }

    /** What an `<a>` becomes before the reader sees it: its content, followed by the address in brackets when that adds something. */
    private fun link(match: MatchResult): String {
        val href = (match.groups[1] ?: match.groups[2])?.value.orEmpty()
        val inner = match.groups[3]?.value.orEmpty()
        val address = href.replace("&amp;", "&").trim()
        if (!WRITABLE_ADDRESS.matches(address)) return inner
        val said = inner.replace(ANY_TAG, "").replace("&amp;", "&").trim()
        if (said.sameAddress(address) || said.sameAddress(address.removePrefix("mailto:"))) return inner
        return "$inner ($href)"
    }

    private fun String.sameAddress(other: String) = trimEnd('/').equals(other.trimEnd('/'), ignoreCase = true)
}
