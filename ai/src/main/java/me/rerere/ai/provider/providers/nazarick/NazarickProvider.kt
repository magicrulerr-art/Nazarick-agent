package me.rerere.ai.provider.providers.nazarick

import android.os.Build
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
import me.rerere.ai.companion.buildCompanionTurnBody
import me.rerere.ai.companion.companionEventsToChunks
import me.rerere.ai.companion.companionHeaders
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
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.Base64
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
    ): Flow<StreamChunk> {
        // US-002: companion_enabled is a WIRE switch, not a no-op. ON sends the same turn
        // through the companion lane (POST /api/companion/agents/{agent}/chat, X-Companion-*
        // headers, companionEventsToChunks); OFF falls through to the untouched console
        // assembly below — same request bytes as every build before this branch.
        if (providerSetting.companion_enabled) {
            return companionStreamText(providerSetting, messages, params)
        }
        return flow {
        val url = nazarickUrl(providerSetting.baseUrl, NazarickClient.CHAT_PATH)
            ?: throw IllegalArgumentException("invalid nazarick baseUrl: ${providerSetting.baseUrl}")
        val input = nazarickLastUserText(messages)
        // US-011r: an image on the outgoing turn is read and encoded *before* the POST, so a
        // missing or unreadable file fails loudly here instead of silently degrading to the
        // text-only turn the user did not ask for.
        val imageDataUri = nazarickTurnImageUri(messages)
        if (input.isBlank() && imageDataUri == null) {
            throw IllegalArgumentException("nazarick chat needs a user text message to send")
        }

        // One id on both wires (US-006): the chat POST starts the run under this session_id and
        // the stop POST addresses the run by it — the server resolves session_id -> chat for
        // channel=console, so the tap cannot stop somebody else's conversation.
        val sessionId = nazarickSessionId(deviceSlug, params.sessionId)
        val body = if (imageDataUri == null) {
            nazarickChatRequestBody(
                input = input,
                sessionId = sessionId,
            )
        } else {
            nazarickChatRequestBodyWithImage(
                input = input,
                sessionId = sessionId,
                imageDataUri = imageDataUri,
            )
        }.toRequestBody(JSON_MEDIA_TYPE.toMediaType())
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
            var reasoningOpen = false
            // msg_id -> message type: reasoning prose and answer prose interleave, and the
            // lane tag is only announced once per part, so the map must outlive single batches.
            val msgKinds = HashMap<String, String?>()
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
                        reasoningOpen = reasoningOpen,
                        msgKinds = msgKinds,
                    )
                    for (chunk in batch.chunks) emit(chunk)
                    textOpen = batch.textOpen
                    reasoningOpen = batch.reasoningOpen
                    msgKinds.putAll(batch.msgKinds)
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
                if (reasoningOpen) emit(StreamChunk.ReasoningEnd(NAZARICK_REASONING_PART_ID))
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
    }

    /**
     * US-002 — the companion-lane turn, mirroring the console branch's streaming idiom:
     * `POST {baseUrl}/api/companion/agents/{companion_agent_id or agentId}/chat` assembled by
     * [companionTurnRequest] ([buildCompanionTurnBody] body carrying the same namespaced
     * session id the console path uses, [companionHeaders] envelope from
     * `companion_device_id` + this device's Build model/os), answered by companion SSE lines
     * mapped verbatim through [companionEventsToChunks].
     *
     * Cancellation follows the console branch: a cancelled collector cancels the call via
     * `invokeOnCompletion`, the read loop re-checks `ensureActive()` per line, and a non-2xx
     * answer throws [NazarickHttpException] instead of stalling. There is no stop POST: the
     * companion lane ships no stop endpoint in US-002, so closing the socket is the stop.
     */
    private fun companionStreamText(
        providerSetting: ProviderSetting.Nazarick,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow {
        // One id on both wires: same nazarickSessionId(deviceSlug, chatId) the console path
        // sends as session_id; the companion server takes it in chat_id.
        val sessionId = nazarickSessionId(deviceSlug, params.sessionId)
        val request = companionTurnRequest(
            settings = providerSetting,
            messages = messages,
            sessionId = sessionId,
            deviceModel = Build.MODEL,
            deviceOs = "Android " + Build.VERSION.RELEASE,
        )
        val call = client.newCall(request)
        val stopHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = runCatching { response.body?.string() }.getOrNull().orEmpty()
                    throw NazarickHttpException(response.code, errorBody)
                }
                val source = response.body?.source()
                    ?: throw IllegalStateException("companion chat returned no body")
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = runCatching { source.readUtf8Line() }.getOrElse {
                        // call.cancel() (user Stop) lands here as an IOException; the collector
                        // is already gone, so end quietly like the console branch does.
                        if (currentCoroutineContext()[Job]?.isActive != true) return@flow else throw it
                    } ?: break       // EOF: the server closed the stream
                    for (chunk in companionEventsToChunks(listOf(line))) emit(chunk)
                }
            }
        } finally {
            stopHandle?.dispose()
        }
    // Unbounded buffer for the same reason as the console path: a dropped delta is a lost word.
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

