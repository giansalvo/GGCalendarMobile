package io.github.giansalvo.ggcalendar.core.notifications

import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.database.ReminderFiredEntity
import io.github.giansalvo.ggcalendar.core.database.ReminderSnoozedEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * At the first unlock moves into Room what [LightReminders] did while Room was not available: reminders shown or
 * rung (so they are not shown again) and snoozes. Idempotent: repeating it changes nothing.
 */
@Singleton
class JournalReconciler @Inject constructor(private val db: GGCalendarDatabase, private val store: DirectBootStore) {
    private val mutex = Mutex()

    suspend fun reconcile() = mutex.withLock {
        val journal = store.takeJournal()
        if (journal.isEmpty()) return@withLock
        val dao = db.reminderDao()
        val shown = journal.filterIsInstance<JournalEntry.Shown>()
        val shownKeys = shown.map { it.key }.toSet()
        shown.forEach { entry ->
            if (entry.key.startsWith(LightReminders.SNOOZE_PREFIX)) {
                // A snooze of Room (snooze|<id>) that rang; the ones made while locked (snooze|<time>) never reached Room.
                entry.key.removePrefix(LightReminders.SNOOZE_PREFIX).toLongOrNull()?.let { dao.deleteSnoozed(it) }
            } else {
                dao.insertFired(ReminderFiredEntity(entry.key, entry.fireAt))
            }
        }
        val existing = dao.snoozed()
        journal.filterIsInstance<JournalEntry.Snoozed>().filter { it.key !in shownKeys }.forEach { s ->
            val a = s.alert
            val already = existing.any { it.fireAt == s.fireAt && it.eventId == a.eventId && it.beginUtc == a.beginUtc && it.method == a.method.name && it.minutes == a.minutes }
            if (!already) {
                dao.insertSnoozed(ReminderSnoozedEntity(fireAt = s.fireAt, eventId = a.eventId, beginUtc = a.beginUtc, method = a.method.name, minutes = a.minutes))
            }
        }
    }
}
