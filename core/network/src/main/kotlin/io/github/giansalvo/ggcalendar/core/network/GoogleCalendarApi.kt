package io.github.giansalvo.ggcalendar.core.network

import io.github.giansalvo.ggcalendar.core.model.Reminder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request

data class GoogleCalendarEntry(
    val id: String,
    val name: String,
    val colorHex: String,
    val isPrimary: Boolean,
    val canWrite: Boolean,
    val accessRole: String = if (canWrite) "owner" else "reader",
    val timeZone: String? = null,
    val defaultReminders: List<Reminder> = emptyList(),
)

data class GoogleSyncPage(val items: List<GoogleEvent>, val nextSyncToken: String?)

class GoogleApiException(val status: Int, message: String) : Exception(message) {
    /**
     * Google answers "too many requests" with 429, or with 403 and a reason such as rateLimitExceeded: the same request
     * goes through later, so it is not a rejection (found on 13/10/2026: a burst of new events made the phone drop them).
     */
    val isRateLimit: Boolean
        get() = status == 429 || (status == 403 && RATE_LIMIT.containsMatchIn(message.orEmpty()))

    private companion object {
        val RATE_LIMIT = Regex("rateLimitExceeded|userRateLimitExceeded|quotaExceeded|dailyLimitExceeded|usage limits exceeded", RegexOption.IGNORE_CASE)
    }
}

/**
 * Thin Calendar v3 client, ported from the desktop app's GoogleCalendarClient. The caller supplies a fresh access
 * token for each call (on Android tokens come from Play Services' AuthorizationClient, which refreshes them).
 */
