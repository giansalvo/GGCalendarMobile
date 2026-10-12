package io.github.giansalvo.ggcalendar.core.model

/**
 * Writes rows as CSV (RFC 4180) that spreadsheets open: fields separated by [separator] (the semicolon of Italian
 * Excel), `CRLF` line ends, quotes doubled, and a byte order mark so that Excel reads the accents as UTF-8.
 */
object CsvWriter {
    fun write(rows: List<List<String>>, separator: Char = ';'): String = buildString {
        append('﻿')
        rows.forEach { row -> append(row.joinToString(separator.toString()) { field(it, separator) }).append("\r\n") }
    }

    private fun field(raw: String, separator: Char): String {
        val text = safe(raw)
        return if (text.any { it == separator || it == '"' || it == '\n' || it == '\r' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
    }

    /** A cell that starts like a formula would be run by the spreadsheet: an apostrophe in front keeps it as text. */
    private fun safe(text: String): String = if (text.isNotEmpty() && text[0] in "=+-@\t\r") "'$text" else text
}
