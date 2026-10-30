package io.github.giansalvo.ggcalendar

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The texts of one module in one language, read from its `strings.xml` as Android reads them, without Android: the tests of the
 * languages (parity, placeholders, copies, style) run in milliseconds on the JVM. The default language is the folder `values`.
 */
internal class StringXml(val module: String, val entries: Map<String, Entry>) {
    enum class Kind { STRING, PLURALS, ARRAY }

    /** A string (one item with an empty key), a plural (items by quantity) or a string array (items by position). [raw] is the XML text. */
    class Entry(val kind: Kind, val raw: List<Pair<String, String>>) {
        /** The texts as the app shows them: escapes, quotes and spaces treated the way aapt does. */
        val items: List<Pair<String, String>> by lazy { raw.map { it.first to unescape(it.second) } }
    }

    companion object {
        /** The folder of the project, found from the folder where Gradle runs the tests of a module. */
        val root: File by lazy {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
            dir ?: error("settings.gradle.kts not found above ${System.getProperty("user.dir")}")
        }

        private val SKIP = setOf("build", ".gradle", ".git", "tools", "app_planning", ".idea", ".kotlin", "dist")

        /** The modules that have texts to translate, as paths from the root ("feature/event"). */
        val modules: List<String> by lazy {
            root.walkTopDown().onEnter { it.name !in SKIP }
                .filter { it.isFile && it.name == "strings.xml" && it.parentFile.name == "values" && it.invariantSeparatorsPath.contains("/src/main/res/") }
                .map { it.parentFile.parentFile.parentFile.parentFile.parentFile.relativeTo(root).invariantSeparatorsPath }
                .sorted().toList()
        }

        /** The texts of [module] in the language of [folder] ("values", "values-fr"), or null when there is no file. */
        fun load(module: String, folder: String): StringXml? {
            val file = File(root, "$module/src/main/res/$folder/strings.xml")
            if (!file.exists()) return null
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val out = LinkedHashMap<String, Entry>()
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? Element ?: continue
                if (el.getAttribute("translatable") == "false") continue
                val name = el.getAttribute("name")
                fun children(): List<Element> = (0 until el.childNodes.length).mapNotNull { el.childNodes.item(it) as? Element }
                when (el.tagName) {
                    "string" -> out["string:$name"] = Entry(Kind.STRING, listOf("" to el.textContent))
                    "plurals" -> out["plurals:$name"] = Entry(Kind.PLURALS, children().map { it.getAttribute("quantity") to it.textContent })
                    "string-array" -> out["array:$name"] = Entry(Kind.ARRAY, children().mapIndexed { n, e -> n.toString() to e.textContent })
                }
            }
            return StringXml(module, out)
        }

        /**
         * What aapt makes of the text of a resource: leading and trailing spaces dropped and inner runs of spaces reduced to one (unless
         * the text is between double quotes), `\n`, `\'`, `\"`, `\@`, `\?`, `\\` and `\uXXXX` replaced, and an unescaped double
         * quote removed (it only switches the quoted mode). Entities are already replaced by the XML parser.
         */
        fun unescape(raw: String): String {
            val out = StringBuilder()
            var quoted = false
            var pendingSpace = false
            fun put(c: Char) {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                pendingSpace = false
                out.append(c)
            }
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                when {
                    c == '\\' && i + 1 < raw.length -> {
                        val n = raw[i + 1]
                        i++
                        when (n) {
                            'n' -> put('\n')
                            't' -> put('\t')
                            'u' -> {
                                val hex = raw.substring(i + 1, minOf(i + 5, raw.length))
                                put(hex.toIntOrNull(16)?.toChar() ?: 'u')
                                i += hex.length
                            }
                            else -> put(n)
                        }
                    }
                    c == '"' -> quoted = !quoted
                    !quoted && (c == ' ' || c == '\t' || c == '\n' || c == '\r') -> pendingSpace = true
                    else -> put(c)
                }
                i++
            }
            return out.toString()
        }

        /** True when the XML text has a double quote that aapt would swallow in the middle of the text (not the pair that encloses it all). */
        fun hasSwallowedQuote(raw: String): Boolean {
            val positions = mutableListOf<Int>()
            var i = 0
            while (i < raw.length) {
                if (raw[i] == '\\') i++ else if (raw[i] == '"') positions += i
                i++
            }
            if (positions.isEmpty()) return false
            val first = raw.indexOfFirst { !it.isWhitespace() }
            val last = raw.indexOfLast { !it.isWhitespace() }
            return !(positions.size == 2 && positions[0] == first && positions[1] == last)
        }

        private val FORMAT = Regex("%(?:(\\d+)\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?([a-zA-Z])")

        /** The placeholders of a text as "position:conversion", sorted (an unnumbered one takes the next position). `%%` is not one. */
        fun placeholders(text: String): List<String> {
            var next = 1
            return FORMAT.findAll(text.replace("%%", "")).map { m ->
                val position = m.groupValues[1].ifEmpty { (next++).toString() }
                "$position:${m.groupValues[2]}"
            }.sorted().toList()
        }
    }
}
