package me.rerere.llamacpp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
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

    /** Left where an evicted tool payload used to sit. The envelope survives (see
     *  [evictToBudget]); only the body is replaced, so a template can still tell the
     *  model "this call ran and its output is no longer in context". */
    const val EVICTED_TOOL_BODY = "\u2026(tool output evicted to fit context)"

    /** Head kept when superseded reasoning is compacted rather than dropped whole. */
    private const val REASONING_DIGEST_BYTES = 240

    private const val REASONING_DIGEST_MARKER = "\u2026(earlier thinking compacted)"

    /**
     * Contract LC-5 (AURA-021 v1.0): pay for an oversized prompt with the bytes that
     * caused it, in this order, BEFORE whole turns disappear:
     *
     *  1. never evictable - the newest user message and everything after it, which is
     *     the live step: its reasoning is what the model is thinking right now, and its
     *     tool outputs are the evidence it has to answer with. Evicting a live step's
     *     reasoning is the Bug #5 stall (a request byte-identical to the one that just
     *     produced it, and greedy sampling replays the same dead step forever).
     *  2. oldest tool-result BODIES first - body only, never the envelope: every
     *     tool_call must keep its matching tool message or the chat template rejects
     *     the request outright.
     *  3. superseded reasoning LAST - compacted to its head plus a marker, then dropped
     *     entirely if the prompt still does not fit.
     *
     * The cheap tier of summarize-then-drop: this costs neither a turn nor a model call.
     * The LLM-written summary is ContextCompactionPlanner's job (runtime module) and
     * stays there; a request that still does not fit afterwards goes to
     * [trimToBudget], which drops whole user-anchored groups.
     *
     * Returns the input untouched when it already fits, and always returns a list that
     * renders to the same or fewer bytes than it was given.
     */
    fun evictToBudget(messages: List<UIMessage>, budgetBytes: Int): List<UIMessage> {
        if (messages.isEmpty()) return messages
        var working = messages
        if (sizeOfJson(working) <= budgetBytes) return working

        // Everything from the newest user message onward is the live step: untouchable.
        val liveStart = messages.indexOfLast { it.role == MessageRole.USER }
            .let { if (it < 0) messages.size else it }

        // (2) oldest tool bodies first
        var index = 0
        while (index < liveStart && sizeOfJson(working) > budgetBytes) {
            val evicted = evictToolBody(working[index])
            if (evicted != null) working = working.toMutableList().also { it[index] = evicted }
            index++
        }

        // (3a) superseded reasoning -> digest head
        index = 0
        while (index < liveStart && sizeOfJson(working) > budgetBytes) {
            val compacted = compactReasoning(working[index], REASONING_DIGEST_BYTES)
            if (compacted != null) working = working.toMutableList().also { it[index] = compacted }
            index++
        }

        // (3b) ...then drop it entirely
        index = 0
        while (index < liveStart && sizeOfJson(working) > budgetBytes) {
            val dropped = compactReasoning(working[index], 0)
            if (dropped != null) working = working.toMutableList().also { it[index] = dropped }
            index++
        }
        return working
    }

    /**
     * Replaces one message's executed tool payloads with [EVICTED_TOOL_BODY]; null when
     * the message carries no body worth evicting (nothing to do, or already evicted).
     * The call envelope (toolCallId, toolName, arguments) is kept on purpose: a
     * tool_call without its result message is a malformed request, not a smaller one.
     */
    @Suppress("DEPRECATION")
    private fun evictToolBody(message: UIMessage): UIMessage? {
        if (message.role == MessageRole.TOOL) {
            val texts = message.parts.filterIsInstance<UIMessagePart.Text>()
            if (texts.isEmpty() || texts.all { it.text == EVICTED_TOOL_BODY }) return null
            return message.copy(
                parts = message.parts.map { part ->
                    if (part is UIMessagePart.Text) part.copy(text = EVICTED_TOOL_BODY) else part
                },
            )
        }
        var changed = false
        val parts = message.parts.map { part ->
            when {
                part is UIMessagePart.Tool && part.output.isNotEmpty() -> {
                    changed = true
                    part.copy(output = listOf(UIMessagePart.Text(EVICTED_TOOL_BODY)))
                }
                part is UIMessagePart.ServerTool && part.output != null -> {
                    changed = true
                    part.copy(output = JsonPrimitive(EVICTED_TOOL_BODY))
                }
                part is UIMessagePart.ToolResult -> {
                    changed = true
                    part.copy(content = JsonPrimitive(EVICTED_TOOL_BODY))
                }
                else -> part
            }
        }
        return if (changed) message.copy(parts = parts) else null
    }

    /**
     * Compacts one message's thinking to a [digestBytes] head (0 = drop it). Null when the
     * message has no reasoning or already sits in the requested shape. Only ever called on
     * superseded turns: the live step is excluded by [evictToBudget].
     */
    private fun compactReasoning(message: UIMessage, digestBytes: Int): UIMessage? {
        if (message.parts.none { it is UIMessagePart.Reasoning }) return null
        var changed = false
        val parts = message.parts.map { part ->
            if (part !is UIMessagePart.Reasoning) return@map part
            val kept = digestText(part.reasoning, digestBytes)
            if (kept == part.reasoning) return@map part
            changed = true
            part.copy(reasoning = kept)
        }
        return if (changed) message.copy(parts = parts) else null
    }

    /** Head of [text] at [limit] UTF-8 bytes plus a marker; whole text when it already fits,
     *  empty string when [limit] is 0. Cuts on a codepoint boundary so the seam is never
     *  mojibake. */
    private fun digestText(text: String, limit: Int): String {
        if (limit <= 0) return ""
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= limit) return text
        var cut = limit
        while (cut > 0 && (bytes[cut].toInt() and 0xC0) == 0x80) cut--
        return bytes.decodeToString(0, cut) + REASONING_DIGEST_MARKER
    }

    /** Rendered request bytes for [messages] with no tools - the same yardstick
     *  [trimToBudget] uses, so eviction and group-dropping can never disagree about size. */
    private fun sizeOfJson(messages: List<UIMessage>): Int =
        messagesArray(messages).toString().toByteArray(Charsets.UTF_8).size

    /**
     * Drops the oldest turn groups until the rendered conversation fits [budgetBytes],
     * and, once no history is left to drop, shortens the system prompt to what remains.
     *
     * Turns are dropped in whole user-anchored groups: each user message keeps the
     * assistant/tool turns that answer it in the same group, and a group leaves only as
     * a unit. The old turn-by-turn drop removed the oldest non-system message first,
     * which on a post-tool step is the user's own question, orphaning
     * [assistant+tool_calls, tool] with no query behind them. Qwen3.5's chat template
     * scans for a user-role message that is not tool feedback (role:"tool" is invisible
     * to its scan) and raises "No user query found in messages." when it finds none -
     * measured on z8 2026-11-12, where a 32 KB persona and 16 tool schemas left ~40 KB
     * of input budget and one 10-result web_search spent it, so the follow-up step
     * after every search died at template line 79 while LFM3 sailed past the same
     * query-less request silently. A conversation with no user-role message at all
     * keeps the old turn-by-turn behaviour; so does one that begins with orphaned
     * non-user turns, which no grouping rule can honestly anchor.
     *
     * The newest group is always kept, even if it alone exceeds the budget: sending a
     * conversation with no user turn breaks the chat template, and an overflowing
     * prompt that produces a readable error beats that.
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

        fun sizeOf(list: List<UIMessage>): Int = sizeOfJson(list)

        // LC-5 first: stale tool payloads and superseded thinking give up their
        // bytes before any remembered turn is deleted outright.
        var working = evictToBudget(messages, budgetBytes)

        val groups = userAnchoredGroups(messages)
        if (groups != null) {
            // Qwen3.5 and friends accept the system block nowhere but messages[0], so
            // every rebuild puts the system messages back out front regardless of where
            // the caller happened to carry them.
            val systemMessages = messages.filter { it.role == MessageRole.SYSTEM }
            fun rebuild(kept: List<List<UIMessage>>): List<UIMessage> = systemMessages + kept.flatten()

            // Drop whole groups, oldest first. Stop at the newest group: it carries the
            // live user query and may not vanish even when it alone exceeds the budget.
            val kept = groups.toMutableList()
            while (kept.size > 1 && sizeOf(rebuild(kept)) > budgetBytes) {
                kept.removeAt(0)
            }
            working = rebuild(kept)
        } else {
            // No user-role anchor exists (or the conversation opens on orphaned turns):
            // stop once only one non-system turn remains, the pre-grouping behaviour,
            // kept honest for the few requests it can actually describe.
            var droppableCount = working.count { it.role != MessageRole.SYSTEM }
            while (droppableCount > 1 && sizeOf(working) > budgetBytes) {
                // Drop the oldest non-system turn.
                val index = working.indexOfFirst { it.role != MessageRole.SYSTEM }
                if (index < 0) break
                working = working.toMutableList().apply { removeAt(index) }
                droppableCount--
            }
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

    /**
     * Splits the non-system messages into groups anchored on each user message: every
     * user turn owns the assistant and tool messages that follow it, until the next
     * user message. Returns null when no honest anchor exists — either no user-role
     * message at all, or orphaned non-user turns before the first one — leaving those
     * conversations to the caller's legacy turn-by-turn drop.
     */
    private fun userAnchoredGroups(messages: List<UIMessage>): List<List<UIMessage>>? {
        val conversation = messages.filter { it.role != MessageRole.SYSTEM }
        if (conversation.isEmpty() || conversation.first().role != MessageRole.USER) return null
        val groups = mutableListOf<MutableList<UIMessage>>()
        for (message in conversation) {
            if (message.role == MessageRole.USER) groups.add(mutableListOf())
            groups.last().add(message)
        }
        return groups
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

    /** Null when there is nothing to say and nothing was called, matching the reference.
     *
     *  Reasoning is echoed back through `reasoning_content`, not dropped (Bug #5): the engine's
     *  OAICOMPAT message parser reads that field (`common/chat.cpp`, `msg.reasoning_content =
     *  message.at("reasoning_content")`) and `common_chat_templates_apply` renders it as the
     *  model's own thinking block, which is llama.cpp server's default "preserve reasoning"
     *  behaviour. Dropping it was the dominant stall cause: a step that produced only thinking
     *  made the next request byte-identical to the one that had just produced it (proven by
     *  matching request-hash log lines 48900=48900, 48995=48995, 52050=52050), and identical
     *  input under greedy sampling reproduces the same dead step forever. A template that does
     *  not know the field simply ignores it, so the echo can only ever add information.
     */
    private fun assistantMessage(contentParts: List<UIMessagePart>, tools: List<UIMessagePart.Tool>): JSONObject? {
        val text = contentParts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
        val reasoning = contentParts.filterIsInstance<UIMessagePart.Reasoning>()
            .joinToString("") { it.reasoning }
        if (text.isBlank() && tools.isEmpty() && reasoning.isBlank()) return null

        val obj = JSONObject().put("role", "assistant").put("content", text)
        if (reasoning.isNotEmpty()) {
            obj.put("reasoning_content", reasoning)
        }
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
