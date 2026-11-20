package me.rerere.ai.provider.providers.nazarick

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * One decoded event of a Nazarick-instance (QwenPaw) chat stream — US-002.
 *
 * The server speaks the OpenAI **Responses-API** SSE shape, which carries more event kinds than a
 * chat transcript needs. Only the four transitions the UI actually consumes are modelled, and what
 * is deliberately *not* modelled is as load-bearing as what is:
 *  - `object=message, status=completed` — the terminal message event repeats the whole content
 *    block, so re-emitting its text would duplicate prose its deltas already delivered.
 *  - `object=content, type=data` — plugin-call argument fragments (US-005 owns tool calls).
 *  - `type=turn_usage` — carries no `object` key at all, and any object this parser has not met.
 */
sealed interface NazarickEvent {

    /** First `object=response` event with status `created` or `in_progress`. Emitted once per stream. */
    data class ResponseStarted(val responseId: String?, val sequenceNumber: Int?) : NazarickEvent

    /** `object=message` opened (status `created` or `in_progress`). Emitted once per message id. */
    data class MessageOpened(val msgId: String, val msgType: String?) : NazarickEvent

    /** A `type=text, delta=true` content fragment, tagged with the server's own sequence_number. */
    data class TextDelta(val sequenceNumber: Int?, val msgId: String?, val text: String) : NazarickEvent

    /** `object=response, status=completed`. Emitted once per stream; the transcript is finished. */
    data class ResponseCompleted(val responseId: String?, val sequenceNumber: Int?) : NazarickEvent
}

/**
 * Incremental decoder for the Nazarick-instance chat stream (US-002).
 *
 * Feed it one wire line at a time — exactly the line an SSE listener or a
 * `BufferedSource.readUtf8Line()` hands over — and it returns the events that line produced,
 * usually zero or one. [feedLine] **never throws**: a malformed payload, an unknown `object`, a
 * bare `[DONE]` sentinel, an SSE `id:`/`event:`/`:`-comment line or a half-written line torn by a
 * disconnect all return an empty list, because a provider that dies on one junk line loses the
 * whole answer.
 *
 * Pure Kotlin — kotlinx-serialization only, no OkHttp, no Android — so it runs on the JVM test
 * classpath and, later, in any non-Android host. The real captured stream it was built against is
 * banked at `ai/src/test/resources/nazarick/stream_fixture.txt`.
 *
 * Field paths pinned against the live capture (all observed 2026-11-17, `repro/v2/stream.txt`):
 * ```
 * data: {"object":"response","status":"created","id":"response_…","sequence_number":1, …}
 * data: {"object":"message","status":"in_progress","id":"msg_…","type":"reasoning", …}
 * data: {"object":"content","type":"text","delta":true,"msg_id":"msg_…","text":"The","sequence_number":4}
 * data: {"object":"content","type":"data","delta":true,"msg_id":"msg_…","data":{…},"sequence_number":130}
 * data: {"object":"response","status":"completed","id":"response_…","sequence_number":129}
 * ```
 * `sequence_number` is stream-global and monotonic, so it — not arrival order — is the ordering
 * authority for [reassemble]; deltas that arrive late or out of order still land in place.
 */
class NazarickResponsesParser {

    private val acceptedDeltas = mutableListOf<NazarickEvent.TextDelta>()

    /** Every text delta accepted so far, in arrival order (see [reassemble] for wire order). */
    val textDeltas: List<NazarickEvent.TextDelta> get() = acceptedDeltas.toList()

    private val openedMessages = mutableSetOf<String>()
    private val seenSequences = mutableSetOf<Int>()
    private var responseStarted = false
    private var responseCompleted = false

