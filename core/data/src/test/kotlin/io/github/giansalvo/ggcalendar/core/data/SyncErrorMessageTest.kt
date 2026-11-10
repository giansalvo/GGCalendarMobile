package io.github.giansalvo.ggcalendar.core.data

import io.github.giansalvo.ggcalendar.core.network.GoogleApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The message a sync error becomes (issue 6): "too many requests" is short and has no JSON, the other errors keep their text. */
class SyncErrorMessageTest {
    private val json403 = """{"error": {"code": 403, "message": "Quota exceeded for quota metric 'Queries' ... per minute per user", "errors": [{"reason": "rateLimitExceeded"}]}}"""

    @Test
    fun `403 with rateLimitExceeded is a short message without text`() {
        assertEquals("rate_limited" to "", SyncEngine.syncErrorMessage(GoogleApiException(403, json403)))
        assertEquals("rate_limited" to "", SyncEngine.syncErrorMessage(GoogleApiException(403, json403), calendar = "Work"))
    }

    @Test
    fun `other reasons of the quota are recognised too`() {
        for (reason in listOf("userRateLimitExceeded", "quotaExceeded", "dailyLimitExceeded", "RATE_LIMIT_EXCEEDED")) {
            assertEquals(reason, "rate_limited", SyncEngine.syncErrorMessage(GoogleApiException(403, """{"error":{"errors":[{"reason":"$reason"}]}}""")).first)
        }
    }

    @Test
    fun `429 is a short message even with an empty body`() {
        assertEquals("rate_limited" to "", SyncEngine.syncErrorMessage(GoogleApiException(429, "")))
    }

    @Test
    fun `403 for another reason keeps Google's text and the calendar name`() {
        val e = GoogleApiException(403, """{"error": {"errors": [{"reason": "forbidden"}]}}""")
        val (kind, text) = SyncEngine.syncErrorMessage(e, calendar = "Work")
        assertEquals("sync_error", kind)
        assertEquals("Work: ${e.message}", text)
    }

    @Test
    fun `other errors keep their text`() {
        assertEquals("sync_error" to "boom", SyncEngine.syncErrorMessage(GoogleApiException(500, "boom")))
        assertEquals("sync_error" to "boom", SyncEngine.syncErrorMessage(IllegalStateException("boom")))
    }

    @Test
    fun `an unknown account is its own message`() {
        assertEquals("account_missing" to "", SyncEngine.syncErrorMessage(IllegalStateException("16: [28433] Cannot find a matching credential.")))
    }

    @Test
    fun `the words of the quota in a non Google error are not taken for it`() {
        val kind = SyncEngine.syncErrorMessage(IOException("quotaExceeded")).first
        assertFalse(kind == "rate_limited")
        assertTrue(kind == "sync_error")
    }
}
