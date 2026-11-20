package me.rerere.ai.provider.providers.nazarick

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure half of the US-007 push-messages client: [pushMessagesUrl] (the exact query the
 * server contract demands - `session_id` as a **query parameter**, percent-encoded, carrying the
 * same namespaced id the chat POST sends), [parseNazarickPushMessages] against the envelope the
 * shipped server actually serialises (`_strip_ts` -> `{id, text, sticky}`, plus the two list fields
 * this story ignores), and [pushOutcome] status mapping (401 = stop polling).
 */
class NazarickPushTest {

    @Test
    fun `push url carries session_id as a query param`() {
        assertEquals(
            "http://192.168.1.7:3131/api/console/push-messages?session_id=app-device-17",
            pushMessagesUrl("http://192.168.1.7:3131", "app-device-17"),
        )
    }

    @Test
    fun `push url percent-encodes space percent and ampersand in the session id`() {
        assertEquals(
            "http://h.local/api/console/push-messages?session_id=a%20b%25c%26d",
            pushMessagesUrl("http://h.local", "a b%c&d"),
        )
    }

    @Test
    fun `chat session id lands in the push query unchanged`() {
        // One id on all three wires (US-004 chat, US-006 stop, US-007 push): the namespaced id the
        // run started with is the id the store holds that run's messages under.
        val sessionId = nazarickSessionId("pixel", "42")
        val url = pushMessagesUrl("https://tomb.local/", sessionId).toHttpUrl()
        assertEquals(sessionId, url.queryParameter("session_id"))
        assertEquals("/api/console/push-messages", url.encodedPath)
    }

    @Test
    fun `envelope parses the shipped message shape verbatim`() {
        val body = "{\"messages\":[{\"id\":\"2f7a\",\"text\":\"The Tomb answers\",\"sticky\":true}," +
            "{\"id\":\"9c1b\",\"text\":\"second\",\"sticky\":false}]," +
            "\"pending_approvals\":[{\"request_id\":\"r1\",\"session_id\":\"default\",\"tool_name\":\"bash\"}]," +
            "\"notification_events\":[{\"event_id\":\"e1\"}]}"
        val messages = parseNazarickPushMessages(body)
        assertEquals(2, messages.size)
        assertEquals("2f7a", messages[0].id)
        assertEquals("The Tomb answers", messages[0].text)
        assertTrue(messages[0].sticky)
        assertEquals("9c1b", messages[1].id)
        assertFalse(messages[1].sticky)
    }

    @Test
    fun `empty envelope and unknown fields are both handled`() {
        assertEquals(0, parseNazarickPushMessages("{\"messages\":[],\"pending_approvals\":[],\"notification_events\":[]}").size)
        // A response with no messages key at all is an empty answer, not a crash.
        assertEquals(0, parseNazarickPushMessages("{}").size)
        // Extra per-message fields (a future `ts`, `agent_id`) must not break the parse.
        val one = parseNazarickPushMessages("{\"messages\":[{\"id\":\"x\",\"text\":\"hi\",\"sticky\":false,\"ts\":1760000000.5,\"agent_id\":\"aura\"}]}")
        assertEquals(1, one.size)
        assertEquals("hi", one[0].text)
    }

    @Test
    fun `200 maps to Reply and 401 and 404 and 5xx stay distinguishable`() {
        val ok = pushOutcome(200, "{\"messages\":[{\"id\":\"a\",\"text\":\"hi\",\"sticky\":false}]}")
        assertTrue(ok is NazarickPushOutcome.Reply)
        assertEquals("hi", (ok as NazarickPushOutcome.Reply).messages.single().text)
        assertEquals(NazarickPushOutcome.Unauthorized, pushOutcome(401, "{\"detail\":\"invalid token\"}"))
        assertEquals(NazarickPushOutcome.NotFound, pushOutcome(404, "not found"))
        assertEquals(NazarickPushOutcome.HttpError(503), pushOutcome(503, "unavailable"))
        assertEquals(NazarickPushOutcome.HttpError(429), pushOutcome(429, "slow down"))
    }

    @Test
    fun `200 with a body that is not the envelope is Malformed, not an empty answer`() {
        // A proxy login page answering 200 must never be read as "nothing pending": the poller would
        // wait out the turn while a reply sat unconsumed behind the HTML.
        val outcome = pushOutcome(200, "<html><body>sign in</body></html>")
        assertTrue(outcome is NazarickPushOutcome.Malformed)
        assertEquals("<html><body>sign in</body></html>", (outcome as NazarickPushOutcome.Malformed).snippet)
    }

    @Test
    fun `push request is a GET riding the central header builder`() {
        val url = pushMessagesUrl("http://h.local", "app-device-1").toHttpUrl()
        val request = nazarickRequest(url, "tok", "agent-9")
        assertEquals("GET", request.method)
        assertEquals("Bearer tok", request.header("Authorization"))
        assertEquals("agent-9", request.header("X-Agent-Id"))
        // An unauth loopback instance must not get an empty Authorization header.
        val anon = nazarickRequest(url, "", "")
        assertEquals(null, anon.header("Authorization"))
        assertEquals(null, anon.header("X-Agent-Id"))
    }

    @Test
    fun `push path is the console endpoint of record`() {
        assertEquals("/api/console/push-messages", NazarickClient.PUSH_PATH)
    }
}
