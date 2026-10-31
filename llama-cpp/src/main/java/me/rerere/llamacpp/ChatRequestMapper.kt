package me.rerere.llamacpp

import kotlinx.serialization.json.Json
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts the app's messages and tools into the OpenAI-shaped request that
 * llama.cpp's chat template layer consumes. The shape is deliberately the same one the
 * app already builds for Chat Completions, so there is one wire format to reason about.
 */
object ChatRequestMapper {

    /** Floor for how much system text survives [trimToBudget], even when the input budget is
     *  already spent on tools. Sized to hold a one-paragraph identity, not a persona library.
     *  Shared with [ContextPlanner.SYSTEM_PROMPT_RESERVE_BYTES] because that is the figure the
     *  planner charges a persona against: if the two disagree, the planner reserves space this
     *  truncation never consumes and drops tools to pay for it.
     *
     *  Was `const` until AURA-008 made that figure a per-load knob; a property rather than a
     *  constant is what keeps the two halves of one decision from drifting apart again. */
    private val MIN_SYSTEM_TEXT_BYTES: Int get() = ContextPlanner.SYSTEM_PROMPT_RESERVE_BYTES

    /** Appended to system text [trimToBudget] cut, so the model can tell a short persona from
     *  a truncated one. Same wording LiteRtProvider uses. */
    private const val TRUNCATION_MARKER = "\n…(truncated for local model context)"

    fun toRequestJson(
        messages: List<UIMessage>,
        tools: List<Tool>,
        enableThinking: Boolean? = null,
    ): String {
        val root = JSONObject()
        root.put("messages", messagesArray(messages))
        // Absent rather than empty: an empty array makes some templates emit a tool
        // preamble describing no tools at all.
        if (tools.isNotEmpty()) {
            root.put("tools", toolsArray(tools))
        }
        // Absent unless the bench asked for something specific. Omitting it is not "let the
        // template decide" — llama.cpp's chat inputs carry enable_thinking = true and always
        // bind the variable, so an absent field means thinking. Setting false makes the
        // template write an empty thinking block and go straight to the answer, which is the
        // prefill/latency experiment [EngineTunables.reasoning] exists to run.
        if (enableThinking != null) {
            root.put("enable_thinking", enableThinking)
        }
        return root.toString()
    }

    /**
     * Drops the oldest turns until the rendered conversation fits [budgetBytes], and, once
     * no history is left to drop, shortens the system prompt to what remains.
     *
     * The newest non-system turn is always kept, even if it alone exceeds the budget:
     * sending a conversation with no user turn breaks the chat template, and an
     * overflowing prompt that produces a readable error beats that.
     *
     * System messages are trimmed rather than exempt. They were exempt on the theory that
     * the planner already capped them, but nothing caps them: an assistant with the bundled
     * agent-core skill carries ~31 KB of persona prose, which on an 8,192-token local
     * context is the whole budget by itself, so a "hi" turned into a 10k-token prefill the
     * engine needed ten minutes to chew through. [MIN_SYSTEM_TEXT_BYTES] keeps the persona
     * from vanishing entirely when the budget is already spent, mirroring
     * LiteRtProvider's SYSTEM_MESSAGE_CHAR_BUDGET for the same reason - a small local model
     * cannot hold thousands of tokens of persona instructions in mind anyway.
     */
    fun trimToBudget(messages: List<UIMessage>, budgetBytes: Int): List<UIMessage> {
        if (messages.isEmpty()) return messages

        fun sizeOf(list: List<UIMessage>): Int =
            messagesArray(list).toString().toByteArray().size

        var working = messages
        // Stop once only one non-system turn remains: that survivor is always the
        // newest one, since each iteration only ever removes the oldest, and it must
        // never be dropped even if it alone pushes the request over budget.
        var droppableCount = working.count { it.role != MessageRole.SYSTEM }
        while (droppableCount > 1 && sizeOf(working) > budgetBytes) {
            // Drop the oldest non-system turn.
            val index = working.indexOfFirst { it.role != MessageRole.SYSTEM }
            if (index < 0) break
            working = working.toMutableList().apply { removeAt(index) }
            droppableCount--
        }

        // History is down to its last turn and the request still does not fit, so the
        // system prompt is the oversized part. Give it whatever the budget has left after
        // the surviving turns and cut it there.
        if (sizeOf(working) > budgetBytes) {
            val keptBytes = sizeOf(working.filter { it.role != MessageRole.SYSTEM })
            val allowance = (budgetBytes - keptBytes).coerceAtLeast(MIN_SYSTEM_TEXT_BYTES)
            working = working.map { message ->
                if (message.role == MessageRole.SYSTEM) truncateSystemText(message, allowance) else message
            }
        }
        return working
    }

