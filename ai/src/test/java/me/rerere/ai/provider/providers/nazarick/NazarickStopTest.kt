package me.rerere.ai.provider.providers.nazarick

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the pure half of the US-006 stop wiring: [stopUrl] (the exact query the server contract
 * demands — `chat_id` + `cancel_reason` as **query parameters**, percent-encoded) and
 * [stopOutcome] (HTTP status -> [NazarickStopOutcome]). The server resolves the query `chat_id`
 * as a ChatSpec id first and falls back to a `session_id` lookup for channel=console, so the
 * captured-stream case asserts the very id [nazarickSessionId] puts on the chat wire comes back
 * out of the stop query unchanged — one run in, one run stopped.
 */
class NazarickStopTest {

    @Test
    fun `stop url carries chat_id and cancel_reason as query params`() {
        assertEquals(
            "http://192.168.1.7:3131/api/console/chat/stop?chat_id=app-device-17&cancel_reason=USER",
            stopUrl("http://192.168.1.7:3131", "app-device-17"),
        )
    }

    @Test
    fun `stop url percent-encodes space percent and ampersand in the session id`() {
        assertEquals(
            "http://h.local/api/console/chat/stop?chat_id=a%20b%25c%26d&cancel_reason=USER",
            stopUrl("http://h.local", "a b%c&d"),
        )
    }

    @Test
    fun `captured stream session id lands in the stop query unchanged`() {
        // Same builder the US-004 chat body used; the stop query must carry it back verbatim.
        val sessionId = nazarickSessionId("pixel", "42")
        val url = stopUrl("https://tomb.local/", sessionId).toHttpUrl()
        assertEquals(sessionId, url.queryParameter("chat_id"))
        assertEquals("USER", url.queryParameter("cancel_reason"))
        assertEquals("/api/console/chat/stop", url.encodedPath)
    }

    @Test
    fun `stop 200 is OK`() {
        assertEquals(NazarickStopOutcome.OK, stopOutcome(200))
    }

    @Test
    fun `stop 404 is NotFound, a sane answer when the run already ended`() {
        assertEquals(NazarickStopOutcome.NotFound, stopOutcome(404))
    }

    @Test
    fun `stop 500 and 401 map to HttpError carrying the code`() {
        assertEquals(NazarickStopOutcome.HttpError(500), stopOutcome(500))
        assertEquals(NazarickStopOutcome.HttpError(401), stopOutcome(401))
    }

    @Test
    fun `stop request is a POST riding the central header builder`() {
        val url = stopUrl("http://h.local", "app-device-1").toHttpUrl()
        val request = nazarickRequest(url, "tok", "agent-9", NazarickClient.EMPTY_BODY)
        assertEquals("POST", request.method)
        assertEquals("Bearer tok", request.header("Authorization"))
        assertEquals("agent-9", request.header("X-Agent-Id"))
    }
}
