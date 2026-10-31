package io.github.giansalvo.ggcalendar.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.giansalvo.ggcalendar.core.model.AttachmentLink
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY sort_order, added_at")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY sort_order, added_at")
    suspend fun getAll(): List<AccountEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE email = :email")
    suspend fun delete(email: String)
}

@Dao
interface CalendarDao {
    @Query("SELECT * FROM calendars ORDER BY is_primary DESC, sort_order, name COLLATE NOCASE")
    fun observeAll(): Flow<List<CalendarEntity>>

    @Query("SELECT * FROM calendars WHERE account_email = :email")
    suspend fun getForAccount(email: String): List<CalendarEntity>

    @Query("SELECT * FROM calendars WHERE id = :id")
    suspend fun get(id: String): CalendarEntity?

    @Upsert
    suspend fun upsert(calendar: CalendarEntity)

    @Query("UPDATE calendars SET is_visible = :visible WHERE id = :id")
    suspend fun setVisible(id: String, visible: Boolean)

    @Query("UPDATE calendars SET sync_token = :token, last_synced_at = :at WHERE id = :id")
    suspend fun setSyncToken(id: String, token: String?, at: Long = System.currentTimeMillis())

    @Query("DELETE FROM calendars WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE calendars SET name = :name, color = :color WHERE id = :id")
    suspend fun setNameAndColor(id: String, name: String, color: String)

    @Query("SELECT COALESCE(MAX(sort_order), 0) FROM calendars WHERE kind = :kind")
    suspend fun maxSortOrder(kind: String): Int

    @Query("SELECT * FROM calendars WHERE kind = :kind ORDER BY sort_order")
    suspend fun getByKind(kind: String): List<CalendarEntity>

    /** A subscription after a download that worked: the copy it holds now, when it was taken and when it was tried. */
    @Query("UPDATE calendars SET source_hash = :hash, source_etag = :etag, last_fetched_at = :fetchedAt, last_synced_at = :attemptAt, last_error = NULL WHERE id = :id")
    suspend fun setSourceFetched(id: String, hash: String?, etag: String?, fetchedAt: Long, attemptAt: Long)

    /** A subscription after an attempt that failed: its events stay, only the time of the attempt and the reason change. */
    @Query("UPDATE calendars SET last_synced_at = :attemptAt, last_error = :error WHERE id = :id")
    suspend fun setSourceFailed(id: String, attemptAt: Long, error: String)

    /** The days of a subscription: the events outside them are not kept (see SubscriptionRange). */
    @Query("UPDATE calendars SET range_from = :from, range_to = :to WHERE id = :id")
    suspend fun setRange(id: String, from: Long?, to: Long?)
}

@Dao
abstract class EventDao {
    @Query("SELECT * FROM events WHERE id = :id")
    abstract suspend fun get(id: String): EventEntity?

    @Query("SELECT * FROM events WHERE id = :id")
    abstract fun observe(id: String): Flow<EventEntity?>

    @Query("SELECT * FROM events WHERE calendar_id = :calendarId")
    abstract suspend fun getForCalendar(calendarId: String): List<EventEntity>

    /** Local id of each event of a calendar that exists on Google, keyed by remote id. */
    @Query("SELECT remote_id AS remoteId, id FROM events WHERE calendar_id = :calendarId AND remote_id IS NOT NULL")
    abstract suspend fun remoteIds(calendarId: String): List<RemoteLink>

    /** Events that own their instances: single events and series masters (not the occurrences of a series). */
    @Query("SELECT id FROM events WHERE series_master_id IS NULL")
    abstract suspend fun masterIds(): List<String>

    @Query("SELECT id FROM events WHERE series_master_id IS NULL AND rrule IS NOT NULL")
    abstract suspend fun seriesIds(): List<String>

    @Query("SELECT * FROM events WHERE series_master_id = :masterId")
    abstract suspend fun getExceptions(masterId: String): List<EventEntity>

    /**
     * The events to export from [calendarIds] for the days [fromDay, toDay): single events and series (not their
     * occurrences) that have at least one occurrence in the days. Hidden calendars count.
     */
    @Query(
        """
        SELECT DISTINCT COALESCE(e.series_master_id, e.id) FROM instances i
        JOIN events e ON e.id = i.event_id
        WHERE e.calendar_id IN (:calendarIds) AND i.start_day < :toDay AND i.end_day > :fromDay
        """,
    )
    abstract suspend fun exportRootIds(calendarIds: List<String>, fromDay: Long, toDay: Long): List<String>