/**
 * US-011r — the wire `image_url` for the outgoing turn's image, or null when that turn carries no
 * image part.
 *
 * `UIMessagePart.Image.url` (ai/ui/UIMessagePart.kt) holds whatever the picker stored, and the app
 * always copies an attachment into app storage first (`FilesManager.createChatFilesByContents`
 * returns `file.toUri()`), so the everyday case is a `file://` URI. The three shapes handled:
 *
 *  - `data:` / `http(s)://` — already a valid `image_url` for the live-proven contract (the
 *    2026-11-20 loopback probe accepted both), forwarded **verbatim** rather than re-encoded;
 *  - `file://` (and a bare absolute path) — read with `java.io` and inlined as a base64 data URI,
 *    which is the shape the probe verified end-to-end;
 *  - anything else (`content://`, `res://`, …) — no `java.io` reader exists for it, so it raises
 *    [NazarickImageUploadException] rather than POSTing a turn whose image silently vanished. That
 *    is the honest failure the composer's Tomb chip needs to be able to show.
 *
 * Called from [NazarickProvider.streamText] before the request is built, so an unreadable image is
 * reported as a failed turn instead of a wrong one.
 */
private fun nazarickTurnImageUri(messages: List<UIMessage>): String? {
    val url = messages.lastOrNull { it.role == MessageRole.USER }
        ?.parts
        ?.filterIsInstance<UIMessagePart.Image>()
        ?.lastOrNull()
        ?.url
        ?.takeIf { it.isNotBlank() }
        ?: return null

    if (url.startsWith("data:") || url.startsWith("http://") || url.startsWith("https://")) return url

    return try {
        val path = url.removePrefix("file://")
        val bytes = File(path).readBytes()
        "data:${nazarickImageMimeFromPath(path)};base64," + Base64.getEncoder().encodeToString(bytes)
    } catch (e: IOException) {
        throw NazarickImageUploadException(url, e)
    }
}

/**
 * Mime for a local image path, by extension only (US-011r). `png`/`jpg`/`jpeg`/`webp` map to their
 * real types; anything unknown reports `image/png`, the one mime the live-proven contract was
 * verified with — a wrong-but-parseable mime is the server's to tolerate, whereas an unsupported
 * type string risks a hard reject. Query/fragment suffixes are stripped before the extension is
 * read, and the comparison is case-insensitive because camera folders disagree about case.
 */
internal fun nazarickImageMimeFromPath(path: String): String =
    when (path.substringBefore('#').substringBefore('?').substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "png" -> "image/png"
        else -> "image/png"
    }

/**
 * US-002 — the companion turn endpoint path: `/api/companion/agents/{agent}/chat`, where the
 * agent segment is `companion_agent_id`, falling back to the console `agentId` when blank.
 */
internal fun companionChatPath(settings: ProviderSetting.Nazarick): String {
    val agent = settings.companion_agent_id.takeUnless { it.isBlank() } ?: settings.agentId
    return "/api/companion/agents/$agent/chat"
}

/**
 * US-002 — the companion branch's ONE request-build point, kept pure so the JVM unit test
 * pins URL + headers without Android or a socket: `android.os.Build` stays in
 * [NazarickProvider.companionStreamText] and the device model/os arrive as plain strings.
 *
 * Body: [buildCompanionTurnBody] with the same namespaced session id the console path derives.
 * Headers: [companionHeaders] (X-Companion-Device/Model/Os + Bearer token) plus
 * `Accept: text/event-stream`, mirroring the console request's SSE negotiation. The agent is
 * addressed by the URL path, so no X-Agent-Id header is sent.
 */
internal fun companionTurnRequest(
    settings: ProviderSetting.Nazarick,
    messages: List<UIMessage>,
    sessionId: String,
    deviceModel: String,
    deviceOs: String,
): Request {
    val url = nazarickUrl(settings.baseUrl, companionChatPath(settings))
        ?: throw IllegalArgumentException("invalid nazarick baseUrl: ${settings.baseUrl}")
    val body = buildCompanionTurnBody(messages, sessionId, settings)
        .toRequestBody(JSON_MEDIA_TYPE.toMediaType())
    return Request.Builder().url(url).apply {
        companionHeaders(
            deviceId = settings.companion_device_id,
            model = deviceModel,
            osName = deviceOs,
            token = settings.token,
        ).forEach { (name, value) -> header(name, value) }
        header("Accept", "text/event-stream")
    }.post(body).build()
}
