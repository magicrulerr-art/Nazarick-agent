package me.rerere.ai.provider.providers.nazarick

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * Build the `POST /api/console/chat` body. Field names are the server contract of record
 * (`repro/v2/stream.txt` capture + `floor/ticket-2026-11-17-companion-edge-v2.md`):
 * `input` (this turn's user text), `stream` (always true — the parser needs deltas),
 * `session_id` (namespaced via [nazarickSessionId]).
 */
fun nazarickChatRequestBody(input: String, sessionId: String): String =
    buildJsonObject {
        put("input", input)
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
): NazarickChunkBatch {
    var open = textOpen
    var completed = false
    var responseId: String? = null
    val chunks = mutableListOf<StreamChunk>()

    for (event in events) {
        when (event) {
            is NazarickEvent.TextDelta -> if (event.text.isNotEmpty()) {
                if (!open) {
                    chunks += StreamChunk.TextStart(textId)
                    open = true
                }
                chunks += StreamChunk.TextDelta(textId, event.text)
            }

            is NazarickEvent.ResponseStarted -> {
                responseId = event.responseId ?: responseId
            }

            is NazarickEvent.ResponseCompleted -> {
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

            // TODO(US-005): non-text content is out of US-004 scope and is dropped, not invented.
            //  `object=message, type=reasoning` opens a reasoning part and `object=content,
            //  type=data` carries plugin-call argument fragments; the app's tool-call + reasoning
            //  contract belongs to US-005, which owns surfacing them as
            //  StreamChunk.ReasoningStart/Delta/End and ToolCallStart/Delta/End. Emitting prose or
            //  a fake tool part here would teach the loop to expect a shape nobody ratified.
            is NazarickEvent.MessageOpened -> Unit
        }
    }

    return NazarickChunkBatch(chunks = chunks, textOpen = open, completed = completed, responseId = responseId)
}