    /** Birthday events that come from Google (the contacts of an account), not from the phone's contacts. */
    @Query("SELECT * FROM events WHERE event_type = 'birthday' AND calendar_id != :exceptCalendarId")
    abstract suspend fun birthdaysOutside(exceptCalendarId: String): List<EventEntity>

    @Query("SELECT * FROM events WHERE id IN (:ids)")
    abstract suspend fun getAll(ids: List<String>): List<EventEntity>

    @Upsert
    protected abstract suspend fun upsertRows(events: List<EventEntity>)

    /** Writes the events, keeping the search text of their attachments in step with the list. */
    @Transaction
    open suspend fun upsert(events: List<EventEntity>) =
        upsertRows(events.map { it.copy(attachmentText = AttachmentLink.searchText(it.attachments)) })

    /** The attachments read from Google for an event whose own change is still waiting to be sent (see SyncEngine): only if they were not read yet. */
    @Query("UPDATE events SET attachments = :json, attachment_text = :text WHERE id = :id AND attachments IS NULL")
    abstract suspend fun setAttachmentsIfUnread(id: String, json: String, text: String?)

    @Query("UPDATE events SET remote_id = :remoteId, etag = :etag WHERE id = :id")
    abstract suspend fun setRemote(id: String, remoteId: String, etag: String?)

    /** Attendees of every event, to suggest addresses in the editor. */
    @Query("SELECT attendees FROM events WHERE attendees != '[]'")
    abstract suspend fun allAttendees(): List<String>

    /** Deletes an event together with the modified or cancelled occurrences of its series. */
    @Query("DELETE FROM events WHERE id IN (:ids) OR series_master_id IN (:ids)")
    abstract suspend fun deleteWithSeries(ids: List<String>)
}

data class RemoteLink(val remoteId: String, val id: String)

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(item: OutboxEntity): Long

    @Query("SELECT * FROM outbox ORDER BY seq")
    suspend fun getAll(): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE event_id = :eventId ORDER BY seq")
    suspend fun getFor(eventId: String): List<OutboxEntity>

    @Query("SELECT DISTINCT event_id FROM outbox")
    suspend fun pendingEventIds(): List<String>

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM outbox WHERE event_id = :eventId AND seq > :seq)")
    suspend fun hasPendingAfter(eventId: String, seq: Long): Boolean

    @Query("DELETE FROM outbox WHERE seq = :seq")
    suspend fun complete(seq: Long)

    @Query("DELETE FROM outbox WHERE event_id = :eventId")
    suspend fun discardFor(eventId: String)

    /** Drops what was queued for the calendars of an account that is being removed ([likePrefix] is `email|` escaped with `!`). */
    @Query("DELETE FROM outbox WHERE calendar_id LIKE :likePrefix || '%' ESCAPE '!'")
    suspend fun discardForAccount(likePrefix: String)

    /** Drops what was queued for a calendar that is deleted, and the moves that were going to put an event in it. */
    @Query("DELETE FROM outbox WHERE calendar_id = :calendarId OR (operation = 'move' AND payload = :calendarId)")
    suspend fun discardForCalendar(calendarId: String)

    @Query("UPDATE outbox SET attempts = attempts + 1, last_error = :error WHERE seq = :seq")
    suspend fun failed(seq: Long, error: String?)
}

private const val REMINDER_ROW = """
    SELECT i.event_id AS eventId, i.begin_utc AS beginUtc, i.end_utc AS endUtc, i.all_day AS allDay, e.title AS title,
           e.location AS location, e.description AS description, e.meet_link AS meetLink, e.ical_uid AS iCalUid, e.reminders AS reminders,
           e.has_alarm AS hasAlarm, e.organizer_email AS organizerEmail, e.attendees AS attendees,
           c.default_reminders AS defaultReminders, c.account_email AS accountEmail, c.remote_id AS calendarRemoteId,
           c.id AS calendarId, c.name AS calendarName, c.is_visible AS calendarVisible
    FROM instances i
    JOIN events e ON e.id = i.event_id
    JOIN calendars c ON c.id = e.calendar_id
"""

