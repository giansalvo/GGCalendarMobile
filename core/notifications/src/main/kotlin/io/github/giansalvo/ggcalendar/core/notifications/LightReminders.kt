package io.github.giansalvo.ggcalendar.core.notifications

import android.content.Context
import android.os.UserManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.giansalvo.ggcalendar.core.data.CalendarRepository

/**
 * The reminders in direct boot, from the reboot to the first unlock: no Hilt, no Room, no DataStore, only the
 * [DirectBootStore] snapshot and AlarmManager, notifications and the alarm service. What is done here (a reminder shown,
 * a snooze) goes in the journal, which [JournalReconciler] moves into Room at the unlock.
 * After the unlock the full layer ([ReminderEngine]) takes over.
 */
class LightReminders(private val context: Context, private val store: DirectBootStore = DirectBootStore(context)) {
    private val registrar = AlarmRegistrar(context, store)
    private val notifier = ReminderNotifier(context)

    /**
     * After a reboot, an update or a change of time: registers the planned reminders again and shows the ones that
     * went off while the phone was off (the last 24 hours, event not over), as the full layer does.
     */
    fun restore(now: Long = System.currentTimeMillis()) {
        notifier.createChannels()
        val wanted = LinkedHashMap<String, ReminderSlot>()
        for (entry in pending()) {
            if (entry.fireAt > now) wanted[entry.key] = ReminderSlot(entry.fireAt, entry.alert.isAlarm)
            else if (entry.fireAt > now - CATCH_UP && entry.alert.endUtc > now) show(entry)
        }
        registrar.sync(wanted)
    }

    /** The AlarmManager alarm [key] went off. */
    fun onAlarm(key: String, now: Long = System.currentTimeMillis()) {
        notifier.createChannels()
        val entry = pending().firstOrNull { it.key == key } ?: return
        if (entry.alert.endUtc <= now) return
        show(entry)
    }

    /** "Snooze" while the phone is locked: the reminder comes back in [minutes], also after another reboot. */
    fun snooze(alert: ReminderAlert, minutes: Int, now: Long = System.currentTimeMillis()) {
        val fireAt = now + minutes * 60_000L
        val key = SNOOZE_PREFIX + fireAt
        store.appendJournal(JournalEntry.Snoozed(key, fireAt, alert))
        val snapshot = store.readSnapshot() ?: Snapshot(now, emptyList())
        store.writeSnapshot(snapshot.copy(entries = snapshot.entries + SnapshotEntry(key, fireAt, alert)))
        registrar.add(key, ReminderSlot(fireAt, alert.isAlarm))
        dismissAlert(context, alert)
    }

    /** Entries not shown yet: the snapshot minus what the journal says was shown since. */
    private fun pending(): List<SnapshotEntry> {
        val shown = store.readJournal().filterIsInstance<JournalEntry.Shown>().map { it.key }.toSet()
        return (store.readSnapshot()?.entries ?: emptyList()).filter { it.key !in shown }
    }

    private fun show(entry: SnapshotEntry) {
        store.appendJournal(JournalEntry.Shown(entry.key, entry.fireAt))
        presentAlert(context, notifier, entry.alert)
    }

    companion object {
        const val SNOOZE_PREFIX = "snooze|"
        private const val CATCH_UP = 24L * 60 * 60 * 1000
    }
}

/** Shows a reminder: an alarm rings through the service, anything else is a notification. */
internal fun presentAlert(context: Context, notifier: ReminderNotifier, alert: ReminderAlert) {
    if (alert.isAlarm) {
        if (!AlarmService.start(context, alert)) notifier.showPopup(alert)
    } else {
        notifier.showPopup(alert)
    }
}

/** The user closed the reminder: its notification goes away and, for an alarm, the ringing stops. */
internal fun dismissAlert(context: Context, alert: ReminderAlert) {
    ReminderNotifier(context).cancel(alert)
    if (alert.isAlarm) AlarmService.stop(alert.key)
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderEntryPoint {
    fun engine(): ReminderEngine
    fun scheduler(): ReminderScheduler
    fun notifier(): ReminderNotifier
    fun repository(): CalendarRepository
}

/**
 * What the buttons of the notifications and of the alarm screen do, whether the phone is locked or not: before the
 * first unlock [LightReminders] (journal), after it the full [ReminderEngine] (Room).
 */
object ReminderActions {
    fun isUnlocked(context: Context): Boolean = context.getSystemService(UserManager::class.java).isUserUnlocked

    fun entryPoint(context: Context): ReminderEntryPoint =
        EntryPointAccessors.fromApplication(context.applicationContext, ReminderEntryPoint::class.java)

    suspend fun snooze(context: Context, alert: ReminderAlert, minutes: Int) {
        if (isUnlocked(context)) entryPoint(context).engine().snooze(alert, minutes) else LightReminders(context).snooze(alert, minutes)
    }

    fun dismiss(context: Context, alert: ReminderAlert) = dismissAlert(context, alert)
}
