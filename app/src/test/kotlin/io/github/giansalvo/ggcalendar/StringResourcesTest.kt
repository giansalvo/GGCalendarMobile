package io.github.giansalvo.ggcalendar

import io.github.giansalvo.ggcalendar.StringXml.Entry
import io.github.giansalvo.ggcalendar.StringXml.Kind
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The texts of the app in every language it has (Italian, German, Spanish, French) against the default ones (`values`, English):
 * the same texts, the same placeholders, plurals with the quantities of the language, no text left in English, no space or quote
 * that Android would swallow. The lint of the CI already stops a missing translation or a wrong format; these checks add what it
 * does not look at. What is found in a language already published and not corrected yet is listed in [KNOWN] (reported, not blocking).
 */
@RunWith(Parameterized::class)
class StringResourcesTest(private val language: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun languages() = listOf("it", "de", "es", "fr")

        /**
         * Findings in the languages already published, left as they are for now. Keys are "language/check", values are the beginnings of
         * the problems ("module:kind:name") that are accepted; the reason is in the comment.
         */
        private val KNOWN: Map<String, Set<String>> = mapOf(
            // Nothing at the moment. The two Italian texts with plain double quotes in the middle (alarm_duration_help and close_alarm_text,
            // which Android showed without quotes) now use « » in Italian and “ ” in English, like the rest of the app.
        )

        /**
         * Words that are the same as in English in a language on purpose (a word that two languages really share), by language. Like the
         * list of "same words" of the desktop app: a new text equal to the English one must be translated, or be added here.
         */
        private val SAME_WORDS: Map<String, Set<String>> = mapOf(
            "it" to setOf("Banana", "Email", "No"),
            "de" to setOf("Flamingo", "Name", "Optional", "Status", "Text", "Version", "Website"),
            "es" to setOf("No"),
            "fr" to setOf("Contacts", "Description", "Graphite", "Notification", "Notifications", "Public", "Version", "occurrence", "occurrences", "minute", "minutes"),
        )

        /** Names and formats that are the same in every language. */
        private val SAME_EVERYWHERE = Regex("Google Meet|Google Drive|Google|GGCalendar|iCal|\\.ics|\\.csv|CSV|OK|https?://|webcal://|\\b(?:min|h|d)\\b")
        private val PLACEHOLDER = Regex("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z]|%%")
    }

    private class Row(val module: String, val key: String, val base: Entry, val translation: Entry?) {
        val id get() = "$module:$key"
    }

    private val folder = "values-$language"
    private val allowedQuantities = if (language == "de") setOf("one", "other") else setOf("one", "many", "other")

    private val rows: List<Row> by lazy {
        StringXml.modules.flatMap { module ->
            val base = StringXml.load(module, "values")!!
            val translated = StringXml.load(module, folder)
            base.entries.map { (key, entry) -> Row(module, key, entry, translated?.entries?.get(key)) }
        }
    }

    private fun report(check: String, problems: List<String>) {
        val known = KNOWN["$language/$check"].orEmpty()
        val real = problems.filterNot { p -> known.any { p.startsWith(it) } }
        assertTrue("$language, $check (${real.size}):\n" + real.joinToString("\n"), real.isEmpty())
    }

    @Test
    fun everyModuleWithTextsHasTheLanguage() {
        report("files", StringXml.modules.filter { StringXml.load(it, "values")!!.entries.isNotEmpty() && StringXml.load(it, folder) == null }.map { "$it: no $folder/strings.xml" })
    }

    @Test
    fun sameTextsAsTheDefaultLanguage() {
        val problems = mutableListOf<String>()
        for (module in StringXml.modules) {
            val base = StringXml.load(module, "values")!!
            val translated = StringXml.load(module, folder) ?: continue
            problems += (translated.entries.keys - base.entries.keys).map { "$module:$it: not in the default language" }
        }
        for (r in rows) {
            val t = r.translation
            if (t == null) {
                problems += "${r.id}: missing"
                continue
            }
            if (t.kind != r.base.kind) problems += "${r.id}: not the same kind of text"
            if (r.base.kind == Kind.ARRAY && t.raw.size != r.base.raw.size) problems += "${r.id}: ${t.raw.size} items instead of ${r.base.raw.size}"
            if (r.base.kind == Kind.PLURALS) {
                val quantities = t.raw.map { it.first }
                if ("other" !in quantities) problems += "${r.id}: no quantity \"other\""
                if (r.base.raw.any { it.first == "one" } && "one" !in quantities) problems += "${r.id}: no quantity \"one\""
                (quantities - allowedQuantities).forEach { problems += "${r.id}: quantity \"$it\" is not one of $language" }
            }
        }
        report("keys", problems)
    }

    @Test
    fun noEmptyText() {
        report("empty", rows.filter { r -> r.translation?.items?.any { it.second.isBlank() } == true }.map { "${it.id}: empty" })
    }

    @Test
    fun samePlaceholders() {
        val problems = mutableListOf<String>()
        for (r in rows) {
            val t = r.translation ?: continue
            if (r.base.kind == Kind.PLURALS) {
                // The form for "other" has all the placeholders; "one" and "many" may leave the number out ("une fois").
                val full = StringXml.placeholders(r.base.items.first { it.first == "other" }.second)
                for ((quantity, text) in t.items) {
                    val found = StringXml.placeholders(text)
                    val ok = if (quantity == "other") found == full else full.containsAll(found)
                    if (!ok) problems += "${r.id}[$quantity]: $found instead of $full"
                }
            } else {
                r.base.items.forEachIndexed { n, (_, text) ->
                    val found = t.items.getOrNull(n)?.second?.let { StringXml.placeholders(it) }
                    if (found != StringXml.placeholders(text)) problems += "${r.id}${if (r.base.kind == Kind.ARRAY) "[$n]" else ""}: $found instead of ${StringXml.placeholders(text)}"
                }
            }
        }
        report("placeholders", problems)
    }

    @Test
    fun noTextLeftInTheDefaultLanguage() {
        val problems = mutableListOf<String>()
        for (r in rows) {
            val t = r.translation ?: continue
            r.base.items.forEachIndexed { n, (quantity, text) ->
                val other = t.items.getOrNull(n)?.second ?: return@forEachIndexed
                val words = text.replace(PLACEHOLDER, "").replace(SAME_EVERYWHERE, "").replace(Regex("[()<>\n]"), " ").trim()
                if (other == text && words.any { it.isLetter() } && words !in SAME_WORDS[language].orEmpty()) problems += "${r.id}${if (quantity.isNotEmpty()) "[$quantity]" else ""}: same as the default language: $text"
            }
        }
        report("copies", problems)
    }

    @Test
    fun noQuoteSilentlyRemovedAndNoStraySpaces() {
        val problems = mutableListOf<String>()
        for (r in rows) {
            val t = r.translation ?: continue
            t.raw.forEachIndexed { n, (_, raw) ->
                if (StringXml.hasSwallowedQuote(raw)) problems += "${r.id}[$n]: a double quote in the middle of the text is removed by Android (write \\\" or use the quotes of the language)"
            }
            t.items.forEachIndexed { n, (_, text) ->
                if ("  " in text) problems += "${r.id}[$n]: two spaces in a row"
                val base = r.base.items.getOrNull(n)?.second ?: return@forEachIndexed
                if (text.startsWith(" ") != base.startsWith(" ") || text.endsWith(" ") != base.endsWith(" ")) problems += "${r.id}[$n]: space at the start or the end that the default text does not have (or the other way round)"
            }
        }
        report("quotes-and-spaces", problems)
    }
}