@Dao
interface ReminderDao {
    /** Occurrences of the visible calendars (a hidden one neither rings nor wakes), not ended by [endAfter] and starting by [beginUntil]. */
    @Query("$REMINDER_ROW WHERE c.is_visible = 1 AND i.end_utc > :endAfter AND i.begin_utc <= :beginUntil")
    suspend fun rows(endAfter: Long, beginUntil: Long): List<ReminderRow>

    /** Occurrences of one calendar, visible or not, not ended by [endAfter] and starting by [beginUntil]. */
    @Query("$REMINDER_ROW WHERE c.id = :calendarId AND i.end_utc > :endAfter AND i.begin_utc <= :beginUntil")
    suspend fun rowsOfCalendar(calendarId: String, endAfter: Long, beginUntil: Long): List<ReminderRow>

    /** An occurrence of any calendar: for a snooze, which the user asked for by pressing "Snooze" even if the calendar is hidden now. */
    @Query("$REMINDER_ROW WHERE i.event_id = :eventId AND i.begin_utc = :beginUtc")
    suspend fun row(eventId: String, beginUtc: Long): ReminderRow?

    /** An occurrence of a visible calendar: for a planned reminder that goes off. */
    @Query("$REMINDER_ROW WHERE c.is_visible = 1 AND i.event_id = :eventId AND i.begin_utc = :beginUtc")
    suspend fun visibleRow(eventId: String, beginUtc: Long): ReminderRow?

    @Query("SELECT `key` FROM reminder_fired WHERE `key` IN (:keys)")
    suspend fun fired(keys: List<String>): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFired(fired: ReminderFiredEntity)

    @Query("DELETE FROM reminder_fired WHERE fire_at < :before")
    suspend fun deleteFiredBefore(before: Long)

    @Insert
    suspend fun insertSnoozed(snoozed: ReminderSnoozedEntity)

    @Query("SELECT * FROM reminder_snoozed ORDER BY fire_at")
    suspend fun snoozed(): List<ReminderSnoozedEntity>

    @Query("DELETE FROM reminder_snoozed WHERE id = :id")
    suspend fun deleteSnoozed(id: Long)

    /** The snoozes of the events of a calendar: they go when the calendar is hidden. */
    @Query("DELETE FROM reminder_snoozed WHERE event_id IN (SELECT id FROM events WHERE calendar_id = :calendarId)")
    suspend fun deleteSnoozedOfCalendar(calendarId: String)
}

