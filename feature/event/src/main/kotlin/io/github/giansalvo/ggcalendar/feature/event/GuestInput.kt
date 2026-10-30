package io.github.giansalvo.ggcalendar.feature.event

import io.github.giansalvo.ggcalendar.core.model.Attendee

/**
 * What is typed in the guest field of the editor: one address, or several separated by comma, semicolon, space or new line.
 * "Name <address>" (as copied from a mail) is read too. The field is read when Done, + or Save is pressed, so an address that
 * was typed but not confirmed is not lost.
 */
internal object GuestInput {
    sealed interface Result {
        /** Every address of the text, in order and without repetitions; empty for an empty text. */
        data class Valid(val guests: List<Attendee>) : Result

        /** The first piece of the text that is not an address, as typed. */
        data class Invalid(val text: String) : Result
    }

    private const val MAX_LENGTH = 254
    private const val MAX_LOCAL = 64
    private const val ATOM = """[\p{L}\p{N}!#${'$'}%&'*+/=?^_`{|}~-]+"""
    private const val LABEL = """[\p{L}\p{N}](?:[\p{L}\p{N}-]{0,61}[\p{L}\p{N}])?"""

    /** Letters, digits and the usual symbols in the part before the @, with single dots inside; a name of at least two parts after it. */
    private val ADDRESS = Regex("^$ATOM(?:\\.$ATOM)*@(?:$LABEL\\.)+$LABEL$")
    private val SEPARATORS = Regex("[,;\\n\\r]")
    private val SPACES = Regex("\\s+")
    private val NAMED = Regex("^(.*)<([^<>]*)>$")

    fun isValid(address: String): Boolean {
        if (address.length > MAX_LENGTH || !ADDRESS.matches(address)) return false
        if (address.substringBefore('@').length > MAX_LOCAL) return false
        val tld = address.substringAfterLast('.')
        return tld.length >= 2 && !tld.all { it.isDigit() } // 1.2.3.4-like names are not mail domains
    }

    fun parse(text: String): Result {
        val found = LinkedHashMap<String, Attendee>()
        for (segment in segments(text)) {
            val s = segment.trim()
            if (s.isEmpty()) continue
            val named = NAMED.matchEntire(s)
            val pieces = if (named != null) {
                val name = named.groupValues[1].trim().trim('"', '\'').trim().takeIf { it.isNotEmpty() }
                listOf(named.groupValues[2].trim() to name)
            } else {
                s.split(SPACES).filter { it.isNotEmpty() }.map { it to null }
            }
            for ((raw, name) in pieces) {
                val address = raw.removePrefix("mailto:").removePrefix("MAILTO:").trim()
                if (!isValid(address)) return Result.Invalid(raw)
                found.putIfAbsent(address.lowercase(), Attendee(address, displayName = name))
            }
        }
        return Result.Valid(found.values.toList())
    }

    /** The text cut at comma, semicolon and new line, except inside a quoted name or between < and >. */
    private fun segments(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var angle = false
        for (c in text) {
            when {
                c == '"' -> quoted = !quoted
                c == '<' && !quoted -> angle = true
                c == '>' && !quoted -> angle = false
            }
            if (!quoted && !angle && SEPARATORS.matches(c.toString())) {
                out += sb.toString()
                sb.clear()
            } else {
                sb.append(c)
            }
        }
        out += sb.toString()
        return out
    }

    /** [current] followed by the [added] ones that are not in it yet (the same address in other letter case is the same guest). */
    fun merge(current: List<Attendee>, added: List<Attendee>): List<Attendee> {
        val known = current.mapTo(HashSet()) { it.email.lowercase() }
        return current + added.filter { known.add(it.email.lowercase()) }
    }
}
