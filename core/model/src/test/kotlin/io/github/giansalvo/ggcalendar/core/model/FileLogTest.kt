package io.github.giansalvo.ggcalendar.core.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.concurrent.thread

class FileLogTest {
    private val dir: File = Files.createTempDirectory("ggcal-log").toFile()
    private val instant = 1_792_750_530_123L // 2026-10-23 10:15:30.123 UTC

    private fun log(max: Long = FileLog.MAX_BYTES, zone: ZoneId = ZoneOffset.UTC) = FileLog(dir, max, { instant }, zone)

    @After
    fun cleanUp() {
        dir.deleteRecursively()
        Diag.file = null
        Diag.sink = null
        Diag.foreground = false
        Diag.screen = "calendar"
    }

    @Test
    fun `a line carries date and time, thread, window and text`() {
        val log = log()
        log.write("settings", "main", "hello")
        assertEquals("2026-10-23 10:15:30.123 [main] [settings] hello\n", log.text())
    }

    @Test
    fun `the time is written in the zone of the phone`() {
        val log = log(zone = ZoneId.of("Europe/Rome"))
        log.write("calendar", "main", "x")
        assertTrue(log.text().startsWith("2026-10-23 12:15:30.123"))
    }

    @Test
    fun `lines stay in the order they were written`() {
        val log = log()
        repeat(50) { log.write("calendar", "main", "line $it") }
        val lines = log.text().trim().lines()
        assertEquals(50, lines.size)
        assertEquals((0 until 50).map { "line $it" }, lines.map { it.split("] ", limit = 3)[2] })
    }

    @Test
    fun `a new writer finds the lines of the one before it`() {
        log().apply { write("calendar", "main", "first"); flush() }
        val again = log()
        again.write("calendar", "main", "second")
        assertEquals(listOf("first", "second"), again.text().trim().lines().map { it.split("] ", limit = 3)[2] })
    }

    @Test
    fun `a text with line breaks stays on one line`() {
        val log = log()
        log.write("calendar", "main", "a\nb\r\nc\rd")
        val lines = log.text().trimEnd().lines()
        assertEquals(1, lines.size)
        assertTrue(lines[0].endsWith("a\\nb\\nc\\nd"))
    }

    @Test
    fun `a whole email address never reaches the file`() {
        val log = log()
        log.write("calendar", "main", "calendar me@x.com|other@y.org name='prof.gusinu+tag@gmail.com' key=a@b.it|123")
        val t = log.text()
        assertFalse(t, "@x.com|" in t && "me@x.com" in t)
        assertTrue(t, "me*@x.com|oth*@y.org name='pro*@gmail.com' key=a*@b.it|123" in t)
    }

    @Test
    fun `an address with a short name keeps its domain and not the at sign twice`() {
        assertEquals("a*@b.it", Diag.who("a@b.it"))
        assertEquals("pro*@gmail.com", Diag.who("profgusinu@gmail.com"))
        assertEquals("-", Diag.who(null))
    }

    @Test
    fun `text without an address is not changed`() {
        assertEquals("Meeting at 5 - bring the laptop", FileLog.redact("Meeting at 5 - bring the laptop"))
    }

    @Test
    fun `titles with emoji and quotes are kept`() {
        val log = log()
        log.write("calendar", "main", "title='Cena 🍕 \"da Mario\"' é à")
        assertTrue(log.text().contains("Cena 🍕 \"da Mario\"' é à"))
    }

    @Test
    fun `when the file is full it becomes the previous one and the oldest lines go`() {
        val log = log(max = 300)
        repeat(30) { log.write("calendar", "main", "line %02d xxxxxxxxxx".format(it)) }
        log.flush()
        assertTrue(File(dir, FileLog.CURRENT).length() <= 300)
        assertTrue(File(dir, FileLog.PREVIOUS).exists())
        assertTrue(File(dir, FileLog.PREVIOUS).length() <= 300)
        val text = log.text()
        assertTrue("the newest line is kept", text.contains("line 29"))
        assertFalse("the oldest lines are dropped", text.contains("line 00"))
        // what is kept is in order, without a gap in the middle
        val numbers = text.trim().lines().map { it.substringAfter("line ").take(2).toInt() }
        assertEquals(numbers, numbers.sorted())
        assertEquals(numbers.size, numbers.last() - numbers.first() + 1)
    }