@Dao
interface InstanceDao {
    @Query("DELETE FROM instances WHERE event_id = :eventId")
    suspend fun deleteForEvent(eventId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(instances: List<InstanceEntity>)

    /** Visible instances overlapping the days [fromDay, toDay) (epoch days). */
    @Query(
        """
        SELECT i.event_id AS eventId, e.calendar_id AS calendarId, e.title AS title, e.location AS location,
               COALESCE(e.color, c.color) AS colorHex, i.all_day AS allDay, i.begin_utc AS beginUtc, i.end_utc AS endUtc,
               i.start_day AS startDay, i.end_day AS endDay,
               (e.meet_link IS NOT NULL OR e.add_meet) AS hasMeet, e.has_alarm AS hasAlarm,
               (e.rrule IS NOT NULL OR e.series_master_id IS NOT NULL) AS isRecurring,
               (e.event_type = 'birthday') AS isBirthday,
               (c.access_role IN ('owner', 'writer') AND e.event_type = 'default' AND (e.organizer_email IS NULL
                   OR e.organizer_email IN (c.remote_id, c.account_email) OR e.guests_can_modify)) AS canWrite,
               NULL AS attachmentsJson, 1 AS inOther
        FROM instances i
        JOIN events e ON e.id = i.event_id
        JOIN calendars c ON c.id = e.calendar_id
        WHERE c.is_visible = 1 AND i.start_day < :toDay AND i.end_day > :fromDay
        ORDER BY i.start_day, i.all_day DESC, i.begin_utc, e.title
        """,
    )
    fun observeRange(fromDay: Long, toDay: Long): Flow<List<InstanceRow>>

    /** Every occurrence of the events of [calendarIds] in the days [fromDay, toDay), in time order, to export as a table. */
    @Query(
        """
        SELECT i.* FROM instances i JOIN events e ON e.id = i.event_id
        WHERE e.calendar_id IN (:calendarIds) AND i.start_day < :toDay AND i.end_day > :fromDay
        ORDER BY i.begin_utc, i.event_id
        """,
    )
    suspend fun exportInstances(calendarIds: List<String>, fromDay: Long, toDay: Long): List<InstanceEntity>

    /**
     * Occurrences of the visible calendars whose event matches [pattern] (a LIKE pattern, `!` escapes) in title, place,
     * description or guests (names and emails), soonest first; only the days the instances table covers. [attachmentPattern] is the same
     * words without accents or capitals, for the title and file name of the attachments (never their addresses).
     */
    @Query(
        """
        SELECT i.event_id AS eventId, e.calendar_id AS calendarId, e.title AS title, e.location AS location,
               COALESCE(e.color, c.color) AS colorHex, i.all_day AS allDay, i.begin_utc AS beginUtc, i.end_utc AS endUtc,
               i.start_day AS startDay, i.end_day AS endDay,
               (e.meet_link IS NOT NULL OR e.add_meet) AS hasMeet, e.has_alarm AS hasAlarm,
               (e.rrule IS NOT NULL OR e.series_master_id IS NOT NULL) AS isRecurring,
               (e.event_type = 'birthday') AS isBirthday,
               (c.access_role IN ('owner', 'writer') AND e.event_type = 'default' AND (e.organizer_email IS NULL
                   OR e.organizer_email IN (c.remote_id, c.account_email) OR e.guests_can_modify)) AS canWrite,
               e.attachments AS attachmentsJson,
               (e.title LIKE :pattern ESCAPE '!' OR e.location LIKE :pattern ESCAPE '!'
                   OR e.description LIKE :pattern ESCAPE '!' OR e.attendees LIKE :pattern ESCAPE '!') AS inOther
        FROM instances i
        JOIN events e ON e.id = i.event_id
        JOIN calendars c ON c.id = e.calendar_id
        WHERE c.is_visible = 1 AND e.is_cancelled = 0
          AND (e.title LIKE :pattern ESCAPE '!' OR e.location LIKE :pattern ESCAPE '!'
               OR e.description LIKE :pattern ESCAPE '!' OR e.attendees LIKE :pattern ESCAPE '!'
               OR e.attachment_text LIKE :attachmentPattern ESCAPE '!')
        ORDER BY i.begin_utc, e.title
        LIMIT :limit
        """,
    )
    suspend fun search(pattern: String, attachmentPattern: String, limit: Int): List<InstanceRow>

    /** One occurrence, also of a hidden calendar (a reminder opens it). */
    @Query(
        """
        SELECT i.event_id AS eventId, e.calendar_id AS calendarId, e.title AS title, e.location AS location,
               COALESCE(e.color, c.color) AS colorHex, i.all_day AS allDay, i.begin_utc AS beginUtc, i.end_utc AS endUtc,
               i.start_day AS startDay, i.end_day AS endDay,
               (e.meet_link IS NOT NULL OR e.add_meet) AS hasMeet, e.has_alarm AS hasAlarm,
               (e.rrule IS NOT NULL OR e.series_master_id IS NOT NULL) AS isRecurring,
               (e.event_type = 'birthday') AS isBirthday,
               (c.access_role IN ('owner', 'writer') AND e.event_type = 'default' AND (e.organizer_email IS NULL
                   OR e.organizer_email IN (c.remote_id, c.account_email) OR e.guests_can_modify)) AS canWrite,
               NULL AS attachmentsJson, 1 AS inOther
        FROM instances i
        JOIN events e ON e.id = i.event_id
        JOIN calendars c ON c.id = e.calendar_id
        WHERE i.event_id = :eventId AND i.begin_utc = :beginUtc
        """,
    )
    suspend fun get(eventId: String, beginUtc: Long): InstanceRow?
}

@Dao
interface SyncMessageDao {
    @Insert
    suspend fun insert(message: SyncMessageEntity)

    @Query("SELECT * FROM sync_messages WHERE read = 0 ORDER BY at DESC")
    fun observeUnread(): Flow<List<SyncMessageEntity>>

    @Query("UPDATE sync_messages SET read = 1 WHERE id = :id")
    suspend fun markRead(id: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM sync_messages WHERE read = 0 AND account_email = :email AND kind = :kind)")
    suspend fun hasUnread(email: String, kind: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM sync_messages WHERE read = 0 AND account_email = :email AND kind = :kind AND text = :text)")
    suspend fun hasUnreadText(email: String, kind: String, text: String): Boolean
}
