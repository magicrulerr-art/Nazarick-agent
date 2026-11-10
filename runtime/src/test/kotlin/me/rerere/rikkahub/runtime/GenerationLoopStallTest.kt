package me.rerere.rikkahub.runtime

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-predicate tests for the bug #5 stall check ("model stops mid thought-stream", owner
 * report 2026-11-10 with the LFM3-VL screenshot: a thinking block ending in "let me check..."
 * and nothing else, provider reporting a clean stop).
 *
 * [stalledWithoutReply] decides whether the loop auto-nudges such a step instead of breaking.
 * The nudge itself rides the next request as a plain instruction string
 * ([STALL_NUDGE_INSTRUCTION]) and is budgeted by [MAX_STALL_NUDGES].
 */
class GenerationLoopStallTest {
    private fun msg(
        role: MessageRole = MessageRole.ASSISTANT,
        parts: List<UIMessagePart>,
    ) = UIMessage(role = role, parts = parts)

    private fun reasoning(text: String) =
        UIMessagePart.Reasoning(reasoning = text)

    // ---- the bug #5 shape ------------------------------------------------------

    @Test
    fun `reasoning-only assistant step is stalled`() {
        // The exact screenshot: think block streamed, model closed it, emitted nothing else.
        val m = msg(parts = listOf(reasoning("The memory lookup returned no results. Let me search the conversation history or check for recent mentions.")))
        assertTrue(stalledWithoutReply(m))
    }

    @Test
    fun `empty assistant step is stalled`() {
        assertTrue(stalledWithoutReply(msg(parts = emptyList())))
    }

    @Test
    fun `whitespace-only text is stalled`() {
        assertTrue(stalledWithoutReply(msg(parts = listOf(UIMessagePart.Text("  \n")))))
    }

    // ---- things that must NOT trigger a nudge ---------------------------------

    @Test
    fun `assistant step with visible text is not stalled`() {
        assertFalse(stalledWithoutReply(msg(parts = listOf(UIMessagePart.Text("Here is what happened.")))))
    }

    @Test
    fun `reasoning plus text is not stalled`() {
        val m = msg(parts = listOf(reasoning("thinking..."), UIMessagePart.Text("answer")))
        assertFalse(stalledWithoutReply(m))
    }

    @Test
    fun `tool part is not stalled whether executed or not`() {
        val pending = UIMessagePart.Tool(toolCallId = "c1", toolName = "memory_lookup", input = "{}")
        val executed = pending.copy(
            approvalState = ToolApprovalState.Auto,
            output = listOf(UIMessagePart.Text("result")),
        )
        assertFalse(stalledWithoutReply(msg(parts = listOf(pending))))
        assertFalse(stalledWithoutReply(msg(parts = listOf(executed))))
    }

    @Test
    fun `server tool and media parts are not stalled`() {
        val serverTool = UIMessagePart.ServerTool(
            toolCallId = "s1",
            toolName = "web_search",
            status = ServerToolStatus.COMPLETED,
        )
        assertFalse(stalledWithoutReply(msg(parts = listOf(serverTool))))
        assertFalse(stalledWithoutReply(msg(parts = listOf(UIMessagePart.Image("file:///x.png")))))
        assertFalse(stalledWithoutReply(msg(parts = listOf(UIMessagePart.Document("file:///d.pdf", fileName = "d.pdf")))))
    }

    @Test
    fun `null and non-assistant messages are not stalled`() {
        assertFalse(stalledWithoutReply(null))
        assertFalse(stalledWithoutReply(msg(role = MessageRole.USER, parts = emptyList())))
    }

    // ---- instruction sanity ----------------------------------------------------

    @Test
    fun `nudge instruction demands action and budget is small`() {
        // Request-only wording: must forbid ending on thinking again.
        assertTrue(STALL_NUDGE_INSTRUCTION.contains("tool call", ignoreCase = true))
        assertTrue(STALL_NUDGE_INSTRUCTION.contains("answer", ignoreCase = true))
        assertEquals(2, MAX_STALL_NUDGES)
    }
}
