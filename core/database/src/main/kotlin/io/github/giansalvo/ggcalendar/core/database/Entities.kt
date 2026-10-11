package io.github.giansalvo.ggcalendar.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import io.github.giansalvo.ggcalendar.core.model.Attendee
import io.github.giansalvo.ggcalendar.core.model.Reminder

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val email: String,
    @ColumnInfo(name = "display_name") val displayName: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "added_at") val addedAt: Long = System.currentTimeMillis(),
)

/** Google calendars have id "{email}|{googleId}" as in the desktop app; local calendars (M6) "local:{uuid}" and no account. */
@Entity(
    tableName = "calendars",
    foreignKeys = [ForeignKey(AccountEntity::class, ["email"], ["account_email"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("account_email")],
)
data class CalendarEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "account_email") val accountEmail: String?,
    @ColumnInfo(name = "remote_id") val remoteId: String?,
    val name: String,
    val color: String,
    @ColumnInfo(name = "access_role") val accessRole: String,
    @ColumnInfo(name = "is_primary") val isPrimary: Boolean = false,
    @ColumnInfo(name = "is_visible") val isVisible: Boolean = true,
    @ColumnInfo(name = "default_reminders") val defaultReminders: List<Reminder> = emptyList(),
    @ColumnInfo(name = "time_zone") val timeZone: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "sync_token") val syncToken: String? = null,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long? = null,
) {
    val canWrite: Boolean get() = accessRole == "owner" || accessRole == "writer"
}

/**
 * An event, the master of a series, or a modified/cancelled occurrence of a series ([seriesMasterId] set).
 * Timed events use [startUtc]/[endUtc] (epoch millis); all-day events [startDay]/[endDay] (epoch days, end exclusive).
 * [originalStartUtc]/[originalStartDay] identify the occurrence an exception replaces.
 */
@Entity(
    tableName = "events",
    foreignKeys = [ForeignKey(CalendarEntity::class, ["id"], ["calendar_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("calendar_id"), Index("remote_id"), Index("series_master_id"), Index("ical_uid")],
)
@Serializable
data class EventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "calendar_id") val calendarId: String,
    @ColumnInfo(name = "remote_id") val remoteId: String? = null,
    val etag: String? = null,
    @ColumnInfo(name = "ical_uid") val iCalUid: String? = null,
    val title: String = "",
    val location: String = "",
    val description: String = "",
    @ColumnInfo(name = "all_day") val allDay: Boolean = false,
    @ColumnInfo(name = "start_utc") val startUtc: Long? = null,
    @ColumnInfo(name = "end_utc") val endUtc: Long? = null,
    @ColumnInfo(name = "start_day") val startDay: Long? = null,
    @ColumnInfo(name = "end_day") val endDay: Long? = null,
    @ColumnInfo(name = "time_zone") val timeZone: String? = null,
    val rrule: String? = null,
    /** ISO instants (timed series) or ISO dates (all-day series). */
    val exdates: List<String> = emptyList(),
    @ColumnInfo(name = "series_master_id") val seriesMasterId: String? = null,
    @ColumnInfo(name = "original_start_utc") val originalStartUtc: Long? = null,
    @ColumnInfo(name = "original_start_day") val originalStartDay: Long? = null,
    @ColumnInfo(name = "is_cancelled") val isCancelled: Boolean = false,
    /** Resolved event color (Google colorId), null = calendar color. */
    val color: String? = null,
    val availability: String = "BUSY",
    val visibility: String = "DEFAULT",
    @ColumnInfo(name = "meet_link") val meetLink: String? = null,
    @ColumnInfo(name = "add_meet") val addMeet: Boolean = false,
    @ColumnInfo(name = "send_invites") val sendInvites: Boolean = true,
    @ColumnInfo(name = "guests_can_modify") val guestsCanModify: Boolean = false,
    @ColumnInfo(name = "guests_can_invite") val guestsCanInvite: Boolean = true,
    @ColumnInfo(name = "guests_can_see_list") val guestsCanSeeList: Boolean = true,
    @ColumnInfo(name = "organizer_email") val organizerEmail: String? = null,
    val attendees: List<Attendee> = emptyList(),
    /** Null = the calendar's default reminders. */
    val reminders: List<Reminder>? = null,
    @ColumnInfo(name = "has_alarm") val hasAlarm: Boolean = false,
    /** Google eventType: "default", "birthday", ... */
    @ColumnInfo(name = "event_type", defaultValue = "'default'") val eventType: String = "default",
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

/** One occurrence to draw, precomputed from [EventEntity] (see RecurrenceExpander). Days are in the device time zone. */
@Entity(
    tableName = "instances",
    primaryKeys = ["event_id", "begin_utc"],
    foreignKeys = [ForeignKey(EventEntity::class, ["id"], ["event_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("begin_utc"), Index("start_day", "end_day")],
)
data class InstanceEntity(
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "begin_utc") val beginUtc: Long,
    @ColumnInfo(name = "end_utc") val endUtc: Long,
    @ColumnInfo(name = "start_day") val startDay: Long,
    @ColumnInfo(name = "end_day") val endDay: Long,
    @ColumnInfo(name = "all_day") val allDay: Boolean,
)

/**
 * A local change waiting to be sent to Google, as in the desktop outbox. [payload] is the event as saved (JSON of
 * [EventEntity]) for create and update, the destination calendar id for move, the Google id for delete. [base] is the
 * Google version the edit started from (JSON of [EventEntity]): a 412 is a real conflict only if Google's current
 * version differs from it.
 */
@Entity(tableName = "outbox", indices = [Index("event_id")])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "calendar_id") val calendarId: String,
    /** "create", "update", "move" or "delete". */
    val operation: String,
    val payload: String?,
    val base: String? = null,
    @ColumnInfo(name = "send_updates") val sendUpdates: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
) {
    companion object {
        const val CREATE = "create"
        const val UPDATE = "update"
        const val MOVE = "move"
        const val DELETE = "delete"
    }
}

@Entity(tableName = "sync_messages")
data class SyncMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "account_email") val accountEmail: String?,
    /** "error", "conflict" or "auth" (the account needs to be authorized again). */
    val kind: String,
    val text: String,
    val read: Boolean = false,
)

/** Row of the views' query: an instance joined with its event and calendar. */
data class InstanceRow(
    val eventId: String,
    val calendarId: String,
    val title: String,
    val location: String,
    val colorHex: String,
    val allDay: Boolean,
    val beginUtc: Long,
    val endUtc: Long,
    val startDay: Long,
    val endDay: Long,
    val hasMeet: Boolean,
    val hasAlarm: Boolean,
    val isRecurring: Boolean,
    val isBirthday: Boolean,
    val canWrite: Boolean,
)
