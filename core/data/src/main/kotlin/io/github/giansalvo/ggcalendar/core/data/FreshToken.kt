package io.github.giansalvo.ggcalendar.core.data

import io.github.giansalvo.ggcalendar.core.network.GoogleApiException

/**
 * Runs [block] with an access token. If Google answers 401 (the token it was given is no longer good: a cached token
 * from before the app data was cleared, or one revoked meanwhile) the token is dropped with [invalidate], a new one is
 * asked with [getToken], and [block] runs once more. A second 401 goes to the caller.
 */
internal suspend fun <T> withFreshTokenOnce(
    getToken: suspend () -> String,
    invalidate: suspend (String) -> Unit,
    block: suspend (String) -> T,
): T {
    val first = getToken()
    return try {
        block(first)
    } catch (e: GoogleApiException) {
        if (e.status != 401) throw e
        invalidate(first)
        block(getToken())
    }
}
