package io.github.giansalvo.ggcalendar.core.notifications

import android.content.Intent
import io.github.giansalvo.ggcalendar.core.data.DueReminder
import io.github.giansalvo.ggcalendar.core.database.ReminderRow
import io.github.giansalvo.ggcalendar.core.model.Reminder
import io.github.giansalvo.ggcalendar.core.model.ReminderMethod
import org.json.JSONObject

/** A reminder being shown: what the notification, the alarm screen and their actions need. */
data class ReminderAlert(
    val key: String,
    val eventId: String,
    val beginUtc: Long,
    val endUtc: Long,
    val allDay: Boolean,
    val title: String,
    val location: String,
    val description: String,
    val meetLink: String?,
    val method: ReminderMethod,
    val minutes: Int,
) {
    val isAlarm: Boolean get() = method == ReminderMethod.ALARM

    /** Stable id of the notification of this reminder. */
    val notificationId: Int get() = key.hashCode()

    fun putInto(intent: Intent): Intent = intent
        .putExtra(KEY, key).putExtra(EVENT, eventId).putExtra(BEGIN, beginUtc).putExtra(END, endUtc)
        .putExtra(ALL_DAY, allDay).putExtra(TITLE, title).putExtra(LOCATION, location).putExtra(DESCRIPTION, description).putExtra(MEET, meetLink)
        .putExtra(METHOD, method.name).putExtra(MINUTES, minutes)

    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("event", eventId).put("begin", beginUtc).put("end", endUtc).put("allDay", allDay)
        .put("title", title).put("location", location).put("description", description).put("meet", meetLink)
        .put("method", method.name).put("minutes", minutes)

    companion object {
        private const val KEY = "reminder.key"
        private const val EVENT = "reminder.event"
        private const val BEGIN = "reminder.begin"
        private const val END = "reminder.end"
        private const val ALL_DAY = "reminder.allDay"
        private const val TITLE = "reminder.title"
        private const val LOCATION = "reminder.location"
        private const val MEET = "reminder.meet"
        private const val DESCRIPTION = "reminder.description"

        /** Intent extras are limited in size: a long description is cut. */
        private const val MAX_DESCRIPTION = 1_500
        private const val METHOD = "reminder.method"
        private const val MINUTES = "reminder.minutes"

        fun of(due: DueReminder): ReminderAlert = of(due.row, due.reminder)

        fun of(row: ReminderRow, reminder: Reminder) = ReminderAlert(
            key = due(row, reminder),
            eventId = row.eventId, beginUtc = row.beginUtc, endUtc = row.endUtc, allDay = row.allDay,
            title = row.title, location = row.location, description = row.description.take(MAX_DESCRIPTION), meetLink = row.meetLink,
            method = reminder.method, minutes = reminder.minutes,
        )

        private fun due(row: ReminderRow, reminder: Reminder) = DueReminder.keyOf(row.eventId, row.beginUtc, reminder)

        fun fromJson(json: JSONObject): ReminderAlert? = runCatching {
            ReminderAlert(
                key = json.getString("key"),
                eventId = json.getString("event"),
                beginUtc = json.getLong("begin"),
                endUtc = json.getLong("end"),
                allDay = json.getBoolean("allDay"),
                title = json.optString("title"),
                location = json.optString("location"),
                description = json.optString("description"),
                meetLink = if (json.isNull("meet")) null else json.getString("meet"),
                method = runCatching { ReminderMethod.valueOf(json.optString("method")) }.getOrDefault(ReminderMethod.POPUP),
                minutes = json.optInt("minutes"),
            )
        }.getOrNull()

        fun from(intent: Intent): ReminderAlert? {
            val key = intent.getStringExtra(KEY) ?: return null
            return ReminderAlert(
                key = key,
                eventId = intent.getStringExtra(EVENT) ?: return null,
                beginUtc = intent.getLongExtra(BEGIN, 0),
                endUtc = intent.getLongExtra(END, 0),
                allDay = intent.getBooleanExtra(ALL_DAY, false),
                title = intent.getStringExtra(TITLE).orEmpty(),
                location = intent.getStringExtra(LOCATION).orEmpty(),
                description = intent.getStringExtra(DESCRIPTION).orEmpty(),
                meetLink = intent.getStringExtra(MEET),
                method = runCatching { ReminderMethod.valueOf(intent.getStringExtra(METHOD).orEmpty()) }.getOrDefault(ReminderMethod.POPUP),
                minutes = intent.getIntExtra(MINUTES, 0),
            )
        }
    }
}