    /** Truncate [message]'s text parts to a combined [allowanceBytes], keeping the head. */
    private fun truncateSystemText(message: UIMessage, allowanceBytes: Int): UIMessage {
        if (allowanceBytes <= 0) return message
        var remaining = allowanceBytes
        val parts = message.parts.map { part ->
            if (part !is UIMessagePart.Text) return@map part
            if (remaining <= 0) return@map part.copy(text = "")
            val bytes = part.text.toByteArray(Charsets.UTF_8)
            if (bytes.size <= remaining) {
                remaining -= bytes.size
                return@map part
            }
            // Cut on a codepoint boundary: a UTF-8 continuation byte (10xxxxxx) at the cut
            // point would otherwise emit a mojibake character at the truncation seam.
            var cut = remaining
            while (cut > 0 && (bytes[cut].toInt() and 0xC0) == 0x80) cut--
            part.copy(text = bytes.decodeToString(0, cut) + TRUNCATION_MARKER)
        }
        return message.copy(parts = parts)
    }

    fun toolDeclarations(tools: List<Tool>): List<ToolDeclaration> {
        val array = toolsArray(tools)
        return tools.mapIndexed { index, tool ->
            ToolDeclaration(
                name = tool.name,
                jsonBytes = array.getJSONObject(index).toString().toByteArray().size,
            )
        }
    }

    private fun messagesArray(messages: List<UIMessage>): JSONArray {
        val array = JSONArray()
        for (message in messages) {
            if (message.role == MessageRole.ASSISTANT) {
                addAssistantMessages(array, message)
            } else {
                addPlainMessage(array, message)
            }
        }
        return array
    }

    private fun addPlainMessage(array: JSONArray, message: UIMessage) {
        val role = when (message.role) {
            MessageRole.USER -> "user"
            MessageRole.SYSTEM -> "system"
            MessageRole.TOOL -> "tool"
            MessageRole.ASSISTANT -> "assistant" // handled by addAssistantMessages
        }
        val text = message.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }
        array.put(JSONObject().put("role", role).put("content", text))
    }

    /**
     * Splits an assistant message on tool boundaries using the same grouping the app's
     * own Chat Completions provider uses, so a turn that talks, calls a tool, then talks
     * again becomes three messages (assistant, tool, assistant) with the tool result
     * sitting between the two assistant turns, instead of one message that glues both
     * texts together and announces the call before the result exists. Also guarantees
     * every entry in a group's tool_calls has a matching tool message, because
     * [groupPartsByToolBoundary] only ever groups executed tools into [PartGroup.Tools];
     * an unexecuted (pending-approval) tool part lands in [PartGroup.Content] instead and
     * is silently dropped, exactly like the reference does.
     */
    private fun addAssistantMessages(array: JSONArray, message: UIMessage) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<UIMessagePart>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    contentBuffer += group.parts
                }

                is PartGroup.Tools -> {
                    assistantMessage(contentBuffer, group.tools)?.let { array.put(it) }
                    contentBuffer.clear()
                    for (tool in group.tools) {
                        array.put(toolResultMessage(tool))
                    }
                }
            }
        }

        // Trailing text after the last tool call, or the whole message when it never
        // called a tool at all.
        assistantMessage(contentBuffer, emptyList())?.let { array.put(it) }
    }

    /** Null when there is nothing to say and nothing was called, matching the reference. */
    private fun assistantMessage(contentParts: List<UIMessagePart>, tools: List<UIMessagePart.Tool>): JSONObject? {
        val text = contentParts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
        if (text.isBlank() && tools.isEmpty()) return null

        val obj = JSONObject().put("role", "assistant").put("content", text)
        if (tools.isNotEmpty()) {
            val calls = JSONArray()
            for (part in tools) {
                calls.put(
                    JSONObject()
                        .put("id", part.toolCallId)
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", part.toolName)
                                // Normalized the same way ChatCompletionsAPI does, so a
                                // stream cut mid-argument never sends broken JSON.
                                .put("arguments", part.inputAsJson().toString()),
                        )
                )
            }
            obj.put("tool_calls", calls)
        }
        return obj
    }

    private fun toolResultMessage(tool: UIMessagePart.Tool): JSONObject {
        val output = tool.output
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }
        return JSONObject()
            .put("role", "tool")
            .put("name", tool.toolName)
            .put("tool_call_id", tool.toolCallId)
            .put("content", output)
    }

    private fun toolsArray(tools: List<Tool>): JSONArray {
        val array = JSONArray()
        for (tool in tools) {
            // parameters is a lambda returning InputSchema?, and InputSchema is
            // @Serializable, so encode it with kotlinx and re-read it as org.json to
            // keep one JSON object model in this file.
            val schema = tool.parameters()
            val schemaJson = if (schema == null) {
                JSONObject().put("type", "object").put("properties", JSONObject())
            } else {
                JSONObject(Json.encodeToString(InputSchema.serializer(), schema))
            }
            array.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", tool.name)
                            .put("description", tool.description)
                            .put("parameters", schemaJson)
                    )
            )
        }
        return array
    }
}
