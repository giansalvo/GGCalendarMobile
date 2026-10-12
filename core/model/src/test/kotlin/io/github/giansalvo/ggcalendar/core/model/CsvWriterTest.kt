package io.github.giansalvo.ggcalendar.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvWriterTest {
    private fun body(rows: List<List<String>>) = CsvWriter.write(rows).removePrefix("﻿")

    @Test
    fun startsWithTheByteOrderMarkAndEndsLinesWithCrLf() {
        val csv = CsvWriter.write(listOf(listOf("a", "b"), listOf("c", "d")))
        assertTrue(csv.startsWith("﻿"))
        assertEquals("a;b\r\nc;d\r\n", csv.removePrefix("﻿"))
    }

    @Test
    fun fieldsWithSeparatorsQuotesOrLineBreaksAreQuoted() {
        assertEquals("\"a;b\";\"say \"\"hi\"\"\";\"one\ntwo\"\r\n", body(listOf(listOf("a;b", "say \"hi\"", "one\ntwo"))))
    }

    @Test
    fun aCommaIsNotASeparatorHere() {
        assertEquals("a,b;c\r\n", body(listOf(listOf("a,b", "c"))))
    }

    @Test
    fun accentsAndEmptyFieldsSurvive() {
        assertEquals("è;;ü\r\n", body(listOf(listOf("è", "", "ü"))))
    }

    @Test
    fun textThatLooksLikeAFormulaIsKeptAsText() {
        assertEquals("'=SUM(A1);'-1;'@x;'+1\r\n", body(listOf(listOf("=SUM(A1)", "-1", "@x", "+1"))))
    }
}
