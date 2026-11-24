package me.rerere.ai.provider.providers.nazarick

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * US-002: the wire branch inside `NazarickProvider.streamText` behind `companion_enabled`,
 * pinned at the builder level (pure JVM, no Android, no socket — same lane as
 * CompanionTurnTest.kt).
 *
 * ON  — the request streamText builds comes from [companionTurnRequest]: companion URL +
 *       X-Companion-* envelope headers.
 * OFF — the untouched console assembly (`nazarickRequest` + [NazarickClient.CHAT_PATH]):
 *       console URL, no companion headers — the byte-identical pre-US-002 request.
 */
class NazarickCompanionBranchTest {

    private val messages = listOf(
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("ping companion"))),
    )

    private fun setting(enabled: Boolean, companionAgentId: String = "companion-x") =
        ProviderSetting.Nazarick(
            baseUrl = "http://192.168.1.10:8088",
            token = "tok",
            agentId = "agent-a",
            companion_enabled = enabled,
            companion_agent_id = companionAgentId,
            companion_device_id = "z7",
        )

    private fun bodyOf(request: okhttp3.Request): String {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return buffer.readUtf8()
    }

    @Test
    fun `flag on builds companion url and companion envelope headers`() {
        val request = companionTurnRequest(
            settings = setting(enabled = true),
            messages = messages,
            sessionId = "app-device-chat9",
            deviceModel = "SM-F966B",
            deviceOs = "Android 16",
        )
        assertEquals(
            "http://192.168.1.10:8088/api/companion/agents/companion-x/chat",
            request.url.toString(),
        )
        assertEquals("POST", request.method)
        assertEquals("z7", request.header("X-Companion-Device"))
        assertEquals("SM-F966B", request.header("X-Companion-Model"))
        assertEquals("Android 16", request.header("X-Companion-Os"))
        assertEquals("Bearer tok", request.header("Authorization"))
        assertEquals("text/event-stream", request.header("Accept"))
        assertNull(
            "companion lane addresses the agent by URL path, not X-Agent-Id",
            request.header("X-Agent-Id"),
        )
        val body = bodyOf(request)
        assertTrue("body carries the user text", body.contains("ping companion"))
        assertTrue(
            "body carries the namespaced chat_id",
            body.contains("\"chat_id\":\"app-device-chat9\""),
        )
    }

    @Test
    fun `blank companion_agent_id falls back to console agentId in the companion url`() {
        val request = companionTurnRequest(
            settings = setting(enabled = true, companionAgentId = ""),
            messages = messages,
            sessionId = "s1",
            deviceModel = "m",
            deviceOs = "o",
        )
        assertEquals(
            "http://192.168.1.10:8088/api/companion/agents/agent-a/chat",
            request.url.toString(),
        )
    }

    @Test
    fun `flag off keeps the console url and sends no companion headers`() {
        val s = setting(enabled = false)
        // Exactly the untouched OFF assembly streamText performs:
        val url = nazarickUrl(s.baseUrl, NazarickClient.CHAT_PATH)
            ?: error("test baseUrl must parse")
        val request = nazarickRequest(
            url = url,
            token = s.token,
            agentId = s.agentId,
            body = "{}".toRequestBody("application/json".toMediaType()),
        )
        assertEquals("http://192.168.1.10:8088/api/console/chat", request.url.toString())
        assertNull("OFF lane must not send the companion envelope", request.header("X-Companion-Device"))
        assertEquals("Bearer tok", request.header("Authorization"))
        assertEquals("agent-a", request.header("X-Agent-Id"))
    }
}
