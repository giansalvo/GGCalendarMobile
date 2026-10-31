package io.github.giansalvo.ggcalendar.core.data

import androidx.room.withTransaction
import io.github.giansalvo.ggcalendar.core.auth.AuthorizationOutcome
import io.github.giansalvo.ggcalendar.core.auth.GoogleAuthorizer
import io.github.giansalvo.ggcalendar.core.database.CalendarEntity
import io.github.giansalvo.ggcalendar.core.database.CalendarKind
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.database.SyncMessageEntity
import io.github.giansalvo.ggcalendar.core.model.CalendarInfo
import io.github.giansalvo.ggcalendar.core.model.Diag
import io.github.giansalvo.ggcalendar.core.network.GoogleApiException
import io.github.giansalvo.ggcalendar.core.network.GoogleCalendarApi
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** The account has to authorize the app again (the user's consent screen is needed). */
internal class ConsentRequiredException : Exception()

/** Why a calendar of Google could not be made, changed or deleted. Each one has its own message on the screen. */
enum class GoogleCalendarProblem {
    EMPTY_NAME, NO_ACCOUNT,

    /** The request did not leave the phone: nothing was done. */
    NO_NETWORK,

    /** The connection broke after the request was sent: Google may have done it. Not to be repeated before looking. */
    MAYBE_DONE,
    RATE_LIMITED, NOT_ALLOWED, AUTH_EXPIRED, SERVER, PRIMARY, NOT_OWNER, OTHER,
}

/**
 * The outcome of one operation: [problem] is null when it went through. [partial] is a problem of a second step that did not stop the
 * first one (the color refused after a rename that was accepted).
 */
class GoogleCalendarResult(val calendarId: String? = null, val problem: GoogleCalendarProblem? = null, val partial: GoogleCalendarProblem? = null) {
    val ok: Boolean get() = problem == null
}

/**
 * Makes, changes and deletes the calendars of a Google account (issue #14). It goes to Google at once and only online: without a network, or
 * when Google refuses, nothing is done here either and the result says why. Nothing is queued, because the events of a calendar that does not
 * exist yet could not be sent. The events of a new calendar come with the next sync.
 *
 * What is written in the database waits for the sync that may be running: a sync deletes the calendars that are not in the list it
 * downloaded before ([SyncEngine.exclusive]).
 */
@Singleton
class GoogleCalendars @Inject constructor(
    private val db: GGCalendarDatabase,
    private val api: GoogleCalendarApi,
    private val authorizer: GoogleAuthorizer,
    private val engine: SyncEngine,
    private val reminders: ReminderHooks,
) {
    /** Where the access tokens come from; a test gives its own. */
    internal class Tokens(val get: suspend () -> String, val invalidate: suspend (String) -> Unit)

    private fun tokensOf(email: String) = Tokens(
        get = {
            when (val outcome = authorizer.authorize(email)) {
                is AuthorizationOutcome.Authorized -> outcome.accessToken
                is AuthorizationOutcome.NeedsConsent -> throw ConsentRequiredException()
            }
        },
        invalidate = { authorizer.invalidate(it) },
    )

    suspend fun create(email: String, name: String, colorHex: String): GoogleCalendarResult =
        create(email, name, colorHex, tokensOf(email), ZoneId.systemDefault().id)

    suspend fun update(calendarId: String, newName: String?, newColorHex: String?): GoogleCalendarResult {
        val email = db.calendarDao().get(calendarId)?.accountEmail ?: return GoogleCalendarResult(problem = GoogleCalendarProblem.OTHER)
        return update(calendarId, newName, newColorHex, tokensOf(email))
    }

    suspend fun delete(calendarId: String): GoogleCalendarResult {
        val email = db.calendarDao().get(calendarId)?.accountEmail ?: return GoogleCalendarResult(problem = GoogleCalendarProblem.OTHER)
        return delete(calendarId, tokensOf(email))
    }

    internal suspend fun create(email: String, name: String, colorHex: String, tokens: Tokens, zone: String): GoogleCalendarResult {
        val clean = cleanName(name)
        if (clean.isEmpty()) return GoogleCalendarResult(problem = GoogleCalendarProblem.EMPTY_NAME)
        if (db.accountDao().getAll().none { it.email == email }) return GoogleCalendarResult(problem = GoogleCalendarProblem.NO_ACCOUNT)
        val remoteId = try {
            withFreshTokenOnce(tokens.get, tokens.invalidate) { api.createCalendar(clean, zone, it) }
        } catch (e: Exception) {
            return failed(email, "create", e)
        }
        // The calendar exists. A color that Google refuses does not undo it: the next sync takes the color Google has.
        try {
            withFreshTokenOnce(tokens.get, tokens.invalidate) { api.setCalendarListEntry(remoteId, colorHex, clearPersonalName = false, accessToken = it) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Diag.log { "gcal create ${Diag.who(email)} color refused: ${problemOf(e)}" }
        }
        val id = CalendarInfo.googleId(email, remoteId)
        engine.exclusive {
            // A sync that ran meanwhile may have written it already, with the values of Google.
            if (db.calendarDao().get(id) == null) {
                db.calendarDao().upsert(
                    CalendarEntity(
                        id = id, accountEmail = email, remoteId = remoteId, name = clean, color = colorHex,
                        accessRole = "owner", timeZone = zone,
                    ),
                )
            }
        }
        Diag.log { "gcal create ${Diag.who(email)} done" }
        return GoogleCalendarResult(calendarId = id)
    }

    /**
     * A new [newName] goes to the calendar itself, so only its owner can give one, never to the primary calendar; the color is the person's
     * own and can be changed on any calendar. Renaming also removes the personal name the person may have given on Google, which would hide
     * the new one. When the name is accepted and the color is not, the name stays changed and [GoogleCalendarResult.partial] says why.
     */
    internal suspend fun update(calendarId: String, newName: String?, newColorHex: String?, tokens: Tokens): GoogleCalendarResult {
        val calendar = db.calendarDao().get(calendarId)
        val email = calendar?.accountEmail
        val remoteId = calendar?.remoteId
        if (calendar == null || calendar.kind != CalendarKind.GOOGLE || email == null || remoteId == null) {
            return GoogleCalendarResult(problem = GoogleCalendarProblem.OTHER)
        }
        val name = newName?.let(::cleanName)
        val rename = name != null && name != calendar.name
        val recolor = newColorHex != null && !newColorHex.equals(calendar.color, ignoreCase = true)
        if (!rename && !recolor) return GoogleCalendarResult(calendarId = calendarId)
        if (rename) {
            if (name!!.isEmpty()) return GoogleCalendarResult(problem = GoogleCalendarProblem.EMPTY_NAME)
            if (calendar.isPrimary) return GoogleCalendarResult(problem = GoogleCalendarProblem.PRIMARY)
            if (calendar.accessRole != "owner") return GoogleCalendarResult(problem = GoogleCalendarProblem.NOT_OWNER)
        }
        var finalName = calendar.name
        var finalColor = calendar.color
        var partial: GoogleCalendarProblem? = null
        try {
            if (rename) {
                withFreshTokenOnce(tokens.get, tokens.invalidate) { api.renameCalendar(remoteId, name!!, it) }
                finalName = name!!
                try {
                    withFreshTokenOnce(tokens.get, tokens.invalidate) {
                        api.setCalendarListEntry(remoteId, if (recolor) newColorHex else null, clearPersonalName = true, accessToken = it)
                    }
                    if (recolor) finalColor = newColorHex!!
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    partial = problemOf(e)
                }
            } else {
                withFreshTokenOnce(tokens.get, tokens.invalidate) { api.setCalendarListEntry(remoteId, newColorHex, clearPersonalName = false, accessToken = it) }
                finalColor = newColorHex!!
            }
        } catch (e: Exception) {
            return failed(email, "update", e)
        }
        engine.exclusive { if (db.calendarDao().get(calendarId) != null) db.calendarDao().setNameAndColor(calendarId, finalName, finalColor) }
        Diag.log { "gcal update ${Diag.who(email)} done rename=$rename recolor=$recolor partial=$partial" }
        return GoogleCalendarResult(calendarId = calendarId, partial = partial)
    }

    /**
     * Deletes a calendar the person owns, and that is not the primary one, on Google with all its events (for everybody it is shared with)
     * and then here, with what was queued for it. Anything else is refused without asking Google.
     */
    internal suspend fun delete(calendarId: String, tokens: Tokens): GoogleCalendarResult {
        val calendar = db.calendarDao().get(calendarId)
        val email = calendar?.accountEmail
        val remoteId = calendar?.remoteId
        if (calendar == null || calendar.kind != CalendarKind.GOOGLE || email == null || remoteId == null) {
            return GoogleCalendarResult(problem = GoogleCalendarProblem.OTHER)
        }
        if (calendar.isPrimary) return GoogleCalendarResult(problem = GoogleCalendarProblem.PRIMARY)
        if (calendar.accessRole != "owner") return GoogleCalendarResult(problem = GoogleCalendarProblem.NOT_OWNER)
        // The whole operation under the lock: a sync that read the calendar after Google lost it would report an error.
        return engine.exclusive {
            try {
                withFreshTokenOnce(tokens.get, tokens.invalidate) { api.deleteCalendar(remoteId, it) }
            } catch (e: Exception) {
                return@exclusive failed(email, "delete", e)
            }
            db.withTransaction {
                db.outboxDao().discardForCalendar(calendarId)
                db.reminderDao().deleteSnoozedOfCalendar(calendarId)
                db.calendarDao().delete(calendarId)
            }
            reminders.changed()
            Diag.log { "gcal delete ${Diag.who(email)} done" }
            GoogleCalendarResult(calendarId = calendarId)
        }
    }

    private suspend fun failed(email: String, what: String, e: Exception): GoogleCalendarResult {
        if (e is kotlinx.coroutines.CancellationException) throw e
        val problem = problemOf(e)
        Diag.log { "gcal $what ${Diag.who(email)} failed: $problem ${(e as? GoogleApiException)?.status ?: e.javaClass.simpleName}" }
        // The same message as a sync that lost the access: the snackbar offers to authorize again.
        if (e is ConsentRequiredException && !db.syncMessageDao().hasUnread(email, "auth")) {
            db.syncMessageDao().insert(SyncMessageEntity(accountEmail = email, kind = "auth", text = "Access to Google Calendar must be granted again"))
        }
        return GoogleCalendarResult(problem = problem)
    }

    companion object {
        /** The longest name sent to Google (the same as the desktop app). */
        const val MAX_NAME = 255

        /** The name as it goes to Google: no spaces around it, no line breaks, cut without splitting a character in two. */
        fun cleanName(raw: String): String {
            val oneLine = raw.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()
            if (oneLine.length <= MAX_NAME) return oneLine
            val cut = if (Character.isHighSurrogate(oneLine[MAX_NAME - 1])) MAX_NAME - 1 else MAX_NAME
            return oneLine.take(cut).trim()
        }

        internal fun problemOf(e: Throwable): GoogleCalendarProblem = when (e) {
            is ConsentRequiredException -> GoogleCalendarProblem.AUTH_EXPIRED
            is GoogleApiException -> when {
                e.isRateLimit -> GoogleCalendarProblem.RATE_LIMITED
                e.status == 401 -> GoogleCalendarProblem.AUTH_EXPIRED
                e.status == 403 -> GoogleCalendarProblem.NOT_ALLOWED
                e.status >= 500 -> GoogleCalendarProblem.SERVER
                else -> GoogleCalendarProblem.OTHER
            }
            // The request could not even leave the phone.
            is UnknownHostException, is ConnectException, is NoRouteToHostException -> GoogleCalendarProblem.NO_NETWORK
            // A timeout or a connection that broke later: Google may have received the request.
            is IOException -> GoogleCalendarProblem.MAYBE_DONE
            else -> GoogleCalendarProblem.OTHER
        }
    }
}
