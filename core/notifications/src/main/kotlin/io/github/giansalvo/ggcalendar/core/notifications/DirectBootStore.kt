package io.github.giansalvo.ggcalendar.core.notifications

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.giansalvo.ggcalendar.core.data.AppPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

/** A reminder waiting to go off, as the direct boot layer needs it: [key] identifies the AlarmManager alarm. */
data class SnapshotEntry(val key: String, val fireAt: Long, val alert: ReminderAlert) {
    fun toJson(): JSONObject = JSONObject().put("key", key).put("fireAt", fireAt).put("alert", alert.toJson())

    companion object {
        fun from(json: JSONObject) = ReminderAlert.fromJson(json.getJSONObject("alert"))?.let { SnapshotEntry(json.getString("key"), json.getLong("fireAt"), it) }
    }
}

/** The reminders of the next 48 hours (snoozes included), a copy of what Room holds, readable before the first unlock. */
data class Snapshot(val writtenAt: Long, val entries: List<SnapshotEntry>) {
    fun toJson(): JSONObject = JSONObject().put("writtenAt", writtenAt).put("entries", JSONArray(entries.map { it.toJson() }))

    companion object {
        fun from(json: JSONObject): Snapshot {
            val array = json.optJSONArray("entries") ?: JSONArray()
            return Snapshot(json.optLong("writtenAt"), (0 until array.length()).mapNotNull { SnapshotEntry.from(array.getJSONObject(it)) })
        }
    }
}

/** What happened while Room was not available (the phone locked after a reboot); it is moved into Room at the first unlock. */
sealed interface JournalEntry {
    /** The reminder [key] (the key of a [SnapshotEntry]) was shown or rung, and was planned for [fireAt]. */
    data class Shown(val key: String, val fireAt: Long) : JournalEntry

    /** An alarm or notification was snoozed: [alert] comes back at [fireAt], registered under [key]. */
    data class Snoozed(val key: String, val fireAt: Long, val alert: ReminderAlert) : JournalEntry

    fun toJson(): JSONObject = when (this) {
        is Shown -> JSONObject().put("t", "shown").put("key", key).put("fireAt", fireAt)
        is Snoozed -> JSONObject().put("t", "snoozed").put("key", key).put("fireAt", fireAt).put("alert", alert.toJson())
    }

    companion object {
        fun from(json: JSONObject): JournalEntry? = when (json.optString("t")) {
            "shown" -> Shown(json.getString("key"), json.getLong("fireAt"))
            "snoozed" -> ReminderAlert.fromJson(json.getJSONObject("alert"))?.let { Snoozed(json.getString("key"), json.getLong("fireAt"), it) }
            else -> null
        }
    }
}

/**
 * Files in the device protected storage, the only storage Android opens before the first unlock after a reboot: the
 * [Snapshot] of the reminders, the [JournalEntry] list of what happened meanwhile, the keys registered with AlarmManager
 * and the alarm duration. Room stays the source of truth: the app writes the snapshot every time it registers the
 * alarms and reads the journal back at the unlock. Plain Android and JSON, no Hilt and no Room, so a receiver, the
 * alarm service and the alarm screen can use it while the phone is still locked.
 */
@Singleton
class DirectBootStore internal constructor(private val dir: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(deviceProtectedDir(context))

    private val snapshotFile get() = File(dir, "snapshot.json")
    private val journalFile get() = File(dir, "journal.jsonl")
    private val keysFile get() = File(dir, "scheduled.json")
    private val minutesFile get() = File(dir, "alarm_minutes")

    /** Atomic: a crash while writing leaves the previous snapshot in place (temporary file, then rename). */
    fun writeSnapshot(snapshot: Snapshot) = synchronized(LOCK) { writeAtomic(snapshotFile, snapshot.toJson().toString()) }

    fun readSnapshot(): Snapshot? = synchronized(LOCK) {
        runCatching { Snapshot.from(JSONObject(snapshotFile.readText())) }.getOrNull()
    }

    fun appendJournal(entry: JournalEntry) = synchronized(LOCK) {
        dir.mkdirs()
        journalFile.appendText(entry.toJson().toString() + "\n")
    }

    fun readJournal(): List<JournalEntry> = synchronized(LOCK) { parseJournal(journalFile) }

    /** The journal, emptied: what is read here is the caller's to apply; anything written meanwhile starts a new journal. */
    fun takeJournal(): List<JournalEntry> = synchronized(LOCK) {
        val taking = File(dir, "journal.taking")
        // A leftover from a crash while applying is read again: applying is idempotent.
        if (journalFile.exists()) {
            if (taking.exists()) taking.appendText(journalFile.readText()) else Files.move(journalFile.toPath(), taking.toPath())
            journalFile.delete()
        }
        val entries = parseJournal(taking)
        taking.delete()
        entries
    }

    /** Keys of the alarms registered with AlarmManager, to cancel the ones no longer wanted. */
    fun scheduledKeys(): Set<String> = synchronized(LOCK) {
        runCatching { JSONArray(keysFile.readText()).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrDefault(emptySet())
    }

    fun setScheduledKeys(keys: Set<String>) = synchronized(LOCK) { writeAtomic(keysFile, JSONArray(keys.toList()).toString()) }

    fun alarmMinutes(): Int = synchronized(LOCK) {
        runCatching { minutesFile.readText().trim().toInt() }.getOrDefault(AppPreferences.DEFAULT_ALARM_MINUTES).coerceIn(1, 10)
    }

    fun setAlarmMinutes(minutes: Int) = synchronized(LOCK) { writeAtomic(minutesFile, minutes.coerceIn(1, 10).toString()) }

    private fun parseJournal(file: File): List<JournalEntry> =
        runCatching { file.readLines() }.getOrDefault(emptyList()).mapNotNull { line ->
            runCatching { JournalEntry.from(JSONObject(line)) }.getOrNull()
        }

    private fun writeAtomic(target: File, text: String) {
        dir.mkdirs()
        val temp = File(dir, target.name + ".tmp")
        temp.writeText(text)
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        val LOCK = Any()

        fun deviceProtectedDir(context: Context): File =
            File(context.createDeviceProtectedStorageContext().filesDir, "reminders")
    }
}
