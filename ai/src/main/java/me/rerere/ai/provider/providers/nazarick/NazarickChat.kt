package me.rerere.ai.provider.providers.nazarick

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage

/**
 * US-004 — the pure half of the Nazarick chat generator.
 *
 * Everything that decides *what goes on the wire* and *what comes off it* lives here as plain
 * functions, so the request shape and the delta-to-chunk mapping are unit-testable on the JVM
 * without OkHttp, a socket or an Android context. The networking itself is the thin
 * [NazarickProvider.streamText] loop next door: read line, feed [NazarickResponsesParser],
 * translate with [eventsToChunks], emit.
 *
 * What this layer deliberately does **not** do:
 *  - forward the app's system prompt or message history — the QwenPaw console endpoint keeps the
 *    transcript under the caller-chosen `session_id`, so the app sends one turn of user text and
 *    lets the instance remember the rest;
 *  - invent tool calls — see the TODO in [eventsToChunks].
 */

/** Part id for the reasoning lane of a Nazarick turn — mirrors the provider's text part id. */
const val NAZARICK_REASONING_PART_ID = "nazarick-reasoning"

/** Wire value the app falls back to when no conversation id reached the provider. */
const val NAZARICK_FALLBACK_CHAT_ID = "unknown-chat"

/** Finish string for a stream the server closed cleanly (`status=completed`); normalizes to Stop. */
const val NAZARICK_FINISH_COMPLETED = "completed"

/**
 * Finish string for a stream that ended **without** a `object=response, status=completed` line
 * (server hang-up, proxy cut). Kept distinct so a truncated answer never masquerades as a clean
 * stop; [me.rerere.ai.core.LlmFinishReason.normalize] maps it to Unknown, which is a legitimate
 * terminal value rather than an error.
 */
const val NAZARICK_FINISH_STREAM_CLOSED = "stream_closed"

/**
 * The `session_id` the app puts on the wire: always the US-003 namespaced form
 * `app-<device>-<chat>`, so an app conversation can never collide with the console's `default`.
 *
 * @param chatId the app conversation id (`TextGenerationParams.sessionId`); null falls back to
 *               [NAZARICK_FALLBACK_CHAT_ID] rather than sending a bare `app-<device>`, which would
 *               silently pool every id-less chat into one server transcript.
 */
fun nazarickSessionId(deviceSlug: String, chatId: String?): String =
    appSessionId(deviceSlug, chatId ?: NAZARICK_FALLBACK_CHAT_ID)

/**
 * Build the `POST /api/console/chat` body. Server contract, LIVE-PROBED 2026-11-20 against the
 * shipped server (US-012 pre-rebind leg, repro/v2/prebind-turn.json): `input` is a Responses-style
 * ITEMS ARRAY ([{role:user, content:[{type:input_text, text}]}]) — a bare string is rejected with
 * 400 "Chat input is empty" (the original US-004 shape, unit-tested but never live-probed, was
 * wrong); `stream` always true (the parser needs deltas); `session_id` namespaced via
 * [nazarickSessionId].
 *
 * US-011r: this is now the text-only face of [nazarickChatRequestBodyCore] — it delegates with no
 * image, so the wire it produces for a plain turn is byte-identical to the live-proven shape:
 * one user turn whose content array is the single `input_text` part.
 */
fun nazarickChatRequestBody(input: String, sessionId: String): String =
    nazarickChatRequestBodyCore(input = input, sessionId = sessionId, imageDataUri = null)

/**
 * US-011r — the body for a turn that carries an image to the tomb.
 *
 * The live-proven server contract (loopback probe, 2026-11-20) accepts a content part of the shape
 * `{"type":"input_image","image_url":"data:image/png;base64,…"}` (and plain https URLs) on
 * `POST /api/console/chat`, answering 200 with the usual SSE stream. [imageDataUri] is therefore
 * put on the wire **verbatim** — the caller owns mime sniffing and base64, this function owns only
 * placement, so a bytes-to-data-URI bug can never hide in a string munged twice.
 *
 * @param imageDataUri a complete data URI (`data:image/png;base64,…`) or an absolute https URL.
 *                     Blank falls back to the text-only body rather than emitting an
 *                     `input_image` part with an empty `image_url`, which the server would have to
 *                     guess about.
 */
fun nazarickChatRequestBodyWithImage(input: String, sessionId: String, imageDataUri: String): String =
    nazarickChatRequestBodyCore(input = input, sessionId = sessionId, imageDataUri = imageDataUri)

/**
 * The one place the chat body is assembled.
 *
 * ONE array, ONE source of truth: `input` is the Responses-style items array
 * `[{role: "user", content: [input_text, (input_image)]}]` — the shape the LIVE-PROBED server
 * reads (US-012 pre-rebind, 2026-11-20: a bare-string `input` is rejected 400 "Chat input is
 * empty", and the server parses no sibling `items` field). The image travels as the second
 * content part of the same user turn. Content-part key names copy the house emitter in
 * `ResponseAPI.kt` (`input_text`/`text`, `input_image`/`image_url`) so the app speaks one dialect
 * to every Responses-shaped endpoint.
 */
