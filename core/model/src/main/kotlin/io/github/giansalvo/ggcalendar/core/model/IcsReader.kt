package io.github.giansalvo.ggcalendar.core.model

/** One content line: the name and parameter names are upper case, parameter values have no quotes, the value is not unescaped. */
data class IcsProperty(val name: String, val params: Map<String, String>, val value: String)

/** A BEGIN/END block with its properties and nested blocks. */
class IcsComponent(val name: String, val properties: List<IcsProperty>, val children: List<IcsComponent>) {
    fun property(name: String): IcsProperty? = properties.firstOrNull { it.name == name }
    fun all(name: String): List<IcsProperty> = properties.filter { it.name == name }
    fun children(name: String): List<IcsComponent> = children.filter { it.name == name }
}

/** What went wrong or was left out while reading; the summary of an import counts them. */
enum class IcsIssueCode {
    NOT_A_CALENDAR, TRUNCATED, MALFORMED_LINE, LINE_TOO_LONG, TOO_MANY_COMPONENTS, TOO_DEEP, MISMATCHED_END,
    MISSING_START, INVALID_DATE, END_BEFORE_START, UNKNOWN_TZID, INVALID_RRULE, RDATE_IGNORED, THIS_AND_FUTURE,
    ABSOLUTE_ALARM, INVALID_ALARM, ALARM_AFTER_START, ALARM_TOO_EARLY, TOO_MANY_ALARMS, FIELD_TRUNCATED,
    MISSING_UID, DUPLICATE_UID, ATTACH_IGNORED, CATEGORIES_IGNORED,
}

/** [count] occurrences of one kind of issue; [sample] is the first detail seen (a time zone name, a rule...), never a long text. */
data class IcsIssue(val code: IcsIssueCode, val count: Int, val sample: String? = null)

internal class IcsIssues {
    private val counts = LinkedHashMap<IcsIssueCode, Int>()
    private val samples = HashMap<IcsIssueCode, String>()

    fun add(code: IcsIssueCode, sample: String? = null, count: Int = 1) {
        counts[code] = (counts[code] ?: 0) + count
        if (sample != null && code !in samples) samples[code] = sample.take(80)
    }

    fun merge(issue: IcsIssue) = add(issue.code, issue.sample, issue.count)

    fun list(): List<IcsIssue> = counts.map { (code, n) -> IcsIssue(code, n, samples[code]) }
}

/** The content of the calendar(s) of a file: the properties of the first VCALENDAR and the blocks of all of them. */
class IcsDocument(
    val properties: List<IcsProperty>,
    val components: List<IcsComponent>,
    val issues: List<IcsIssue>,
    /** Set when nothing can be read at all (not a calendar). */
    val fatal: IcsIssueCode? = null,
)

/**
 * Reader for the iCalendar text format (RFC 5545): folded lines, quoted parameters, nested blocks. It treats the text as
 * untrusted data: it has limits and never throws. A damaged part is skipped and counted in [IcsDocument.issues]; a file
 * that stops in the middle keeps the blocks that were completed.
 */
object IcsReader {
    const val MAX_LINE = 64 * 1024
    const val MAX_COMPONENTS = 100_000
    const val MAX_DEPTH = 6

    private class Builder(val name: String) {
        val properties = ArrayList<IcsProperty>()
        val children = ArrayList<IcsComponent>()
        fun build() = IcsComponent(name, properties, children)
    }

