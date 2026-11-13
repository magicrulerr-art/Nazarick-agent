package me.rerere.llamacpp

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.classifyLlamaFinish
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.locallm.LocalRuntime
import me.rerere.locallm.LocalRuntimePreferences
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Turns one [ChatDeltaTracker] channel's deltas into the safe suffix to forward downstream,
 * given that the shared chunk protocol (UIMessage's delta-append fold) can only ever append,
 * never retract, what it has already been sent.
 *
 * A non-reset delta is already a safe suffix and is forwarded unchanged. A reset delta
 * carries the channel's full corrected value instead of a suffix (see [ChatDelta]'s doc); what
 * has already been forwarded downstream cannot be un-sent, so re-sending the corrected value
 * in full would duplicate whatever of it was already shown rather than fix it. Mid-stream
 * ([isFinal] false), forwarding nothing and silently resyncing to the corrected value is the
 * right call: a later, non-reset delta will extend past the corrected value and its suffix
 * carries the correction downstream, so nothing is lost - keeping the stale watermark instead
 * would make every later delta fail its "is this an extension of what's shown" check forever,
 * since the true text no longer contains the stale prefix at all once it has diverged.
 *
 * On the terminal delta ([isFinal] true - the final `isPartial = false` parse, see
 * [ChatDeltaTracker.consume]) there is no later delta to rely on: generation has already
 * stopped, so swallowing a reset there would drop the model's real final text permanently
 * instead of merely delaying its correction. [advance] forwards the full corrected value in
 * that one case instead, accepting a possible duplicate suffix in exchange for never silently
 * losing content the model actually produced.
 */
private class ChannelWatermark {
    private var shown = ""

    fun advance(deltaValue: String, reset: Boolean, isFinal: Boolean): String {
        if (reset) {
            shown = deltaValue
            return if (isFinal) deltaValue else ""
        }
        shown += deltaValue
        return deltaValue
    }
}

/**
 * Streams generation from a locally loaded GGUF through [LlamaCppRuntime]. The prompt, the
 * grammar that constrains tool-call syntax, and the rules for parsing the reply all come from
 * the applied-template blob [LlamaCppRuntime.applyTemplate] returns; this provider treats that
 * blob as opaque, only ever handing it back to [LlamaCppRuntime.generate] and
 * [LlamaCppRuntime.parse], never parsing or rebuilding it.
 *
 * [streamText] resolves [TextGenerationParams.model]'s `modelId` - a bare file name, never a
 * path (see [LocalRuntimePreferences.installedModels]) - to the GGUF's absolute path, loads it
 * via [LlamaCppRuntime.load], and only then templates and generates. [loadedPath] tracks which
 * path is currently loaded so a repeat call for the same model is a no-op: reloading a
 * multi-GB model on every turn would make every message pay the load cost again.
 *
 * [LlamaCppRuntime.generate] blocks the calling thread for the whole generation, so it runs on
 * a child coroutine on [Dispatchers.IO] rather than inline in the flow body: cancelling a
 * coroutine only takes effect at a suspension point, and a blocking native call has none, so a
 * cancelled collector could not otherwise interrupt it. [awaitClose] runs concurrently with
 * that child and calls [LlamaCppRuntime.cancelGeneration] - the only way to interrupt a prefill,
 * per its doc - when the flow is cancelled before the generation finished on its own.
 */
