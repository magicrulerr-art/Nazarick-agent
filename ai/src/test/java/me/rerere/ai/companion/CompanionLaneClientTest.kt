package me.rerere.ai.companion

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionLaneClientTest {

    private val enabledBuilder =
        CompanionLaneRequestBuilder("localhost", 8765, "device-1", "Bearer token-abc", enabled = true)

    @Test
    fun chat_request_is_post_to_companion_agent_chat_endpoint() {
        val req: Request = enabledBuilder.chatRequest("agent-42")
        assertEquals("POST", req.method)
        assertEquals(
            "http://localhost:8765/api/companion/agents/agent-42/chat",
            req.url.toString(),
        )
    }

    @Test
    fun chat_request_carries_companion_device_and_authorization_headers() {
        val req: Request = enabledBuilder.chatRequest("agent-42")
        assertEquals("device-1", req.header("X-Companion-Device"))
        assertEquals("Bearer token-abc", req.header("Authorization"))
    }

    @Test
    fun disabled_debug_flag_rejects_requests() {
        val disabled =
            CompanionLaneRequestBuilder("localhost", 8765, "device-1", "Bearer token-abc", enabled = false)
        assertThrows(IllegalStateException::class.java) {
            disabled.chatRequest("agent-42")
        }
    }

    @Test
    fun sse_parser_emits_text_delta() {
        val p = CompanionLaneSseParser()
        assertTrue(p.onLine("event: text-delta").isEmpty())
        assertTrue(p.onLine("data: hello").isEmpty())
        assertEquals(listOf(CompanionEvent.TextDelta("hello")), p.onLine(""))
    }

    @Test
    fun sse_parser_emits_completed() {
        val p = CompanionLaneSseParser()
        assertTrue(p.onLine("event: completed").isEmpty())
        assertEquals(listOf(CompanionEvent.Completed), p.onLine(""))
    }

    @Test
    fun sse_parser_emits_error() {
        val p = CompanionLaneSseParser()
        assertTrue(p.onLine("event: error").isEmpty())
        assertTrue(p.onLine("data: something went wrong").isEmpty())
        val events = p.onLine("")
        assertEquals(1, events.size)
        assertTrue(events.first() is CompanionEvent.Error)
        assertEquals("something went wrong", (events.first() as CompanionEvent.Error).message)
    }

    @Test
    fun sse_parser_joins_multi_line_data() {
        val p = CompanionLaneSseParser()
        p.onLine("event: error")
        p.onLine("data: first")
        p.onLine("data: second")
        assertEquals(listOf(CompanionEvent.Error("first\nsecond")), p.onLine(""))
    }

    @Test
    fun sse_parser_tolerates_malformed_lines() {
        val p = CompanionLaneSseParser()
        assertTrue(p.onLine("this line has no colon").isEmpty())
        assertTrue(p.onLine("data").isEmpty())
        p.onLine("event: text-delta")
        assertTrue(p.onLine("also-malformed").isEmpty())
        p.onLine("data: ok")
        assertEquals(listOf(CompanionEvent.TextDelta("ok")), p.onLine(""))
    }

    @Test
    fun sse_parser_flushes_unterminated_message_at_end_of_stream() {
        val p = CompanionLaneSseParser()
        p.onLine("event: completed")
        assertEquals(listOf(CompanionEvent.Completed), p.finish())
        assertTrue(p.finish().isEmpty()) // already reset
    }

    @Test
    fun sse_parser_ignores_unknown_event_types() {
        val p = CompanionLaneSseParser()
        p.onLine("event: keep-alive")
        p.onLine("data: ping")
        assertTrue(p.onLine("").isEmpty())
    }

    @Test
    fun client_exposes_request_builder_and_sse_parser() {
        val client = CompanionLaneClient(enabledBuilder)
        val req = client.chatRequest("agent-42")
        assertEquals("Bearer token-abc", req.header("Authorization"))
        client.onSseLine("event: text-delta")
        client.onSseLine("data: delta-text")
        assertEquals(listOf(CompanionEvent.TextDelta("delta-text")), client.onSseLine(""))
    }
}