    /**
     * Decode a single stream line into zero or more [NazarickEvent]s. Null-safe and
     * exception-free by contract; see the class doc for what gets dropped and why.
     */
    fun feedLine(line: String?): List<NazarickEvent> {
        val trimmed = line?.trim() ?: return emptyList()
        // Blank lines, `id:`/`event:`/`retry:` SSE metadata and `:` comments carry no payload.
        if (!trimmed.startsWith(DATA_PREFIX)) return emptyList()
        val payload = trimmed.substring(DATA_PREFIX.length).trim()
        if (payload.isEmpty() || payload == DONE_SENTINEL) return emptyList()
        val obj = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: return emptyList()   // truncated JSON, arrays, bare scalars
        return when (obj.stringValue(KEY_OBJECT)) {
            OBJECT_RESPONSE -> responseEvents(obj)
            OBJECT_MESSAGE -> messageEvents(obj)
            OBJECT_CONTENT -> contentEvents(obj)
            else -> emptyList()     // turn_usage, unknown objects, missing key
        }
    }

    /**
     * The assistant text as the server meant it: accepted text deltas concatenated in ascending
     * `sequence_number` order (a delta with no sequence number keeps arrival order and sorts last).
     *
     * @param msgId restricts the result to one message, for streams that open several in a turn
     *              (reasoning, plugin call, final answer). Null joins every message.
     */
    fun reassemble(msgId: String? = null): String {
        val parts = if (msgId == null) acceptedDeltas else acceptedDeltas.filter { it.msgId == msgId }
        return parts.sortedBy { it.sequenceNumber ?: Int.MAX_VALUE }
            .joinToString(separator = "") { it.text }
    }

    private fun responseEvents(obj: JsonObject): List<NazarickEvent> {
        val id = obj.stringValue(KEY_ID)
        val sequence = obj.intValue(KEY_SEQUENCE)
        return when (obj.stringValue(KEY_STATUS)) {
            STATUS_COMPLETED -> {
                if (responseCompleted) emptyList() else {
                    responseCompleted = true
                    listOf(NazarickEvent.ResponseCompleted(id, sequence))
                }
            }

            STATUS_CREATED, STATUS_IN_PROGRESS -> {
                if (responseStarted) emptyList() else {
                    // A reconnect can be captured mid-flight, so in_progress starts the stream too.
                    responseStarted = true
                    listOf(NazarickEvent.ResponseStarted(id, sequence))
                }
            }

            else -> emptyList()     // queued / failed / incomplete: not modelled in US-002
        }
    }

    private fun messageEvents(obj: JsonObject): List<NazarickEvent> {
        val msgId = obj.stringValue(KEY_ID) ?: return emptyList()
        if (openedMessages.add(msgId).not()) return emptyList()
        return when (obj.stringValue(KEY_STATUS)) {
            STATUS_CREATED, STATUS_IN_PROGRESS ->
                listOf(NazarickEvent.MessageOpened(msgId, obj.stringValue(KEY_TYPE)))

            else -> emptyList()     // completed: the aggregated snapshot, text already streamed
        }
    }

    private fun contentEvents(obj: JsonObject): List<NazarickEvent> {
        if (obj.stringValue(KEY_TYPE) != TYPE_TEXT) return emptyList()   // type=data = tool arguments
        if (obj.booleanValue(KEY_DELTA) != true) return emptyList()      // delta=false = whole-block echo
        val text = obj.stringValue(KEY_TEXT) ?: return emptyList()
        val sequence = obj.intValue(KEY_SEQUENCE)
        if (sequence != null && !seenSequences.add(sequence)) return emptyList()
        val delta = NazarickEvent.TextDelta(sequence, obj.stringValue(KEY_MSG_ID), text)
        acceptedDeltas += delta
        return listOf(delta)
    }

    private companion object {
        const val DATA_PREFIX = "data:"
        const val DONE_SENTINEL = "[DONE]"

        const val KEY_OBJECT = "object"
        const val KEY_STATUS = "status"
        const val KEY_TYPE = "type"
        const val KEY_ID = "id"
        const val KEY_MSG_ID = "msg_id"
        const val KEY_TEXT = "text"
        const val KEY_DELTA = "delta"
        const val KEY_SEQUENCE = "sequence_number"

        const val OBJECT_RESPONSE = "response"
        const val OBJECT_MESSAGE = "message"
        const val OBJECT_CONTENT = "content"

        const val STATUS_CREATED = "created"
        const val STATUS_IN_PROGRESS = "in_progress"
        const val STATUS_COMPLETED = "completed"

        const val TYPE_TEXT = "text"
    }
}

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.intValue(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.booleanValue(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull
