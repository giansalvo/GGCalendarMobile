package io.github.giansalvo.ggcalendar.core.data

import io.github.giansalvo.ggcalendar.core.network.GoogleApiException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** A 401 asks for a new token once; anything else, and a second 401, go on to the caller. */
class FreshTokenTest {
    private val tokens = ArrayDeque(listOf("old", "new", "newer"))
    private val dropped = mutableListOf<String>()
    private val used = mutableListOf<String>()

    private suspend fun attempt(failWith: (String) -> Int?): String = withFreshTokenOnce(
        getToken = { tokens.removeFirst() },
        invalidate = { dropped += it },
    ) { token ->
        used += token
        failWith(token)?.let { throw GoogleApiException(it, "Google Calendar: $it") }
        "ok with $token"
    }

    @Test
    fun aGoodTokenIsUsedOnce() = runTest {
        assertEquals("ok with old", attempt { _ -> null })
        assertEquals(listOf("old"), used)
        assertEquals(emptyList<String>(), dropped)
    }

    @Test
    fun a401MakesItDropTheTokenAndTryAgainWithANewOne() = runTest {
        assertEquals("ok with new", attempt { if (it == "old") 401 else null })
        assertEquals(listOf("old", "new"), used)
        assertEquals(listOf("old"), dropped)
    }

    @Test
    fun aSecond401GoesToTheCaller() = runTest {
        val e = assertThrows(GoogleApiException::class.java) { kotlinx.coroutines.runBlocking { attempt { _ -> 401 } } }
        assertEquals(401, e.status)
        assertEquals(listOf("old", "new"), used)
    }

    @Test
    fun otherErrorsAreNotRetried() = runTest {
        val e = assertThrows(GoogleApiException::class.java) { kotlinx.coroutines.runBlocking { attempt { _ -> 403 } } }
        assertEquals(403, e.status)
        assertEquals(listOf("old"), used)
        assertEquals(emptyList<String>(), dropped)
    }
}
