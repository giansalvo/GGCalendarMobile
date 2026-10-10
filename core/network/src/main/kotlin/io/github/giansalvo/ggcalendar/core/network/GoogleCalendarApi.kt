package io.github.giansalvo.ggcalendar.core.network

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
import okhttp3.OkHttpClient
import okhttp3.Request

data class GoogleCalendarEntry(
    val id: String,
    val name: String,
    val colorHex: String,
    val isPrimary: Boolean,
    val canWrite: Boolean,
)

class GoogleApiException(val status: Int, message: String) : Exception(message)

/**
 * Thin Calendar v3 client, ported from the desktop app's GoogleCalendarClient. The caller supplies a fresh access
 * token for each call (on Android tokens come from Play Services' AuthorizationClient, which refreshes them).
 */
class GoogleCalendarApi(
    private val http: OkHttpClient,
    private val baseUrl: HttpUrl = "https://www.googleapis.com/calendar/v3/".toHttpUrl(),
) {
    private val json = Json { ignoreUnknownKeys = true }

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
                )
            }
            page = r.string("nextPageToken")
        } while (page != null)
        return list
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
