package me.rerere.llamacpp

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract LC-5 (AURA-021 v1.0): what an over-budget prompt throws out, and in what order.
 * The 2026-11-12 post-mortem is why these exist — a 32 KB persona plus 16 tool schemas
 * leave ~40 KB of input budget on an 8,192-token context, ONE 10-result web_search spends
 * it, and the old trim paid for that payload with whole remembered turns, including the
 * turn carrying the user's own question (AURA-013). A payload should cost its own bytes.
 */
class ContextEvictionTest {

    private fun renderedBytes(messages: List<UIMessage>): Int =
        ChatRequestMapper.toRequestJson(messages, emptyList()).toByteArray(Charsets.UTF_8).size

    private fun toolTurn(id: String, body: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = id,
                toolName = "web_search",
                input = "{\"query\":\"$id\"}",
                output = listOf(UIMessagePart.Text(body)),
            ),
        ),
    )

    private fun toolBodies(message: UIMessage): List<String> =
        message.parts.filterIsInstance<UIMessagePart.Tool>().flatMap { tool ->
            tool.output.filterIsInstance<UIMessagePart.Text>().map { it.text }
        }

    private fun reasoningOf(message: UIMessage): String =
        message.parts.filterIsInstance<UIMessagePart.Reasoning>().joinToString("") { it.reasoning }

    private fun textOf(message: UIMessage): String =
        message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    private fun conversation(): List<UIMessage> = listOf(
        UIMessage.system("persona"),
        UIMessage.user("first question"),
        UIMessage.assistant("first answer"),
        toolTurn("old-call", "stale search payload ".repeat(400)),
        UIMessage.user("second question"),
        toolTurn("live-call", "fresh search payload ".repeat(20)),
    )

    @Test
    fun `a prompt inside the budget is untouched`() {
        val messages = conversation()
        assertEquals(messages, ChatRequestMapper.evictToBudget(messages, budgetBytes = 100_000))
    }

    @Test
    fun `an oversized stale tool payload is evicted before any turn disappears`() {
        val messages = conversation()
        // A budget the conversation can only reach by giving up the old search body:
        // 2 KB over what remains once that 8.4 KB payload is stubbed out.
        val budget = renderedBytes(messages) - 2_000

        val evicted = ChatRequestMapper.evictToBudget(messages, budgetBytes = budget)

        assertEquals("no turn may be deleted while evictable bodies remain", messages.size, evicted.size)
        assertEquals("the persona is trimToBudget's business, not eviction's", messages[0], evicted[0])
        assertEquals(messages[1], evicted[1])
        assertEquals(messages[2], evicted[2])
        assertEquals(messages[4], evicted[4])
        assertTrue(
            "the oldest tool body is what pays",
            toolBodies(evicted[3]).all { it == ChatRequestMapper.EVICTED_TOOL_BODY },
        )
        assertTrue(
            "the live step's own evidence survives",
            toolBodies(evicted.last()).any { it.startsWith("fresh search payload") },
        )
        assertTrue("the prompt must actually fit now", renderedBytes(evicted) <= budget)
    }

    @Test
    fun `the envelope of an evicted call survives so the template stays valid`() {
        val messages = conversation()
        val evicted = ChatRequestMapper.evictToBudget(
            messages,
            budgetBytes = renderedBytes(messages) - 2_000,
        )
        val rendered = JSONObject(ChatRequestMapper.toRequestJson(evicted, emptyList()))
            .getJSONArray("messages")

        val callIds = mutableListOf<String>()
        val resultIds = mutableListOf<String>()
        for (i in 0 until rendered.length()) {
            val entry = rendered.getJSONObject(i)
            when (entry.getString("role")) {
                "assistant" -> entry.optJSONArray("tool_calls")?.let { calls ->
                    for (c in 0 until calls.length()) {
                        callIds += calls.getJSONObject(c).getString("id")
                    }
                }
                "tool" -> resultIds += entry.getString("tool_call_id")
            }
        }
        assertTrue("the fixture must still exercise a tool round-trip", callIds.isNotEmpty())
        assertEquals("every tool_call keeps its tool message", callIds.sorted(), resultIds.sorted())
    }

    @Test
    fun `superseded reasoning is compacted only after every older tool body is gone`() {
        val superseded = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("a".repeat(6_000)),
                UIMessagePart.Text("older answer"),
            ),
        )
        val messages = listOf(
            UIMessage.system("persona"),
            UIMessage.user("q1"),
            superseded,
            toolTurn("old-call", "s".repeat(1_200)),
            UIMessage.user("q2"),
            UIMessage.assistant("answering now"),
        )
        // Bigger than the 1.2 KB tool body alone can pay, small enough that digesting the
        // old thinking closes the gap: proves the order, not just the endpoints.
        val budget = renderedBytes(messages) - 3_000

        val evicted = ChatRequestMapper.evictToBudget(messages, budgetBytes = budget)

        assertTrue(
            "the older tool body goes first",
            toolBodies(evicted[3]).all { it == ChatRequestMapper.EVICTED_TOOL_BODY },
        )
        val kept = reasoningOf(evicted[2])
        assertTrue("superseded thinking is compacted, not erased", kept.isNotBlank())
        assertTrue("the head of the thought survives", kept.startsWith("aaaa"))
        assertTrue("a compacted thought says so", kept.contains("compacted"))
        assertTrue("compaction must actually shrink", kept.length < 6_000)
        assertEquals("the superseded answer text survives", "older answer", textOf(evicted[2]))
        assertEquals("the live turn is untouched", messages.last(), evicted.last())
        assertTrue(renderedBytes(evicted) <= budget)
    }

    @Test
    fun `the live step and the newest user message are never evictable`() {
        val liveStep = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("current thought ".repeat(200)),
                UIMessagePart.Tool(
                    toolCallId = "live",
                    toolName = "web_search",
                    input = "{\"query\":\"live\"}",
                    output = listOf(UIMessagePart.Text("live payload ".repeat(200))),
                ),
            ),
        )
        val messages = listOf(
            UIMessage.system("persona"),
            UIMessage.user("older question"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning("older thought ".repeat(200)),
                    UIMessagePart.Text("older answer"),
                ),
            ),
            UIMessage.user("newest question"),
            liveStep,
        )

        val evicted = ChatRequestMapper.evictToBudget(messages, budgetBytes = 1)

        assertEquals("the newest user message survives verbatim", messages[3], evicted[3])
        assertEquals("the live step survives verbatim", liveStep, evicted.last())
        assertTrue(
            "superseded thinking is the last resort and may vanish",
            reasoningOf(evicted[2]).isEmpty(),
        )
        assertEquals("its answer text is not evictable material", "older answer", textOf(evicted[2]))
    }

    @Test
    fun `eviction only shrinks and never leaves a bare empty payload`() {
        val messages = conversation()
        val evicted = ChatRequestMapper.evictToBudget(messages, budgetBytes = 10)

        assertTrue("eviction can only shrink", renderedBytes(evicted) <= renderedBytes(messages))
        val bodies = toolBodies(evicted[3])
        assertTrue("an evicted body carries the marker, not an empty string", bodies.isNotEmpty())
        assertFalse("no zero-length tool payload reaches the template", bodies.any { it.isEmpty() })
    }
}
