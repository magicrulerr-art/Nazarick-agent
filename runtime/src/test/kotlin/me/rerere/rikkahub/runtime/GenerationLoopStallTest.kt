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
    fun `turn ending on an executed tool envelope with no follow-up text is stalled`() {
        // The turn accumulated one message; the step that just ended produced a tool result
        // and nothing after it. The user sees a tool card and silence — same wound, so it
        // must nudge. (First fix said no; z7 device repro 2026-11-10 proved it wrong.)
        val executed = UIMessagePart.Tool(
            toolCallId = "c1",
            toolName = "memory_lookup",
            input = "{}",
            approvalState = ToolApprovalState.Auto,
            output = listOf(UIMessagePart.Text("result")),
        )
        assertTrue(stalledWithoutReply(msg(parts = listOf(executed))))
    }

    @Test
    fun `pending tool part is not stalled`() {
        val pending = UIMessagePart.Tool(toolCallId = "c1", toolName = "memory_lookup", input = "{}")
        assertFalse(stalledWithoutReply(msg(parts = listOf(pending))))
    }

    // ---- post-tool recurrence (owner report 2026-11-10, "the bug is still there") -----
    // A turn accumulates all steps into ONE assistant message. After web_fetch executed and
    // the next step closed its think block, the message is [Tool(executed), Reasoning] —
    // the executed envelope must NOT count as the current step's progress.

    @Test
    fun `executed tool followed by reasoning-only step is stalled`() {
        val executed = UIMessagePart.Tool(
            toolCallId = "c1",
            toolName = "web_fetch",
            input = "{}",
            approvalState = ToolApprovalState.Auto,
            output = listOf(UIMessagePart.Text("Google search results page")),
        )
        val m = msg(
            parts = listOf(
                executed,
                reasoning("The web fetch returned a Google search page, but it's just the search results page. Let me try searching more specifically."),
            ),
        )
        assertTrue(stalledWithoutReply(m))
    }

    @Test
    fun `executed tool followed by answer text is not stalled`() {
        val executed = UIMessagePart.Tool(
            toolCallId = "c1",
            toolName = "web_fetch",
            input = "{}",
            approvalState = ToolApprovalState.Auto,
            output = listOf(UIMessagePart.Text("Google search results page")),
        )
        val m = msg(
            parts = listOf(
                executed,
                reasoning("Now I have the facts."),
                UIMessagePart.Text("Deep Water Horizon was a 2010 offshore drilling rig disaster."),
            ),
        )
        assertFalse(stalledWithoutReply(m))
    }

    @Test
    fun `executed tool followed by a fresh pending tool is not stalled`() {
        val executed = UIMessagePart.Tool(
            toolCallId = "c1",
            toolName = "web_fetch",
            input = "{}",
            approvalState = ToolApprovalState.Auto,
            output = listOf(UIMessagePart.Text("results")),
        )
        val fresh = UIMessagePart.Tool(toolCallId = "c2", toolName = "web_search", input = "{}")
        assertFalse(stalledWithoutReply(msg(parts = listOf(executed, fresh))))
    }

    @Test
    fun `completed server tool with nothing after is stalled but in-progress is not`() {
        val completed = UIMessagePart.ServerTool(
            toolCallId = "s1",
            toolName = "web_search",
            status = ServerToolStatus.COMPLETED,
        )
        val inProgress = UIMessagePart.ServerTool(
            toolCallId = "s2",
            toolName = "web_search",
            status = ServerToolStatus.IN_PROGRESS,
        )
        assertTrue(stalledWithoutReply(msg(parts = listOf(completed))))
        assertFalse(stalledWithoutReply(msg(parts = listOf(inProgress))))
    }

    @Test
    fun `media parts after an executed tool are not stalled`() {
        val executed = UIMessagePart.Tool(
            toolCallId = "c1",
            toolName = "img_gen",
            input = "{}",
            approvalState = ToolApprovalState.Auto,
            output = listOf(UIMessagePart.Text("saved")),
        )
        assertFalse(stalledWithoutReply(msg(parts = listOf(executed, UIMessagePart.Image("file:///x.png")))))
    }

    @Test
    fun `server tool and media parts alone are not stalled`() {
        val inProgress = UIMessagePart.ServerTool(
            toolCallId = "s1",
            toolName = "web_search",
            status = ServerToolStatus.IN_PROGRESS,
        )
        assertFalse(stalledWithoutReply(msg(parts = listOf(inProgress))))
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