    @Test
    fun `the whole log stays under twice the limit however long it is`() {
        val log = log(max = 1_000)
        repeat(2_000) { log.write("calendar", "main", "a line of some length $it") }
        log.flush()
        val total = dir.listFiles()!!.sumOf { it.length() }
        assertTrue("total $total", total <= 2_000)
        assertEquals(2, dir.listFiles()!!.size)
    }

    @Test
    fun `clear deletes both files`() {
        val log = log(max = 200)
        repeat(20) { log.write("calendar", "main", "line $it xxxxxxxxxxxx") }
        log.flush()
        assertTrue(File(dir, FileLog.PREVIOUS).exists())
        log.clear()
        assertTrue(log.isEmpty())
        assertEquals("", log.text())
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun `lines written after a clear start a new log`() {
        val log = log()
        log.write("calendar", "main", "old")
        log.clear()
        log.write("calendar", "main", "new")
        assertEquals(listOf("new"), log.text().trim().lines().map { it.split("] ", limit = 3)[2] })
    }

    @Test
    fun `several threads write without losing or splitting a line`() {
        val log = log()
        val workers = (0 until 8).map { t -> thread { repeat(200) { log.write("background", "worker-$t", "t$t line $it") } } }
        workers.forEach { it.join() }
        val lines = log.text().trim().lines()
        assertEquals(1_600, lines.size)
        assertTrue(lines.all { Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \[worker-\d] \[background] t\d line \d+$""").matches(it) })
        // each thread's own lines are in order
        for (t in 0 until 8) {
            val own = lines.filter { it.contains("[worker-$t]") }.map { it.substringAfter("line ").toInt() }
            assertEquals((0 until 200).toList(), own)
        }
    }

    @Test
    fun `a folder that cannot be written does not throw`() {
        val blocked = File(dir, "file")
        blocked.writeText("not a folder")
        val log = FileLog(File(blocked, "inside"), FileLog.MAX_BYTES, { instant }, ZoneOffset.UTC)
        log.write("calendar", "main", "x") // must not throw
        log.flush()
        assertEquals("", log.text())
    }

    @Test
    fun `copyTo puts the previous file first and the current one after`() {
        val log = log(max = 250)
        repeat(12) { log.write("calendar", "main", "line %02d yyyyyyyyyyy".format(it)) }
        val out = File(dir, "out/mail.txt")
        log.copyTo(out)
        assertEquals(log.text(), out.readText())
        val numbers = out.readText().trim().lines().map { it.substringAfter("line ").take(2).toInt() }
        assertEquals(numbers.sorted(), numbers)
    }

    @Test
    fun `Diag writes to the file only while the file is set`() {
        val log = log()
        Diag.log { "not written" }
        Diag.file = log
        Diag.log { "written" }
        Diag.file = null
        Diag.log { "not written either" }
        assertEquals(listOf("written"), log.text().trim().lines().map { it.split("] ", limit = 3)[2] })
    }

    @Test
    fun `Diag does not build the message when nothing listens`() {
        var built = false
        Diag.log { built = true; "x" }
        assertFalse(built)
    }

    @Test
    fun `the window is the screen in front, or background`() {
        val log = log()
        Diag.file = log
        Diag.screen = "settings"
        Diag.foreground = true
        Diag.log { "a" }
        Diag.foreground = false
        Diag.log { "b" }
        val windows = log.text().trim().lines().map { it.substringAfter("[").substringAfter("[").substringBefore("]") }
        assertEquals(listOf("settings", "background"), windows)
    }

    @Test
    fun `the name of the thread is the one of the caller`() {
        val log = log()
        Diag.file = log
        thread(name = "sync-thread") { Diag.log { "from a thread" } }.join()
        assertTrue(log.text().contains("[sync-thread]"))
    }
}
