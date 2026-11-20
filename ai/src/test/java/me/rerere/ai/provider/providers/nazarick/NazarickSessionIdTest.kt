package me.rerere.ai.provider.providers.nazarick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * US-003 session-namespacing leg: [appSessionId] must always wear the `app-` prefix
 * (so app sessions never collide with the server's `default`), be stable across calls,
 * and sanitize anything outside `[a-z0-9-]` out of the id.
 */
class NazarickSessionIdTest {

    @Test
    fun `session id always carries the app- prefix`() {
        assertTrue(appSessionId("pixel8", "chat1").startsWith("app-"))
        assertTrue(appSessionId("", "").startsWith("app-"))
        assertTrue(appSessionId("!!!", "???").startsWith("app-"))
    }

    @Test
    fun `session id is stable for repeated inputs`() {
        val a = appSessionId("pixel-8", "thread-42")
        val b = appSessionId("pixel-8", "thread-42")
        assertEquals(a, b)
        assertEquals("app-pixel-8-thread-42", a)
    }

    @Test
    fun `distinct chats and devices never collapse onto one another`() {
        assertTrue(appSessionId("pixel8", "chat1") != appSessionId("pixel8", "chat2"))
        assertTrue(appSessionId("pixel8", "chat1") != appSessionId("pixel9", "chat1"))
    }

    @Test
    fun `non-alphanumerics are sanitized to single separators and edges trimmed`() {
        assertEquals("app-my-phone-chat-42", appSessionId("My Phone!!", "chat 42"))
        assertEquals("app-a-b", appSessionId("a__b", "-"))
        assertEquals("app-ab-c", appSessionId("-  ab  -", "c!"))
        assertEquals("app-d-e-v-chat", appSessionId("D E V", "  chat  "))
    }

    @Test
    fun `sanitize part is idempotent`() {
        for (raw in listOf("My Phone!!", "a__b", "-x-", "MiXeD_CaSe 9", "日本語 x")) {
            val once = sanitizeSessionPart(raw)
            assertEquals(once, sanitizeSessionPart(once))
        }
    }

    @Test
    fun `sanitize part keeps only safe alphabet`() {
        val cleaned = sanitizeSessionPart("Z9 --__..  x!/@#")
        assertTrue(cleaned, cleaned.matches(Regex("^[a-z0-9]+(-[a-z0-9]+)*$")))
    }
}
