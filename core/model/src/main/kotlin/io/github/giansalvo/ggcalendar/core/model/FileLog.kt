package io.github.giansalvo.ggcalendar.core.model

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * The diagnostic log file: one line per call, `date time [thread] [window] text`, appended by a single background thread (the
 * caller never waits for the disk). Two files of at most [maxBytes] each, the current one and the previous one: when the current
 * one is full it becomes the previous one and the old previous one is dropped, so the log stays small however long it is kept on.
 * Plain Java on purpose: the writer must work in every process, also before the first unlock, and be testable without Android.
 */
class FileLog(
    private val dir: File,
    private val maxBytes: Long = MAX_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(zone)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "GGCalLog").apply { isDaemon = true } }
    private val current get() = File(dir, CURRENT)
    private val previous get() = File(dir, PREVIOUS)

    /** Adds a line. [window] and [thread] are those of the caller, taken now: the line is written later. */
    fun write(window: String, thread: String, text: String) {
        val line = format.format(Instant.ofEpochMilli(now())) + " [" + thread + "] [" + window + "] " + oneLine(redact(text)) + "\n"
        worker.execute { runCatching { append(line) } }
    }

    private fun append(line: String) {
        dir.mkdirs()
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (current.length() + bytes.size > maxBytes && current.length() > 0) {
            previous.delete()
            current.renameTo(previous)
        }
        current.appendBytes(bytes)
    }

    /** Waits until every line given so far is on the disk. */
    fun flush() {
        runCatching { worker.submit {}.get() }
    }

    /** The whole log, the previous file first, as one text. */
    fun text(): String {
        flush()
        return (if (previous.exists()) previous.readText(Charsets.UTF_8) else "") + (if (current.exists()) current.readText(Charsets.UTF_8) else "")
    }

    /** Copies the whole log into [out] (the file to attach to the mail). */
    fun copyTo(out: File) {
        flush()
        out.parentFile?.mkdirs()
        out.outputStream().use { o ->
            if (previous.exists()) previous.inputStream().use { it.copyTo(o) }
            if (current.exists()) current.inputStream().use { it.copyTo(o) }
        }
    }

    /** Deletes both files (the option was turned off). */
    fun clear() {
        flush()
        previous.delete()
        current.delete()
    }

    fun isEmpty(): Boolean {
        flush()
        return !current.exists() && !previous.exists()
    }

    private fun oneLine(text: String) = text.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\\n")

    companion object {
        private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+[.][A-Za-z]{2,}")

        /** No whole address reaches the file, wherever it comes from (a calendar id, a name, a title): `pro*@gmail.com`, as [Diag.who] writes it. */
        fun redact(text: String): String = EMAIL.replace(text) { Diag.who(it.value) }

        const val MAX_BYTES = 1_000_000L
        const val CURRENT = "ggcalendar-log.txt"
        const val PREVIOUS = "ggcalendar-log.1.txt"
    }
}
