package me.rerere.ai.provider.providers.nazarick

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The chat generator for a Nazarick-agent instance (US-004).
 *
 * One turn is one request: `POST {baseUrl}/api/console/chat` with
 * `{input, stream: true, session_id}` (see [nazarickChatRequestBody]) and the shared auth headers
 * from [nazarickRequest], answered by an OpenAI-Responses-shaped SSE stream. The stream is read
 * line-per-line straight into [NazarickResponsesParser] — the house raw-streaming idiom of
 * `LocalDreamEngine.generate`, not OkHttp's SSE helper, because the payload dialect is not plain
 * chat-completions and the US-002 parser already speaks it — and translated by [eventsToChunks]
 * into the app's provider-independent [StreamChunk]s, so the message UI sees deltas exactly like
 * every other remote provider.
 *
 * Failure is loud by contract (US-004 AC: "not silent stalls"): a non-2xx answer throws
 * [NazarickHttpException] (401 says the token was rejected), an unreachable host throws the
 * IOException OkHttp raises, and a stream that ends without a `status=completed` line finishes
 * with [NAZARICK_FINISH_STREAM_CLOSED] instead of pretending the turn was clean.
 */
class NazarickProvider(
    private val client: OkHttpClient = defaultNazarickClient(),
) : Provider<ProviderSetting.Nazarick> {

    /**
     * Device half of the namespaced wire `session_id`. The provider interface hands a provider
     * `TextGenerationParams.sessionId` (the app conversation id — see
     * `GenerationLoop.kt:1639`), but nothing carries a device identity down, so the slug is a
     * caller-settable var instead of US-004 refactoring the interface: US-005/006 set it once from
     * the device name when they wire the chat screen. Left alone, every phone shares the "device"
     * slug and conversations still cannot collide, because the chat half is the conversation id.
     */
    var deviceSlug: String = DEFAULT_DEVICE_SLUG

    override suspend fun listModels(providerSetting: ProviderSetting.Nazarick): List<Model> =
        emptyList()

    override suspend fun generateText(
        providerSetting: ProviderSetting.Nazarick,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        // The console endpoint only streams; a non-streaming turn is the same request with the
        // deltas collected, which keeps one wire path (and one failure story) for both modes.
        val text = StringBuilder()
        var responseId: String? = null
        streamText(providerSetting, messages, params).collect { chunk ->
            when (chunk) {
                is StreamChunk.TextDelta -> text.append(chunk.text)
                is StreamChunk.Finish -> responseId = chunk.responseId ?: responseId
                else -> Unit
            }
        }
        return TextGenerationResult(
            id = responseId ?: "nazarick-${params.sessionId ?: "unknown"}",
            model = params.model.modelId,
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text(text.toString())),
            ),
            finishReason = NAZARICK_FINISH_COMPLETED,
            usage = null,
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Nazarick,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow {
        val url = nazarickUrl(providerSetting.baseUrl, NazarickClient.CHAT_PATH)
            ?: throw IllegalArgumentException("invalid nazarick baseUrl: ${providerSetting.baseUrl}")
        val input = nazarickLastUserText(messages)
        if (input.isBlank()) {
            throw IllegalArgumentException("nazarick chat needs a user text message to send")
        }

        // One id on both wires (US-006): the chat POST starts the run under this session_id and
        // the stop POST addresses the run by it — the server resolves session_id -> chat for
        // channel=console, so the tap cannot stop somebody else's conversation.
        val sessionId = nazarickSessionId(deviceSlug, params.sessionId)
        val body = nazarickChatRequestBody(
            input = input,
            sessionId = sessionId,
        ).toRequestBody(JSON_MEDIA_TYPE.toMediaType())
        val request = nazarickRequest(
            url = url,
            token = providerSetting.token,
            agentId = providerSetting.agentId,
            body = body,
        ).newBuilder()
            .header("Accept", "text/event-stream")
            .build()

        val call = client.newCall(request)
        // Stop must reach the socket: a collector that goes away cancels this coroutine, but the
        // blocking read below only notices at its next line, and the instance keeps generating
        // until the request itself is cancelled. call.cancel() surfaces as an IOException.
        val stopHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        // US-006 needs to tell a clean end from a cut stream in the finally below:
        //  serverRunStarted — the POST was accepted, so an instance-side run exists to stop;
        //  normalEnd       — the read loop finished by itself (completed line or EOF).
        // The stop POST fires only for a run that started and did NOT end normally, i.e. a
        // cancellation or a mid-stream fault: exactly the cases where the agent would otherwise
        // keep generating into a socket nobody reads.
        var serverRunStarted = false
        var normalEnd = false
        try {
            val parser = NazarickResponsesParser()
            var textOpen = false
            var responseId: String? = null
            var completed = false

            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = runCatching { response.body?.string() }.getOrNull().orEmpty()
                    throw NazarickHttpException(response.code, errorBody)
                }
                serverRunStarted = true
                val source = response.body?.source()
                    ?: throw IllegalStateException("nazarick chat returned no body")

                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = runCatching { source.readUtf8Line() }.getOrElse {
                        // call.cancel() (user Stop) lands here as an IOException. The collector is
                        // already gone, so end quietly instead of reporting a phantom failure.
                        if (currentCoroutineContext()[Job]?.isActive != true) return@flow else throw it
                    } ?: break       // EOF: the server closed the stream

                    val events = parser.feedLine(line)
                    if (events.isEmpty()) continue

                    val batch = eventsToChunks(
                        events = events,
                        textId = TEXT_PART_ID,
                        textOpen = textOpen,
                        model = params.model.modelId,
                    )
                    for (chunk in batch.chunks) emit(chunk)
                    textOpen = batch.textOpen
                    responseId = batch.responseId ?: responseId
                    if (batch.completed) {
                        completed = true
                        break
                    }
                }
            }
            normalEnd = true

            if (!completed) {
                // No status=completed line: the connection ended first. Close the text part so the
                // UI stops showing a live cursor, and name the truncation in the finish reason.
                if (textOpen) emit(StreamChunk.TextEnd(TEXT_PART_ID))
                emit(
                    StreamChunk.Finish(
                        finishReason = NAZARICK_FINISH_STREAM_CLOSED,
                        responseId = responseId,
                        model = params.model.modelId,
                    )
                )
            }
        } finally {
            stopHandle?.dispose()
            if (serverRunStarted && !normalEnd) {
                // US-006: the stream was cut while the instance was generating (user Stop, or a
                // mid-stream fault). call.cancel() above only tears down the client side — tell
                // the server too, or the agent burns tokens on an answer nobody will read.
                //
                // withContext(NonCancellable) is what GUARANTEES the POST still runs: this
                // coroutine is already cancelled here and a plain suspend call would re-throw
                // CancellationException before the request left. The flow body runs on
                // Dispatchers.IO (flowOn below), so stopChat's await() is off the main thread.
                //
                // runCatching on purpose: the ai module has no logging idiom (neighbours carry
                // only stray debug printlns) and a failed stop follow-up must never replace the
                // original cancellation. The reason is not lost — stopChat returns it as a
                // [NazarickStopOutcome] (pure-mapped by [stopOutcome], unit-tested); surfacing it
                // in the UI is a follow-up past this story.
                withContext(NonCancellable) {
                    runCatching {
                        NazarickClient(client).stopChat(
                            baseUrl = providerSetting.baseUrl,
                            token = providerSetting.token,
                            agentId = providerSetting.agentId,
                            sessionId = sessionId,
                        )
                    }
                }
            }
        }
    // Unbounded buffer, same reason the OpenAI/Google providers give one: a buffered channel that
    // fills would drop a delta, and a dropped delta is a missing word in the middle of an answer.
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = error("Nazarick image generation is not wired (US-004 ships chat only)")

    companion object {
        /** Device slug used until US-005/006 inject the real device identity. */
        const val DEFAULT_DEVICE_SLUG = "device"

        /** One text part per turn: the console stream interleaves no parallel text channels. */
        const val TEXT_PART_ID = "nazarick-text"

        private const val JSON_MEDIA_TYPE = "application/json"

        /**
         * Defaults tuned for a stream that can sit silent while the agent works: no read-timeout
         * cut-off mid-turn, cancellation is explicit (the flow cancels the call). The app's DI
         * module hands its shared client instead, so real chats ride the shared connection pool.
         */
        fun defaultNazarickClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
