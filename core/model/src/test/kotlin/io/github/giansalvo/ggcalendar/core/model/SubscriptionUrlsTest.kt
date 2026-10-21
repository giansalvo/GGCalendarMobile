package io.github.giansalvo.ggcalendar.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The address of a subscription: what is accepted (U151), what is shown (U152) and what a message may say (U153). */
class SubscriptionUrlsTest {
    private fun ok(input: String): String = (SubscriptionUrls.normalize(input) as UrlCheck.Ok).url
    private fun problem(input: String): UrlProblem = (SubscriptionUrls.normalize(input) as UrlCheck.Rejected).problem

    private val publicFeed = "https://calendar.google.com/calendar/ical/someone%40example.org/public/basic.ics"
    private val privateFeed = "https://calendar.google.com/calendar/ical/someone%40example.org/private-0123456789abcdef0123456789abcdef/basic.ics"

    // ---- U151: what is accepted

    @Test
    fun `an https address is kept, with its scheme and host in lower case`() {
        assertEquals(publicFeed, ok("  $publicFeed  "))
        assertEquals("https://calendar.google.com/a/b.ics", ok("HTTPS://Calendar.Google.com/a/b.ics"))
        assertEquals(publicFeed, ok("\"$publicFeed\""))
        assertEquals(publicFeed, ok("<$publicFeed>"))
    }

    @Test
    fun `webcal becomes https`() {
        assertEquals("https://example.org/cal.ics", ok("webcal://example.org/cal.ics"))
        assertEquals("https://example.org/cal.ics", ok("WEBCAL://example.org/cal.ics"))
        assertEquals("https://example.org/cal.ics", ok("webcals://example.org/cal.ics"))
    }

    @Test
    fun `http and other schemes are refused`() {
        assertEquals(UrlProblem.NOT_HTTPS, problem("http://example.org/cal.ics"))
        assertEquals(UrlProblem.NOT_HTTPS, problem("ftp://example.org/cal.ics"))
        assertEquals(UrlProblem.NOT_HTTPS, problem("file:///sdcard/cal.ics"))
        assertEquals(UrlProblem.NOT_HTTPS, problem("content://x/y"))
        assertEquals(UrlProblem.NOT_AN_ADDRESS, problem("calendar.google.com/ical"))
    }

    @Test
    fun `an empty address, a long one, one with spaces or credentials is refused`() {
        assertEquals(UrlProblem.EMPTY, problem("   "))
        assertEquals(UrlProblem.TOO_LONG, problem("https://example.org/" + "a".repeat(SubscriptionUrls.MAX_LENGTH)))
        assertEquals(UrlProblem.NOT_AN_ADDRESS, problem("https://example.org/my cal.ics"))
        assertEquals(UrlProblem.HAS_CREDENTIALS, problem("https://user:pass@example.org/cal.ics"))
        assertEquals(UrlProblem.NOT_AN_ADDRESS, problem("https:///cal.ics"))
    }

    @Test
    fun `the default port and the fragment go, the query stays`() {
        assertEquals("https://example.org/cal.ics?x=1", ok("https://example.org:443/cal.ics?x=1#top"))
        assertEquals("https://example.org:8443/cal.ics", ok("https://example.org:8443/cal.ics"))
        assertEquals("https://example.org/", ok("https://example.org"))
    }

    // ---- U152: what is shown

    @Test
    fun `the private address of a Google calendar is a secret and its key is masked`() {
        assertTrue(SubscriptionUrls.isLikelySecret(privateFeed))
        val shown = SubscriptionUrls.mask(privateFeed)
        assertEquals("https://calendar.google.com/…/private-••••••/basic.ics", shown)
        assertFalse(shown.contains("0123456789abcdef"))
        assertFalse(shown.contains("someone"))
    }

    @Test
    fun `the public address is not a secret and does not show the owner either`() {
        assertFalse(SubscriptionUrls.isLikelySecret(publicFeed))
        assertEquals("https://calendar.google.com/…/basic.ics", SubscriptionUrls.mask(publicFeed))
    }

    @Test
    fun `a key in the query makes an address secret and the query is never shown`() {
        val url = "https://example.org/cal.ics?token=abc123"
        assertTrue(SubscriptionUrls.isLikelySecret(url))
        assertEquals("https://example.org/cal.ics?…", SubscriptionUrls.mask(url))
        assertTrue(SubscriptionUrls.isLikelySecret("https://example.org/feeds/${"a1".repeat(20)}/cal.ics"))
        assertFalse(SubscriptionUrls.isLikelySecret("https://example.org/holidays/it.ics"))
    }

    // ---- U153: a message with the address in it

    @Test
    fun `redact takes the address, the path and the query out of a message`() {
        val message = "unexpected response for $privateFeed (path /calendar/ical/someone%40example.org/private-0123456789abcdef0123456789abcdef/basic.ics)"
        val clean = SubscriptionUrls.redact(message, privateFeed)
        assertFalse(clean, clean.contains("0123456789abcdef"))
        assertFalse(clean, clean.contains("someone"))
        val withQuery = SubscriptionUrls.redact("GET https://example.org/cal.ics?token=abc123 failed", "https://example.org/cal.ics?token=abc123")
        assertFalse(withQuery, withQuery.contains("abc123"))
    }
}
