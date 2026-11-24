package me.rerere.ai.companion

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * US-002: companion lane turn pipeline behind flag.
 *
 * Pure JVM tests: no OkHttp, no Android, no socket or device context.
 * Real SSE frames are embedded verbatim from z7_real_turn.sse
 * (mission-20261123-061238 evidence), keeping the "data:" prefix exactly as
 * CompanionTurn.kt expects it — the parser strips the prefix itself.
 */
class CompanionTurnTest {

    // Verbatim data line from z7_real_turn.sse lines 1-20 (sequence_number 4).
    private val deltaFrame =
        """data: {"type": "text", "delta": true, "index": 0, "status": null, "object": "content", "msg_id": "msg_fc97f75f0797459fa469a6efdccd98a4", "text": "The", "sequence_number": 4}"""

    // Verbatim data line from the tail of z7_real_turn.sse (sequence_number 50,
    // response status=completed). Prefix kept; CompanionTurn.kt strips it.
    private val completedFrame =
        """data: {"id":"response_839d7ec75ab54d3995eeaaa07f8ce660","output":[{"id":"msg_fc97f75f0797459fa469a6efdccd98a4","type":"reasoning","role":"assistant","content":[{"type":"text","delta":false,"index":0,"status":null,"object":"content","msg_id":null,"text":"The user is asking me to identify what device this turn is from, based on the envelope metadata provided in the message. The envelope says:\n\n`[companion-device-envelope] device=z7 model=SM-F966B os=Android 16 (channel-side metadata, not user text)`\n\nSo the device is z7, model SM-F966B, OS Android 16.\n\nI should answer directly without using tools, as requested (\"Without tools\")."}],"status":"completed","metadata":null,"name":"assistant","object":"message","sequence_number":33},{"id":"msg_e93cb686a4f449f49068c93848d4b7dd","type":"message","role":"assistant","content":[{"type":"text","delta":false,"index":0,"status":null,"object":"content","msg_id":null,"text":"This turn is from device **z7**.\n\nQuoted envelope:\n\n`[companion-device-envelope] device=z7 model=SM-F966B os=Android 16 (channel-side metadata, not user text)`"}],"status":"completed","metadata":null,"name":"assistant","object":"message","sequence_number":49,"usage":{"input_tokens":13051,"output_tokens":152}}],"status":"completed","created_at":"2026-11-23T20:34:19+00:00","completed_at":"2026-11-23T20:34:37+00:00","metadata":{"qwenpaw_model_completion":{"finish_reason":"stop","truncated":false}},"object":"response","session_id":"app-z7-z7-ci-proof","sequence_number":50,"usage":{"input_tokens":13051,"output_tokens":152}}"""

    @Test
    fun `companionEventsToChunks maps garbage plus real frames to nonempty chunks ending in completed Finish`() {
        val chunks = companionEventsToChunks(listOf("garbage", deltaFrame, completedFrame))
        assertFalse("result must be nonempty", chunks.isEmpty())
        assertTrue("at least one TextDelta", chunks.any { it is StreamChunk.TextDelta })
        val last = chunks.lastOrNull()
        assertNotNull("last chunk must exist", last)
        assertTrue("last chunk must be StreamChunk.Finish", last is StreamChunk.Finish)
        assertEquals("finishReason must be completed", "completed", (last as StreamChunk.Finish).finishReason)
    }

    @Test
    fun `companionHeaders sets device header and bearer authorization`() {
        val headers = companionHeaders("z7", "m", "o", "t")
        assertEquals("z7", headers["X-Companion-Device"])
        assertEquals("m", headers["X-Companion-Model"])
        assertEquals("o", headers["X-Companion-Os"])
        assertEquals("Bearer t", headers["Authorization"])
    }

    @Test
    fun `buildCompanionTurnBody contains user text and chat id`() {
        val body = buildCompanionTurnBody(
            listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("HELLO")))),
            "chat9",
            ProviderSetting.Nazarick(),
        )
        assertTrue("body must contain HELLO", body.contains("HELLO"))
        assertTrue("body must contain chat9", body.contains("chat9"))
    }
}