class GoogleCalendarApi(
    private val http: OkHttpClient,
    private val baseUrl: HttpUrl = "https://www.googleapis.com/calendar/v3/".toHttpUrl(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        val EVENT_TYPES = listOf("default", "birthday", "focusTime", "fromGmail", "outOfOffice", "workingLocation")
    }

    /** The primary calendar id is the account email. */
    suspend fun primaryCalendarId(accessToken: String): String =
        get(url("calendars", "primary"), accessToken).string("id")
            ?: throw GoogleApiException(200, "Google Calendar: primary calendar without id")

    suspend fun listCalendars(accessToken: String): List<GoogleCalendarEntry> {
        val list = mutableListOf<GoogleCalendarEntry>()
        var page: String? = null
        do {
            val url = url("users", "me", "calendarList").newBuilder()
                .addQueryParameter("minAccessRole", "reader")
                .apply { page?.let { addQueryParameter("pageToken", it) } }
                .build()
            val r = get(url, accessToken)
            r["items"]?.jsonArray?.forEach { item ->
                val c = item.jsonObject
                val role = c.string("accessRole") ?: "reader"
                list += GoogleCalendarEntry(
                    id = c.string("id") ?: return@forEach,
                    name = c.string("summaryOverride") ?: c.string("summary") ?: "",
                    colorHex = c.string("backgroundColor") ?: "#1A73E8",
                    isPrimary = c["primary"]?.jsonPrimitive?.boolean ?: false,
                    canWrite = role == "owner" || role == "writer",
                    accessRole = role,
                    timeZone = c.string("timeZone"),
                    defaultReminders = GoogleEventMapper.parseDefaultReminders(c),
                )
            }
            page = r.string("nextPageToken")
        } while (page != null)
        return list
    }

    /**
     * Lists events. Without a sync token it fetches everything; with one, only the changes (including cancellations).
     * A 410 ([GoogleApiException.status]) means the token expired: the caller must do a full sync.
     */
    suspend fun listEvents(calendarId: String, syncToken: String?, accessToken: String): GoogleSyncPage {
        val items = mutableListOf<GoogleEvent>()
        var page: String? = null
        var next: String? = null
        do {
            val url = url("calendars", calendarId, "events").newBuilder()
                .addQueryParameter("singleEvents", "false")
                .addQueryParameter("maxResults", "250")
                .addQueryParameter("showDeleted", "true")
                .apply {
                    // Contacts' birthdays (eventType "birthday") are returned only when asked for explicitly.
                    EVENT_TYPES.forEach { addQueryParameter("eventTypes", it) }
                    syncToken?.let { addQueryParameter("syncToken", it) }
                    page?.let { addQueryParameter("pageToken", it) }
                }
                .build()
            val r = get(url, accessToken)
            r["items"]?.jsonArray?.forEach { item -> GoogleEventMapper.fromGoogle(item.jsonObject)?.let(items::add) }
            page = r.string("nextPageToken")
            r.string("nextSyncToken")?.let { next = it }
        } while (page != null)
        return GoogleSyncPage(items, next)
    }

    /** Creates an event; Google answers with its id, etag and, later, the Meet link. */
    suspend fun insertEvent(calendarId: String, body: JsonObject, sendUpdates: Boolean, accessToken: String): GoogleEvent =
        send("POST", writeUrl(sendUpdates, "calendars", calendarId, "events"), body, accessToken).toEvent()

    /**
     * Updates the fields in [body]. With [etag] the update applies only to that version: otherwise Google answers 412
     * ([GoogleApiException.status]) and the caller decides about the conflict.
     */
    suspend fun patchEvent(calendarId: String, eventId: String, body: JsonObject, sendUpdates: Boolean, accessToken: String, etag: String? = null): GoogleEvent =
        send("PATCH", writeUrl(sendUpdates, "calendars", calendarId, "events", eventId), body, accessToken, etag).toEvent()

    /** Deletes an event; one that Google no longer has (404, 410) counts as deleted. */
    suspend fun deleteEvent(calendarId: String, eventId: String, sendUpdates: Boolean, accessToken: String) {
        try {
            send("DELETE", writeUrl(sendUpdates, "calendars", calendarId, "events", eventId), null, accessToken)
        } catch (e: GoogleApiException) {
            if (e.status != 404 && e.status != 410) throw e
        }
    }

    /** Moves an event to another calendar of the same account. */
    suspend fun moveEvent(calendarId: String, eventId: String, destinationId: String, sendUpdates: Boolean, accessToken: String): GoogleEvent {
        val url = writeUrl(sendUpdates, "calendars", calendarId, "events", eventId, "move").newBuilder()
            .addQueryParameter("destination", destinationId).build()
        return send("POST", url, null, accessToken).toEvent()
    }

    /** The current version of an event, or null if Google no longer has it. */
    suspend fun getEvent(calendarId: String, eventId: String, accessToken: String): GoogleEvent? = try {
        get(url("calendars", calendarId, "events", eventId), accessToken).toEvent()
    } catch (e: GoogleApiException) {
        if (e.status == 404 || e.status == 410) null else throw e
    }

    private fun JsonObject.toEvent(): GoogleEvent =
        GoogleEventMapper.fromGoogle(this) ?: throw GoogleApiException(200, "Google Calendar: event without id or dates")

    private fun writeUrl(sendUpdates: Boolean, vararg segments: String): HttpUrl = url(*segments).newBuilder()
        .addQueryParameter("conferenceDataVersion", "1")
        .addQueryParameter("sendUpdates", if (sendUpdates) "all" else "none")
        .build()

    private suspend fun send(method: String, url: HttpUrl, body: JsonObject?, accessToken: String, ifMatch: String? = null): JsonObject =
        withContext(Dispatchers.IO) {
            val requestBody = body?.toString()?.toRequestBody(JSON_TYPE)
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer $accessToken")
                .apply { ifMatch?.let { header("If-Match", it) } }
                .method(method, requestBody ?: if (method == "POST") ByteArray(0).toRequestBody(null) else null)
                .build()
            http.newCall(request).execute().use { res ->
                val text = res.body.string()
                if (!res.isSuccessful) throw GoogleApiException(res.code, "Google Calendar: ${res.code} $text")
                if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text).jsonObject
            }
        }

    private fun url(vararg segments: String): HttpUrl =
        baseUrl.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()

    private suspend fun get(url: HttpUrl, accessToken: String): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").build()
        http.newCall(request).execute().use { res ->
            val text = res.body.string()
            if (!res.isSuccessful) throw GoogleApiException(res.code, "Google Calendar: ${res.code} $text")
            json.parseToJsonElement(text).jsonObject
        }
    }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.takeIf { it.isString }?.content
}