    fun read(raw: String): IcsDocument {
        val issues = IcsIssues()
        val text = raw.removePrefix("﻿")
        if (text.isBlank() || text.take(1024).contains('\u0000') || !text.trimStart().startsWith("BEGIN:VCALENDAR", ignoreCase = true)) {
            issues.add(IcsIssueCode.NOT_A_CALENDAR)
            return IcsDocument(emptyList(), emptyList(), issues.list(), IcsIssueCode.NOT_A_CALENDAR)
        }
        val root = Builder("ROOT")
        val stack = arrayListOf(root)
        var skipping = 0
        var total = 0
        for (line in logicalLines(text)) {
            if (line.isBlank()) continue
            if (line.length > MAX_LINE) {
                issues.add(IcsIssueCode.LINE_TOO_LONG)
                continue
            }
            val p = parseLine(line)
            if (p == null) {
                issues.add(IcsIssueCode.MALFORMED_LINE)
                continue
            }
            when (p.name) {
                "BEGIN" -> {
                    if (skipping > 0 || stack.size > MAX_DEPTH) {
                        if (skipping == 0) issues.add(IcsIssueCode.TOO_DEEP)
                        skipping++
                        continue
                    }
                    if (++total > MAX_COMPONENTS) {
                        issues.add(IcsIssueCode.TOO_MANY_COMPONENTS)
                        break
                    }
                    stack.add(Builder(p.value.trim().uppercase()))
                }
                "END" -> {
                    if (skipping > 0) {
                        skipping--
                        continue
                    }
                    val name = p.value.trim().uppercase()
                    val at = stack.indexOfLast { it.name == name }
                    if (at <= 0) {
                        issues.add(IcsIssueCode.MISMATCHED_END, name)
                        continue
                    }
                    if (at != stack.lastIndex) issues.add(IcsIssueCode.MISMATCHED_END, name)
                    while (stack.size > at) {
                        val done = stack.removeAt(stack.lastIndex)
                        stack.last().children.add(done.build())
                    }
                }
                else -> if (skipping == 0) stack.last().properties.add(p)
            }
        }
        if (stack.size > 1) {
            // The file stops inside a block: the unfinished blocks inside the calendar are dropped, the calendar itself is kept.
            issues.add(IcsIssueCode.TRUNCATED)
            while (stack.size > 2) stack.removeAt(stack.lastIndex)
            if (stack.size == 2) {
                val open = stack.removeAt(1)
                if (open.name == "VCALENDAR") root.children.add(open.build())
            }
        }
        val calendars = root.children.filter { it.name == "VCALENDAR" }
        if (calendars.isEmpty()) {
            issues.add(IcsIssueCode.NOT_A_CALENDAR)
            return IcsDocument(emptyList(), emptyList(), issues.list(), IcsIssueCode.NOT_A_CALENDAR)
        }
        return IcsDocument(calendars.first().properties, calendars.flatMap { it.children }, issues.list())
    }

    /** Joins the folded lines (a line that starts with a space or a tab continues the previous one). */
    private fun logicalLines(text: String): Sequence<String> = sequence {
        var current: StringBuilder? = null
        for (physical in text.lineSequence()) {
            if (physical.isNotEmpty() && (physical[0] == ' ' || physical[0] == '\t')) {
                val c = current
                // Past the limit the line is rejected anyway: stop growing it.
                if (c != null && c.length <= MAX_LINE) c.append(physical, 1, physical.length)
                continue
            }
            current?.let { yield(it.toString()) }
            current = StringBuilder(physical)
        }
        current?.let { yield(it.toString()) }
    }

    /** `NAME;PARAM=a;PARAM2="b:c":value`; null when the line has no name or no colon. */
    internal fun parseLine(line: String): IcsProperty? {
        val n = line.length
        var i = 0
        while (i < n && line[i] != ':' && line[i] != ';') i++
        if (i == 0 || i >= n) return null
        val name = line.substring(0, i).trim().uppercase()
        val params = LinkedHashMap<String, String>()
        while (i < n && line[i] == ';') {
            i++
            val keyStart = i
            while (i < n && line[i] != '=' && line[i] != ':' && line[i] != ';') i++
            if (i >= n) return null
            val key = line.substring(keyStart, i).trim().uppercase()
            val value = StringBuilder()
            if (line[i] == '=') {
                i++
                var quoted = false
                while (i < n && (quoted || (line[i] != ';' && line[i] != ':'))) {
                    if (line[i] == '"') quoted = !quoted else value.append(line[i])
                    i++
                }
            }
            if (key.isNotEmpty()) params[key] = value.toString()
        }
        if (i >= n || line[i] != ':') return null
        return IcsProperty(name, params, line.substring(i + 1))
    }
}