private fun nazarickChatRequestBodyCore(
    input: String,
    sessionId: String,
    imageDataUri: String?,
): String =
    buildJsonObject {
        putJsonArray("input") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", input)
                    }
                    if (!imageDataUri.isNullOrBlank()) {
                        addJsonObject {
                            put("type", "input_image")
                            put("image_url", imageDataUri)
                        }
                    }
                }
            }
        }
        put("stream", true)
        put("session_id", sessionId)
    }.toString()

/**
 * The text to send: the last **user** message rendered to text. Assistant turns and tool results
 * stay in the instance's own transcript, and system prompts are the agent's persona on the server
 * side, not the app's. Blank when the newest user turn carries no text (image-only), which the
 * caller reports instead of POSTing an empty prompt.
 */
fun nazarickLastUserText(messages: List<UIMessage>): String =
    messages.lastOrNull { it.role == MessageRole.USER }?.toText().orEmpty()

/**
 * One translated batch of parser events.
 *
 * [textOpen] and [completed] are the two pieces of state the streaming loop has to carry between
 * lines: whether a text part is currently open (so `TextStart`/`TextEnd` fire exactly once) and
 * whether the server has declared the response finished. Keeping them in the return value — not in
 * a hidden mutable decoder — keeps [eventsToChunks] a pure function a unit test can call directly.
 */
data class NazarickChunkBatch(
    val chunks: List<StreamChunk>,
    val textOpen: Boolean,
    val completed: Boolean,
    val responseId: String?,
    /** Whether a reasoning part is open from this batch — carried between lines like [textOpen]. */
    val reasoningOpen: Boolean = false,
    /** `msg_id -> type` learned from `MessageOpened` in this batch; the loop accumulates across lines. */
    val msgKinds: Map<String, String?> = emptyMap(),
)

/**
 * Translate [NazarickResponsesParser] events into the app's provider-independent [StreamChunk]
 * stream, following the house decoder idiom (`ChatCompletionsStreamDecoder`): open a text part
 * with `TextStart` on the first delta, stream `TextDelta`s, close with `TextEnd` and terminate
 * with `Finish`.
 *
 * @param textOpen whether a text part is already open from an earlier batch.
 * @param model echoed into `Finish` so the loop can attribute the turn to the model it asked for.
 */
fun eventsToChunks(
    events: List<NazarickEvent>,
    textId: String,
    textOpen: Boolean = false,
    model: String? = null,
    reasoningId: String = NAZARICK_REASONING_PART_ID,
    reasoningOpen: Boolean = false,
    msgKinds: Map<String, String?> = emptyMap(),
): NazarickChunkBatch {
    var open = textOpen
    var thinkOpen = reasoningOpen
    var completed = false
    var responseId: String? = null
    val kinds = HashMap(msgKinds)
    val chunks = mutableListOf<StreamChunk>()

    for (event in events) {
        when (event) {
            // `object=message` opens a part whose `type` decides the lane: `reasoning` prose is the
            // model's chain of thought, anything else (`message`) is the answer. The tag is stored,
            // not emitted — TextDelta carries only msg_id, so the lane lookup must survive it.
            is NazarickEvent.MessageOpened -> kinds[event.msgId] = event.msgType

            is NazarickEvent.TextDelta -> if (event.text.isNotEmpty()) {
                val isReasoning = event.msgId?.let(kinds::get) == "reasoning"
                if (isReasoning) {
                    if (!thinkOpen) {
                        chunks += StreamChunk.ReasoningStart(reasoningId)
                        thinkOpen = true
                    }
                    chunks += StreamChunk.ReasoningDelta(reasoningId, event.text)
                } else {
                    // Unknown or unseen msg_id falls to the answer lane — the pre-tagging behaviour,
                    // so a server that stops announcing MessageOpened degrades to prose, not silence.
                    if (!open) {
                        chunks += StreamChunk.TextStart(textId)
                        open = true
                    }
                    chunks += StreamChunk.TextDelta(textId, event.text)
                }
            }

            is NazarickEvent.ResponseStarted -> {
                responseId = event.responseId ?: responseId
            }

            is NazarickEvent.ResponseCompleted -> {
                if (thinkOpen) {
                    chunks += StreamChunk.ReasoningEnd(reasoningId)
                    thinkOpen = false
                }
                if (open) {
                    chunks += StreamChunk.TextEnd(textId)
                    open = false
                }
                completed = true
                responseId = event.responseId ?: responseId
                chunks += StreamChunk.Finish(
                    finishReason = NAZARICK_FINISH_COMPLETED,
                    responseId = responseId,
                    model = model,
                )
            }

            // TODO(US-005): `object=content, type=data` carries plugin-call argument fragments;
            //  the tool-call contract belongs to US-005, which owns ToolCallStart/Delta/End.
        }
    }

    return NazarickChunkBatch(
        chunks = chunks,
        textOpen = open,
        completed = completed,
        responseId = responseId,
        reasoningOpen = thinkOpen,
        msgKinds = kinds,
    )
}
