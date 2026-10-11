package io.github.giansalvo.ggcalendar.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
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
}

@Dao
interface EventDao {
    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun get(id: String): EventEntity?

    @Query("SELECT * FROM events WHERE id = :id")
    fun observe(id: String): Flow<EventEntity?>

    @Query("SELECT * FROM events WHERE calendar_id = :calendarId")
    suspend fun getForCalendar(calendarId: String): List<EventEntity>

    /** Local id of each event of a calendar that exists on Google, keyed by remote id. */
    @Query("SELECT remote_id AS remoteId, id FROM events WHERE calendar_id = :calendarId AND remote_id IS NOT NULL")
    suspend fun remoteIds(calendarId: String): List<RemoteLink>

    /** Events that own their instances: single events and series masters (not the occurrences of a series). */
    @Query("SELECT id FROM events WHERE series_master_id IS NULL")
    suspend fun masterIds(): List<String>

    @Query("SELECT id FROM events WHERE series_master_id IS NULL AND rrule IS NOT NULL")
    suspend fun seriesIds(): List<String>

    @Query("SELECT * FROM events WHERE series_master_id = :masterId")
    suspend fun getExceptions(masterId: String): List<EventEntity>

    @Upsert
    suspend fun upsert(events: List<EventEntity>)

    @Query("UPDATE events SET remote_id = :remoteId, etag = :etag WHERE id = :id")
    suspend fun setRemote(id: String, remoteId: String, etag: String?)

    /** Attendees of every event, to suggest addresses in the editor. */
    @Query("SELECT attendees FROM events WHERE attendees != '[]'")
    suspend fun allAttendees(): List<String>

    /** Deletes an event together with the modified or cancelled occurrences of its series. */
    @Query("DELETE FROM events WHERE id IN (:ids) OR series_master_id IN (:ids)")
    suspend fun deleteWithSeries(ids: List<String>)
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

    @Query("UPDATE outbox SET attempts = attempts + 1, last_error = :error WHERE seq = :seq")
    suspend fun failed(seq: Long, error: String?)
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
               (e.event_type = 'birthday') AS isBirthday
        FROM instances i
        JOIN events e ON e.id = i.event_id
        JOIN calendars c ON c.id = e.calendar_id
        WHERE c.is_visible = 1 AND i.start_day < :toDay AND i.end_day > :fromDay
        ORDER BY i.start_day, i.all_day DESC, i.begin_utc, e.title
        """,
    )
    fun observeRange(fromDay: Long, toDay: Long): Flow<List<InstanceRow>>
}

@Dao
interface SyncMessageDao {
    @Insert
    suspend fun insert(message: SyncMessageEntity)

    @Query("SELECT * FROM sync_messages WHERE read = 0 ORDER BY at DESC")
    fun observeUnread(): Flow<List<SyncMessageEntity>>

    @Query("UPDATE sync_messages SET read = 1 WHERE id = :id")
    suspend fun markRead(id: Long)
}