class LlamaCppProvider(
    private val context: Context,
    private val runtime: LlamaCppRuntime,
    private val prefs: LocalRuntimePreferences,
) : Provider<ProviderSetting.LlamaCppLocal> {

    /** Path [runtime] currently has loaded, or null before the first call. Read and set by
     *  [ensureLoaded] so a repeat [streamText] call for the same model skips the reload. */
    private val loadedPath = AtomicReference<String?>(null)

    /** Serializes each [generateText]/[streamText] call's load-then-generate sequence into one
     *  atomic step, held from [ensureModelReady]'s already-loaded check through the generation
     *  that consumes the loaded model. [runtime] holds at most one model at a time, so releasing
     *  the lock right after loading (as a mutex guarding only [ensureModelReady] would) leaves a
     *  window where a second concurrent call - e.g. a post-turn generateTitle and
     *  generateSuggestion firing right after each other, configurable to different model ids -
     *  swaps [runtime] to its own model before the first call's generation reads it, feeding a
     *  mismatched template/tokenizer/grammar into native code. Mirrors LiteRtRuntime.streamTurns,
     *  which holds its own mutex for the whole inference so concurrent callers queue up rather
     *  than racing the loaded model. */
    private val loadMutex = Mutex()

    override suspend fun listModels(providerSetting: ProviderSetting.LlamaCppLocal): List<Model> =
        providerSetting.models

    override suspend fun generateText(
        providerSetting: ProviderSetting.LlamaCppLocal,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = loadMutex.withLock {
        ensureModelReady(messages, params)
        generateFromLoadedModel(runtime, messages, params)
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.LlamaCppLocal,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow {
        // loadMutex is held across the whole load-then-generate sequence, not just the load -
        // see the mutex's doc comment for why the generation itself must stay inside the lock.
        loadMutex.withLock {
            ensureModelReady(messages, params)
            emitAll(streamFromLoadedModel(runtime, messages, params))
        }
    }

    /** Resolves [TextGenerationParams.model]'s modelId to a path and loads it into [runtime]
     *  (a no-op when it is already loaded - see [ensureLoaded]). Shared by [generateText] and
     *  [streamText] so the two agree on exactly one load path; both already hold [loadMutex]
     *  before calling this. */
    /** Bench hook for the engine A/B lanes: `adb shell setprop debug.nazarick.gpu_layers N`.
     *  0 means "unset - the provider pane's GPU switch decides"; -1 offloads every layer the
     *  compiled-in GPU backend can take, any positive N pins an explicit count. Read per load,
     *  so a lane switch only needs a model reload, never a reinstall. SystemProperties is a
     *  hidden API — reflection keeps the module free of a compile dependency on it. */
    private fun gpuLayersOverride(): Int = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
            .invoke(null, "debug.nazarick.gpu_layers", 0) as Int
    } catch (_: Throwable) {
        0
    }

    /** Owner-facing GPU lane, decided at each model load by [resolveGpuLayers] (top-level). */

    private suspend fun ensureModelReady(messages: List<UIMessage>, params: TextGenerationParams) {
        Log.i("nazarick-perf", "diag: ensureModelReady enter msgs=${messages.size} tools=${params.tools.size} availMem=${readAvailableRamBytes() / 1048576}MB")
        val installed = prefs.installedModels(LocalRuntime.LlamaCpp)
        val modelPath = resolveModelPath(installed, params.model.modelId)
        // GPU lane: owner's provider-pane switch (per-runtime force-CPU) with the bench prop
        // layered on. Resolved per load so flipping the switch takes effect on the next chat,
        // no reinstall. Embeddings are a separate loader (LlamaCppEmbedder.nativeLoadModel,0)
        // and stay on CPU regardless - the KV guard + planner Q8_0 invariant is untouched.
        val gpuLayers = resolveGpuLayers(
            forceCpu = prefs.forceCpu(LocalRuntime.LlamaCpp),
            propOverride = gpuLayersOverride(),
        )
        Log.i("nazarick-engine", "gpu lane: gpu_layers=$gpuLayers")
        // The load blocks the calling thread on a multi-gigabyte mmap, metadata parse and
        // context allocation, so it runs on Dispatchers.IO rather than whatever dispatcher the
        // caller used - mirrors LiteRtRuntime's engine init (LiteRtRuntime.kt:758). Without this,
        // a Main-confined caller like ProviderConnectionTester's rememberCoroutineScope would
        // load the model on the UI thread and ANR.
        withContext(Dispatchers.IO) {
            ensureLoaded(
                runtime = runtime,
                loadedPath = loadedPath,
                path = modelPath,
                tools = ChatRequestMapper.toolDeclarations(params.tools),
                systemPromptBytes = systemPromptBytesOf(messages),
                availableRamBytes = readAvailableRamBytes(),
                gpuLayers = gpuLayers,
            )
            Log.i("nazarick-perf", "diag: ensureLoaded returned")
        }
    }

    /** Bytes of SYSTEM-role text across [messages] - the figure [LlamaCppRuntime.load] feeds
     *  `ContextPlanner` so the system prompt is accounted for before tools are fit into the
     *  context. */
    private fun systemPromptBytesOf(messages: List<UIMessage>): Int =
        messages
            .filter { it.role == MessageRole.SYSTEM }
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Text>()
            .sumOf { it.text.toByteArray(Charsets.UTF_8).size }

    /** Free RAM to size the context against - the same `ActivityManager.MemoryInfo.availMem`
     *  source `MemoryGuard.canLoad` reads, so the planner and the load-time refusal agree on
     *  what "available" means. */
    private fun readAvailableRamBytes(): Long {
        val info = ActivityManager.MemoryInfo()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getMemoryInfo(info)
        return info.availMem
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = error("llama.cpp does not support image generation")

    companion object {
        /** Floor for the output budget ([ContextPlanner.outputTokenBudget] is the real
         *  ceiling). Since Bug #5 this is no longer the silent default every step got —
         *  it only holds the line when a tiny planned context would allow less. */
        private const val DEFAULT_MAX_TOKENS = 2048

        /**
         * The user-visible notices for the two step shapes that used to end a generation
         * stream looking exactly like a finished answer (Bug #5 F3/F4). Pure so CI can
         * prove the decision table without a native runtime.
         *
         *  - **budget cut mid-answer**: [decoded] consumed the whole ceiling with visible
         *    text already streamed and no tool call pending. A pending call fails louder
         *    through its own envelope downstream, and a text-free step is left to the
         *    generation loop's silent stall-nudge — a notice there would mark the step
         *    answered and rob it of that resume.
         *  - **tool call the parser never completed**: the raw output opened a call but
         *    nothing valid came out of it. Previously the step produced nothing at all
         *    with nothing said about it — the "search token loop" shape from the RCA.
         */
        internal fun postGenerationNotices(
            decoded: Int,
            maxTokens: Int,
            textForwarded: Boolean,
            toolCallsForwarded: Int,
            rawOutput: CharSequence,
        ): List<String> = buildList {
            if (decoded >= maxTokens && textForwarded && toolCallsForwarded == 0) {
                add("\n\n⚠️ Stopped at the local output limit ($maxTokens tokens) — say \"continue\" to resume.")
            }
            if (toolCallsForwarded == 0 && rawOutput.contains("<tool_call")) {
                add("\n\n⚠️ The model opened a tool call but emitted nothing the parser could execute — it was skipped.")
            }
        }

        /**
         * Resolves [modelId] - a bare file name, never a path - against [installed], the
         * `filename -> absolutePath` index [LocalRuntimePreferences.installedModels] returns.
         * Throws a user-readable, model-named error rather than letting a missing or stale
         * entry fail opaquely later on - mirrors `LiteRtProvider.streamText`'s guard.
         */
        internal fun resolveModelPath(installed: Map<String, String>, modelId: String): String {
            val path = installed[modelId]
                ?: throw IllegalStateException("Model $modelId not installed")
            if (!File(path).exists()) {
                throw IllegalStateException(
                    "Model file for \"$modelId\" is no longer present on disk ($path). " +
                        "Delete the model entry in Settings → Providers → Local · llama.cpp " +
                        "and re-download it."
                )
            }
            return path
        }

        /**
         * Loads [path] into [runtime] unless it is already the loaded model. Reloading a
         * multi-GB model on every turn is the obvious wrong implementation, so [loadedPath]
         * is checked first and only set to [path] after a real load succeeds.
         *
         * [LlamaCppRuntime.load] unconditionally unloads whatever was loaded before it
         * attempts [path], so the moment it is called the runtime no longer has
         * [loadedPath]'s old value loaded even if the new load then fails (e.g. an
         * oversized model throwing [ModelTooLargeException], or a corrupt file failing
         * natively). [loadedPath] is cleared to null right before that call so a failed
         * load leaves it reflecting "nothing loaded" rather than a model the runtime has
         * already discarded - otherwise a later call for that still-installed model would
         * see it as already loaded, skip the reload, and hit "no model is loaded" on the
         * very generation call this method exists to prevent.
         *
         * A path match still calls [LlamaCppRuntime.replan]: [tools] and
         * [systemPromptBytes] are this request's, not the ones the model happened to be
         * loaded with, and they can change turn to turn (a different assistant/tool
         * config) even when the model itself does not. Skipping that would leave a stale
         * [ContextPlan.droppedToolNames]/[ContextPlan.reservedInputBytes] in effect for
         * every request after the first against a given model.
         */
        internal suspend fun ensureLoaded(
            runtime: LlamaCppRuntime,
            loadedPath: AtomicReference<String?>,
            path: String,
            tools: List<ToolDeclaration>,
            systemPromptBytes: Int,
            availableRamBytes: Long,
            gpuLayers: Int = 0,
        ) {
            if (loadedPath.get() == path) {
                Log.i("nazarick-perf", "diag: replan begin tools=${tools.size} sysPromptBytes=$systemPromptBytes")
                runtime.replan(tools, systemPromptBytes)
                Log.i("nazarick-perf", "diag: replan end")
                return
            }
            loadedPath.set(null)
            Log.i("nazarick-perf", "diag: load begin path=$path tools=${tools.size} sysPromptBytes=$systemPromptBytes availRamMB=${availableRamBytes / 1048576} gpuLayers=$gpuLayers")
            runtime.load(path, tools, systemPromptBytes, availableRamBytes, gpuLayers = gpuLayers)
            Log.i("nazarick-perf", "diag: load end")
            loadedPath.set(path)
        }

        /**
         * [generateText]'s logic, given a [runtime] that already has the right model loaded
         * (see [ensureLoaded]). Every chunk [streamFromLoadedModel] emits carries an
         * incremental delta, not the full message, so the pieces are summed here rather than
         * just keeping the last one - mirrors LiteRtProvider.generateText. Kept as a function
         * of an explicit [runtime] parameter for the same testability reason as
         * [streamFromLoadedModel].
         */
        internal suspend fun generateFromLoadedModel(
            runtime: LlamaCppRuntime,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult {
            var collected = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
            val handler = StreamChunkHandler(params.model)
            streamFromLoadedModel(runtime, messages, params).collect { chunk ->
                collected = handler.handle(collected, chunk)
            }
            return TextGenerationResult(
                id = "llamacpp-${System.currentTimeMillis()}",
                model = params.model.modelId,
                message = collected.last(),
            )
        }

        /**
         * The streaming/chunking logic, given a [runtime] that already has the right model
         * loaded (see [ensureLoaded]). Kept as a function of an explicit [runtime] parameter
         * rather than an instance method reading a provider's own field so it stays
         * unit-testable without a live [Context]/[LocalRuntimePreferences] - see
         * `LlamaCppProviderTest`.
         */
        internal fun streamFromLoadedModel(
            runtime: LlamaCppRuntime,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> = callbackFlow {
            val streamId = "llamacpp-${System.currentTimeMillis()}"
            // One stable id per channel for the whole turn, so StreamChunkHandler appends
            // every delta into a single Text / Reasoning part - mirrors the old positional
            // append-only merge.
            val textId = "$streamId-text"
            val reasoningId = "$streamId-reasoning"
            // Trim history to the input half of the planned context before templating, so a
            // long conversation drops its oldest turns instead of overflowing the prompt.
            Log.i("nazarick-perf", "diag: stream begin msgs=${messages.size} inputBudgetBytes=${runtime.inputBudgetBytes()}")
            // The planner fits tools into the context by dropping the ones that do not fit;
            // honour that here, otherwise the decision exists only in the plan and the
            // rendered prompt overflows anyway.
            val dropped = runtime.droppedToolNames().toSet()
            val keptTools = if (dropped.isEmpty()) params.tools else params.tools.filterNot { it.name in dropped }
            if (dropped.isNotEmpty()) {
                Log.i("nazarick-perf", "diag: tools dropped for context ${dropped.size}/${params.tools.size}: $dropped")
            }
            val trimmedMessages = ChatRequestMapper.trimToBudget(messages, runtime.inputBudgetBytes())
            val requestJson = ChatRequestMapper.toRequestJson(
                trimmedMessages,
                keptTools,
                enableThinking = ContextPlanner.tunables.reasoning,
            )
            Log.i("nazarick-perf", "diag: request json built bytes=${requestJson.toByteArray(Charsets.UTF_8).size}")
            val appliedTemplateJson = runtime.applyTemplate(requestJson)
            Log.i("nazarick-perf", "diag: template applied")
            // Bug #5 F3: the ceiling is the response half of the planned context unless the
            // assistant set one explicitly, floored at the old 2048 — never silently below it.
            // The old unconditional 2048 cut thoughts off mid-sentence on this model class.
            val maxTokens = params.maxTokens
                ?: runtime.outputTokenBudget().coerceAtLeast(DEFAULT_MAX_TOKENS)
            Log.i(
                "nazarick-perf",
                "gen: max_tokens=$maxTokens source=${if (params.maxTokens != null) "assistant" else "context-plan"}",
            )
            val tracker = ChatDeltaTracker()
            val accumulated = StringBuilder()
            val finished = AtomicBoolean(false)
            // The chunk protocol downstream (UIMessage's delta-append fold) can only ever
            // append, never retract, so these mirror what has actually been forwarded for
            // each channel - see ChannelWatermark's doc for how that reconciles textReset/
            // reasoningReset.
            val textWatermark = ChannelWatermark()
            val reasoningWatermark = ChannelWatermark()

            // A plain captured lambda, not an extension function: it closes over this
            // callbackFlow's ProducerScope directly, so calling it from inside the doubly-nested
            // onPiece callback below needs no implicit-receiver resolution of its own. isFinal
            // marks the terminal (isPartial = false) call so a reset there can still be
            // forwarded - see ChannelWatermark's doc.
            // Tracks whether a ReasoningDelta has been sent that a ReasoningEnd has not yet
            // closed, so a later delta with text but no reasoning can close it - mirrors the
            // old merge logic's "a delta with no reasoning part closes any open reasoning part".
            var reasoningOpen = false
            // What actually reached the UI this step, so post-generation notices fire on the
            // step's real shape (see postGenerationNotices). Mutated from the worker through
            // this captured state exactly like reasoningOpen above.
            var forwardedText = false
            var forwardedToolCalls = 0

            val sendDelta: (ChatDelta, Boolean) -> Boolean = { delta, isFinal ->
                val textDelta = textWatermark.advance(delta.textDelta, delta.textReset, isFinal)
                val reasoningDelta = reasoningWatermark.advance(delta.reasoningDelta, delta.reasoningReset, isFinal)
                var sentAny = false
                var allOk = true
                if (reasoningDelta.isNotEmpty()) {
                    sentAny = true
                    reasoningOpen = true
                    allOk = trySend(StreamChunk.ReasoningDelta(reasoningId, reasoningDelta)).isSuccess && allOk
                } else if (reasoningOpen && textDelta.isNotEmpty()) {
                    reasoningOpen = false
                    allOk = trySend(StreamChunk.ReasoningEnd(reasoningId)).isSuccess && allOk
                }
                if (textDelta.isNotEmpty()) {
                    sentAny = true
                    forwardedText = true
                    allOk = trySend(StreamChunk.TextDelta(textId, textDelta)).isSuccess && allOk
                }
                delta.completedToolCalls.forEach { call ->
                    sentAny = true
                    forwardedToolCalls += 1
                    allOk = trySend(StreamChunk.ToolCallStart(id = call.id, toolName = call.name)).isSuccess && allOk
                    allOk = trySend(StreamChunk.ToolCallDelta(id = call.id, inputDelta = call.arguments)).isSuccess && allOk
                }
                if (sentAny) allOk else !isClosedForSend
            }

            val worker = launch(Dispatchers.IO) {
                Log.i("nazarick-perf", "diag: worker launching native generate")
                try {
                    val decoded = runtime.generate(
                        appliedTemplateJson,
                        maxTokens,
                        ContextPlanner.tunables.toolGrammar,
                    ) { piece ->
                        accumulated.append(piece)
                        val parsed = runtime.parse(accumulated.toString(), true, appliedTemplateJson)
                        sendDelta(tracker.consume(parsed, isPartial = true), false)
                    }
                    Log.i("nazarick-perf", "diag: native generate returned chars=${accumulated.length} decoded=$decoded/$maxTokens")

                    // The final parse is authoritative: it flushes any tool call whose arguments
                    // were still settling when generation stopped (see ChatDeltaTracker.consume).
                    val finalParsed = runtime.parse(accumulated.toString(), false, appliedTemplateJson)
                    sendDelta(tracker.consume(finalParsed, isPartial = false), true)

                    // Bug #5 F3/F4: the two ways a broken step used to look exactly like a
                    // finished answer. Notices go out as text so the owner sees them and the
                    // loop treats the step as answered — for a budget cut the user's next
                    // "continue" resumes on top of the echoed partial thought (F1), and a
                    // text-free budget cut is deliberately left notice-less so the loop's
                    // own stall-nudge gets its chance to resume silently.
                    for (notice in postGenerationNotices(
                        decoded = decoded,
                        maxTokens = maxTokens,
                        textForwarded = forwardedText,
                        toolCallsForwarded = forwardedToolCalls,
                        rawOutput = accumulated,
                    )) {
                        Log.e("nazarick-engine", notice.trim())
                        trySend(StreamChunk.TextDelta(textId, notice))
                    }
                    // AURA-021 (LC-1): classify at the source, where both numbers are
                    // measured — the token count llama actually decoded vs the budget it
                    // was given. decoded < max means the engine stopped on its own stop
                    // word (a clean Stop, even when the visible text ends oddly);
                    // decoded >= max means the cap cut the generation, and the loop must
                    // treat the step as continueable (Length), never as a stall.
                    val finishKind = classifyLlamaFinish(
                        stopWordReached = decoded < maxTokens,
                        decodedTokens = decoded,
                        maxTokens = maxTokens,
                        emittedToolCalls = forwardedToolCalls,
                    )
                    trySend(StreamChunk.Finish(reason = finishKind))
                    Log.i("nazarick-perf", "diag: stream finish sent kind=$finishKind")
                } finally {
                    finished.set(true)
                    close()
                }
            }

            awaitClose {
                if (!finished.get()) {
                    runtime.cancelGeneration()
                }
                worker.cancel()
            }
        }
    }
}

/** Owner-facing GPU lane (top-level so it is unit-testable without a native runtime, mirroring
 *  [cacheTypeForOffload]). The provider pane's "Try GPU acceleration" switch sets [forceCpu];
 *  [propOverride] is `debug.nazarick.gpu_layers` where 0 means unset. force-CPU is the owner's
 *  authority and pins the pre-toggle CPU path exactly (0 layers). Inside GPU mode a non-zero
 *  bench value wins; unset offloads every layer the compiled-in OpenCL backend can take (-1).
 *  The full-offload path is device-proven on Adreno 830 (CI run 68: 24/24 layers on GPUOpenCL). */
internal fun resolveGpuLayers(forceCpu: Boolean, propOverride: Int): Int = when {
    forceCpu -> 0
    propOverride != 0 -> propOverride
    else -> -1
}
