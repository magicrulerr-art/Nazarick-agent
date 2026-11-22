package me.rerere.llamacpp

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRequestMapperTest {

    @Test
    fun `a user message becomes an openai shaped message`() {
        val json = JSONObject(
            ChatRequestMapper.toRequestJson(listOf(UIMessage.user("hello")), emptyList())
        )
        val messages = json.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("hello", messages.getJSONObject(0).getString("content"))
    }

    @Test
    fun `no tools means no tools key rather than an empty array`() {
        // An empty tools array makes some templates emit a tool preamble for nothing.
        val json = JSONObject(
            ChatRequestMapper.toRequestJson(listOf(UIMessage.user("hi")), emptyList())
        )
        assertTrue("tools must be absent when none are declared", !json.has("tools"))
    }

    @Test
    fun `tool declaration byte counts match the emitted json`() {
        // The planner budgets on these counts, so they must describe what is really sent.
        val tools = TestTools.two()
        val declarations = ChatRequestMapper.toolDeclarations(tools)
        val emitted = JSONObject(ChatRequestMapper.toRequestJson(listOf(UIMessage.user("x")), tools))
            .getJSONArray("tools")

        assertEquals(tools.size, declarations.size)
        repeat(emitted.length()) { i ->
            val actualBytes = emitted.getJSONObject(i).toString().toByteArray().size
            val claimed = declarations[i].jsonBytes
            // Both call sites run the same private toolsArray() builder over the same
            // tools list, so the true difference is always exactly 0, not merely close.
            // Exact equality catches a future regression that slack would hide, e.g. one
            // call site accidentally routed through a different JSON config.
            assertEquals(
                "declared $claimed bytes but emitted $actualBytes for ${declarations[i].name}",
                claimed,
                actualBytes,
            )
        }
    }

    @Test
    fun `text, an executed tool call, and more text become three ordered messages`() {
        // The tool result must sit between the two assistant turns, not after both, and
        // the two texts must not be glued together into a single assistant message.
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Let me check."),
                UIMessagePart.Tool(
                    toolCallId = "1",
                    toolName = "get_weather",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("sunny")),
                ),
                UIMessagePart.Text("It's sunny."),
            ),
        )

        val messages = JSONObject(
            ChatRequestMapper.toRequestJson(listOf(message), emptyList())
        ).getJSONArray("messages")

        assertEquals(3, messages.length())

        val firstTurn = messages.getJSONObject(0)
        assertEquals("assistant", firstTurn.getString("role"))
        assertEquals("Let me check.", firstTurn.getString("content"))
        val calls = firstTurn.getJSONArray("tool_calls")
        assertEquals(1, calls.length())
        assertEquals("1", calls.getJSONObject(0).getString("id"))
        assertEquals("function", calls.getJSONObject(0).getString("type"))
        val function = calls.getJSONObject(0).getJSONObject("function")
        assertEquals("get_weather", function.getString("name"))
        assertEquals("{}", function.getString("arguments"))

        val toolResult = messages.getJSONObject(1)
        assertEquals("tool", toolResult.getString("role"))
        assertEquals("get_weather", toolResult.getString("name"))
        assertEquals("1", toolResult.getString("tool_call_id"))
        assertEquals("sunny", toolResult.getString("content"))

        val secondTurn = messages.getJSONObject(2)
        assertEquals("assistant", secondTurn.getString("role"))
        assertEquals("It's sunny.", secondTurn.getString("content"))
        assertFalse("second turn called nothing", secondTurn.has("tool_calls"))
    }

    @Test
    fun `an unexecuted tool call is never sent as a tool_calls entry`() {
        // Pending (not-yet-approved) tool calls have no output yet. Sending one in
        // tool_calls with no matching tool message would leave an orphaned
        // tool_call_id, which a strict template rejects the whole conversation for.
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "1",
                    toolName = "get_weather",
                    input = "{}",
                    output = emptyList(),
                ),
            ),
        )

        val json = ChatRequestMapper.toRequestJson(listOf(message), emptyList())
        assertFalse("pending tool call must not appear in the wire format", json.contains("tool_calls"))
        assertFalse("pending tool call must not appear as a tool result", json.contains("\"role\":\"tool\""))
    }

    // enable_thinking — the AURA-008 reasoning knob ------------------------------------

    @Test
    fun `thinking is left out of the request unless the bench asks for otherwise`() {
        // Absent is not neutral: llama.cpp binds enable_thinking to true whether the template
        // wants it or not, so omitting the field *is* thinking. The mapper must never invent the
        // field on its own, or a model's documented default stops meaning anything.
        val json = JSONObject(
            ChatRequestMapper.toRequestJson(listOf(UIMessage.user("hi")), emptyList()),
        )
        assertFalse("enable_thinking must be absent by default", json.has("enable_thinking"))
    }

    @Test
    fun `a tuned reasoning value reaches the wire as a boolean`() {
        for (value in listOf(true, false)) {
            val json = JSONObject(
                ChatRequestMapper.toRequestJson(
                    listOf(UIMessage.user("hi")),
                    emptyList(),
                    enableThinking = value,
                ),
            )
            assertEquals(value, json.getBoolean("enable_thinking"))
        }
    }

    // Bug #5 F1 — reasoning must survive into the next request -------------------------

    @Test
    fun `a reasoning-only assistant turn is echoed with reasoning_content instead of dropped`() {
        // The dominant stall cause: a step that thought but said nothing produced NO
        // assistant message, making the next request byte-identical to the previous one —
        // greedy sampling then replayed the same dead step forever (log hashes 48995=48995).
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning("The user wants the battery level.")),
        )

        val messages = JSONObject(
            ChatRequestMapper.toRequestJson(listOf(message), emptyList())
        ).getJSONArray("messages")

        assertEquals("a thought-only turn must produce a message, not silence", 1, messages.length())
        val turn = messages.getJSONObject(0)
        assertEquals("assistant", turn.getString("role"))
        assertEquals("", turn.getString("content"))
        assertEquals(
            "The user wants the battery level.",
            turn.getString("reasoning_content"),
        )
    }

    @Test
    fun `reasoning is echoed alongside text and survives across a tool boundary`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("Check first, then answer."),
                UIMessagePart.Text("Let me check."),
                UIMessagePart.Tool(
                    toolCallId = "1",
                    toolName = "get_battery",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("87%")),
                ),
                UIMessagePart.Reasoning("Now report it."),
                UIMessagePart.Text("Battery is 87%."),
            ),
        )

        val messages = JSONObject(
            ChatRequestMapper.toRequestJson(listOf(message), emptyList())
        ).getJSONArray("messages")

        // assistant (thought+text+call) / tool / assistant (thought+text)
        assertEquals(3, messages.length())
        assertEquals("Check first, then answer.", messages.getJSONObject(0).getString("reasoning_content"))
        assertEquals("Let me check.", messages.getJSONObject(0).getString("content"))
        assertEquals("tool", messages.getJSONObject(1).getString("role"))
        assertEquals("Now report it.", messages.getJSONObject(2).getString("reasoning_content"))
        assertEquals("Battery is 87%.", messages.getJSONObject(2).getString("content"))
    }

    @Test
    fun `a truly empty assistant turn still emits nothing`() {
        // The F1 echo must not resurrect the blank-message case: no text, no thought,
        // no call → no wire message, exactly as before.
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(""), UIMessagePart.Reasoning("")),
        )

        val messages = JSONObject(
            ChatRequestMapper.toRequestJson(listOf(message), emptyList())
        ).getJSONArray("messages")

        assertEquals(0, messages.length())
    }

    @Test
    fun `a message with no reasoning carries no reasoning_content key`() {
        // Absent, not empty: templates that do know the field must see an unchanged
        // request shape for non-thinking models.
        val messages = JSONObject(
            ChatRequestMapper.toRequestJson(
                listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("plain answer")))),
                emptyList(),
            )
        ).getJSONArray("messages")

        assertEquals(1, messages.length())
        assertFalse(
            "no Reasoning part means no reasoning_content field",
            messages.getJSONObject(0).has("reasoning_content"),
        )
    }
}
