package me.rerere.localdream

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
// PngEncoder lives in this package since the v0.6 sd.cpp retirement (was me.rerere.sdcpp).
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One generation's worth of knobs. Defaults are OGAM's shipped ones (20 steps, CFG 7.5). */
data class LocalDreamRequest(
    val prompt: String,
    val negativePrompt: String = "",
    val steps: Int = 20,
    val cfg: Double = 7.5,
    /** `0` means "ask for a random one", matching the engine's own convention. */
    val seed: Int = 0,
    val width: Int = 512,
    val height: Int = 512,
    /** Emit a preview event every N steps; the engine renders previews at request size. */
    val previewStride: Int = 2,
)

/** What a generation streams back: step previews and, once, the finished PNG. */
sealed interface LocalDreamEvent {
    data class Progress(val step: Int, val totalSteps: Int, val previewPng: ByteArray?) : LocalDreamEvent
    data class Completed(
        val pngBytes: ByteArray,
        val width: Int,
        val height: Int,
        val seed: Int,
        val elapsedMs: Long,
    ) : LocalDreamEvent
}

/**
 * On-device diffusion through the `stable_diffusion_core` engine — the QNN/MNN path
 * transplanted from Off Grid AI's OGAM app (`off-grid-ai/OGAM` @ `0957b72`, MIT;
 * see `local-dream/NOTICE-third-party.md` and the blob hashes beside this module).
 *
 * The architecture is the interesting part and it is deliberately *not* ggml:
 *
 *  - `libstable_diffusion_core.so` is a self-contained diffusion **HTTP server**, spawned as
 *    a subprocess and talked to on `127.0.0.1:18081`. A crash inside Qualcomm's QNN stack
 *    costs us the process, not the app — same crash-isolation posture as our other engines,
 *    reached by a much stupider (better) mechanism.
 *  - The unet never "runs" as an interpreted graph: it is a **pre-compiled HTP context**
 *    (`unet.bin`, compiled per SoC family upstream by xororz) plus split clip/vae graphs.
 *    That is the route around the wall we proved on 2026-11-08 (`floor` hexagon records):
 *    ggml-hexagon has no CONV_2D, so sd.cpp cannot put UNets on the NPU. These models never
 *    ask the DSP to interpret anything, so the wall is not there.
 *  - MNN is the GPU/CPU fallback backend (`--cpu` flips the binary into it), which keeps one
 *    binary and two backends rather than two engines.
 *
 * The port keeps the RN bridge's *behaviour* — command line, environment, health waits,
 * SSE shape, QNN→MNN startup fallback — and drops its plumbing (no Promises, no events:
 * this class is called like any other engine and streams a [Flow]).
 */
class LocalDreamEngine(private val context: Context) {

    companion object {
        private const val TAG = "LocalDreamEngine"
        const val EXECUTABLE_NAME = "libstable_diffusion_core.so"
        const val RUNTIME_DIR = "runtime_libs"
        const val SERVER_PORT = 18081
        private const val MNN_OPENCL_TUNING_MODE = "WIDE"

        /** Any SM-prefixed Qualcomm chip is HTP-capable in this engine's book (OGAM rule). */
        fun isNpuSupported(socModel: String): Boolean = socModel.startsWith("SM")

        fun normalizeBackend(raw: String?): String = when (raw?.lowercase()) {
            "mnn", "cpu" -> "mnn"
            "qnn", "npu" -> "qnn"
            else -> "auto"
        }

        /**
         * Find the directory that actually holds the weights. Zip archives keep their internal
         * folder (`ChilloutMix.zip` → `<dir>/ChilloutMix/unet.bin`), so the installed root is
         * not necessarily the model dir; search down to depth 3 for the backend's marker file.
         */
        fun resolveModelDir(dir: File, isCpu: Boolean): File? {
            val markerFile = if (isCpu) "unet.mnn" else "unet.bin"
            if (File(dir, markerFile).exists()) return dir

            fun searchDir(current: File, depth: Int): File? {
                if (depth > 3) return null
                current.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
                    if (File(subDir, markerFile).exists()) return subDir
                    searchDir(subDir, depth + 1)?.let { return it }
                }
                return null
            }
            return searchDir(dir, 0)
        }

        /** Backend selection: NPU-capable device with QNN weights wins; MNN is the floor. */
        fun pickBackend(
            cpuModelDir: File?,
            qnnModelDir: File?,
            npuSupported: Boolean,
        ): Pair<String, File>? = when {
            qnnModelDir != null && npuSupported -> "qnn" to qnnModelDir
            cpuModelDir != null -> "mnn" to cpuModelDir
            qnnModelDir != null -> "qnn" to qnnModelDir
            else -> null
        }

        /**
         * Command line for the subprocess. Two invariants here are segfault-shaped and were
         * ported verbatim from OGAM's bridge comments:
         *
         *  - **Always pass `clip.mnn`**, even when only `clip_v2.mnn` exists: the binary
         *    auto-detects `clip_v2` from a path *ending in* `clip.mnn` and loads
         *    `pos_emb.bin`/`token_emb.bin` with it. Pointing at `clip_v2.mnn` directly bypasses
         *    that and segfaults. ("clip_v2" is model-format v2, not CLIP-L v2 — embeddings
         *    stay 768-d for everything in the xororz zoo.)
         *  - **`--cpu` is what selects MNN.** Without it the binary assumes QNN and crashes on
         *    devices without a loadable context model.
         */
        fun buildCommand(
            executable: File,
            modelDir: File,
            runtimeDir: File,
            isCpu: Boolean,
        ): List<String> {
            val clipName = "clip.mnn"
            return if (isCpu) {
                mutableListOf(
                    executable.absolutePath,
                    "--clip", File(modelDir, clipName).absolutePath,
                    "--unet", File(modelDir, "unet.mnn").absolutePath,
                    "--vae_decoder", File(modelDir, "vae_decoder.mnn").absolutePath,
                    "--tokenizer", File(modelDir, "tokenizer.json").absolutePath,
                    "--port", SERVER_PORT.toString(),
                    "--text_embedding_size", "768",
                    "--cpu",
                ).also { cmd ->
                    val vaeEncoder = File(modelDir, "vae_encoder.mnn")
                    if (vaeEncoder.exists()) cmd.addAll(listOf("--vae_encoder", vaeEncoder.absolutePath))
                }
            } else {
                val hasMnnClip = File(modelDir, "clip.mnn").exists() || File(modelDir, "clip_v2.mnn").exists()
                val clipFile = if (hasMnnClip) clipName else "clip.bin"
                mutableListOf(
                    executable.absolutePath,
                    "--clip", File(modelDir, clipFile).absolutePath,
                    "--unet", File(modelDir, "unet.bin").absolutePath,
                    "--vae_decoder", File(modelDir, "vae_decoder.bin").absolutePath,
                    "--tokenizer", File(modelDir, "tokenizer.json").absolutePath,
                    "--backend", File(runtimeDir, "libQnnHtp.so").absolutePath,
                    "--system_library", File(runtimeDir, "libQnnSystem.so").absolutePath,
                    "--port", SERVER_PORT.toString(),
                    "--text_embedding_size", "768",
                ).also { cmd ->
                    if (hasMnnClip) cmd.add("--use_cpu_clip")
                    val vaeEncoder = File(modelDir, "vae_encoder.bin")
                    if (vaeEncoder.exists()) cmd.addAll(listOf("--vae_encoder", vaeEncoder.absolutePath))
                }
            }
        }

        /**
         * Process environment. The DSP-side skeletons (`libQnnHtpV73Skel.so` & friends) are
         * found through `ADSP_LIBRARY_PATH` — the same FastRPC convention our sd-cpp hexagon
         * lane learned on run 130, arriving here by a different road.
         */
        fun buildEnvironment(runtimeDir: File): Map<String, String> {
            val systemLibPaths = mutableListOf(
                runtimeDir.absolutePath,
                "/system/lib64",
                "/vendor/lib64",
                "/vendor/lib64/egl",
            )
            try {
                val maliSymlink = File("/system/vendor/lib64/egl/libGLES_mali.so")
                if (maliSymlink.exists()) {
                    val parts = maliSymlink.canonicalPath.split("/")
                    val soc = parts.getOrNull(parts.size - 2)
                    if (soc != null) {
                        listOf("/vendor/lib64/$soc", "/vendor/lib64/egl/$soc")
                            .filterNot { it in systemLibPaths }
                            .forEach { systemLibPaths.add(it) }
                    }
                }
            } catch (_: IOException) {
                // Vendor layout we don't know; the four defaults are what shipped devices have.
            }
            return mapOf(
                "LD_LIBRARY_PATH" to systemLibPaths.joinToString(":"),
                "DSP_LIBRARY_PATH" to runtimeDir.absolutePath,
                "ADSP_LIBRARY_PATH" to runtimeDir.absolutePath,
                "MNN_OPENCL_TUNING" to MNN_OPENCL_TUNING_MODE,
            )
        }

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** POST /generate body — fields and spellings are the binary's HTTP contract. */
        internal fun generationJson(request: LocalDreamRequest): String {
            val seed = if (request.seed != 0) request.seed
            else (Math.random() * Int.MAX_VALUE).toInt()
            val body = buildMap {
                put("prompt", "\"" + request.prompt.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                put("negative_prompt", "\"" + request.negativePrompt.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                put("steps", request.steps.toString())
                put("cfg", request.cfg.toString())
                put("seed", seed.toString())
                put("width", request.width.toString())
                put("height", request.height.toString())
                put("scheduler", "\"dpm\"")
                put("show_diffusion_process", "true")
                put("show_diffusion_stride", request.previewStride.coerceAtLeast(1).toString())
            }
            return body.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
        }

        /** One SSE `data:` payload, typed. Anything unparseable or unknown-type is null. */
        internal fun parseSseData(payload: String): SseEvent? = try {
            val obj = json.parseToJsonElement(payload).jsonObject
            when (obj.stringOrNull("type")) {
                "progress" -> SseEvent.Progress(
                    step = obj.intOrNull("step") ?: return null,
                    totalSteps = obj.intOrNull("total_steps") ?: return null,
                    previewBase64 = obj.stringOrNull("image").orEmpty(),
                )
                "complete" -> SseEvent.Complete(
                    imageBase64 = obj.stringOrNull("image") ?: return null,
                    width = obj.intOrNull("width") ?: return null,
                    height = obj.intOrNull("height") ?: return null,
                    seed = obj.intOrNull("seed") ?: 0,
                    elapsedMs = obj.longOrNull("generation_time_ms") ?: 0L,
                )
                else -> null
            }
        } catch (_: Exception) {
            null
        }

        // NOTE: the SSE event types live at file level as `SseEvent` — a companion-nested
        // class is not reachable as `LocalDreamEngine.SseEvent`, which the run-132 CI caught
        // the honest way.
        /** base64 RGB3 → PNG bytes, via the sd-cpp container writer (channel 3). */
        internal fun rgbBase64ToPng(base64: String, width: Int, height: Int): ByteArray {
            // MIME decoder, not the strict one: some server stacks line-wrap long fields and
            // "looks like a model bug" out of a decoder strictness is a week lost.
            val rgb = Base64.getMimeDecoder().decode(base64)
            val expected = width * height * 3
            require(rgb.size == expected) {
                "RGB payload ${rgb.size} bytes != ${width}x$height x3 ($expected)"
            }
            return PngEncoder.encode(rgb, width, height, 3)
        }
    }

    // ---- runtime state ---------------------------------------------------------------------

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // a generation can take minutes; cancel is explicit
        .build()

    /** Health probes get OGAM's shipped 1s budgets — the warm-up loop must never hang. */
    private val healthClient by lazy {
        client.newBuilder().connectTimeout(1, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS).build()
    }

    @Volatile private var serverProcess: Process? = null
    @Volatile private var currentModelPath: String? = null
    @Volatile private var currentBackend: String? = null
    @Volatile private var isServerReady = false
    @Volatile private var activeCall: Call? = null
    private val generationCancelled = AtomicBoolean(false)

    val loadedBackend: String? get() = currentBackend
    val isReady: Boolean get() = isServerReady && serverProcess?.isAlive == true

    /** filesDir/runtime_libs — QNN runtime libs extracted from assets, exec bits set. */
    private fun prepareRuntimeDir(): File {
        val runtimeDir = File(context.filesDir, RUNTIME_DIR).apply { mkdirs() }
        try {
            context.assets.list("qnnlibs")?.forEach { name ->
                val target = File(runtimeDir, name)
                // OGAM's shipped check, ported: a freshly opened asset stream reports the
                // asset's full length through available(), which is all the skip-if-whole
                // test needs. Anything else about it would be inventing a checksum layer.
                val assetSize = context.assets.open("qnnlibs/$name").use { it.available().toLong() }
                if (!target.exists() || target.length() != assetSize) {
                    context.assets.open("qnnlibs/$name").use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    Log.i(TAG, "extracted qnnlib $name")
                }
                target.setReadable(true, true)
                target.setExecutable(true, true)
            }
        } catch (e: IOException) {
            Log.w(TAG, "no qnnlibs in assets (MNN-only mode): ${e.message}")
        }
        return runtimeDir
    }

    private fun resolveExecutable(runtimeDir: File): File? {
        val native = File(context.applicationInfo.nativeLibraryDir, EXECUTABLE_NAME)
        if (native.isFile) return native
        val extracted = File(runtimeDir, EXECUTABLE_NAME)
        if (extracted.isFile) {
            extracted.setExecutable(true, true)
            return extracted
        }
        return null
    }

    /**
     * Boot the subprocess for [modelRoot] (the *installed* directory; the real model dir is
     * resolved from it). `auto` prefers QNN on HTP-capable chips and **falls back to MNN/CPU**
     * when the context model refuses to load — the fallback is the whole reason MNN packs are
     * cheap insurance, so it is ported, not improvised.
     */
    suspend fun load(modelRoot: File, requestedBackend: String = "auto"): String {
        val normalized = normalizeBackend(requestedBackend)
        val cpuDir = resolveModelDir(modelRoot, isCpu = true)
        val qnnDir = resolveModelDir(modelRoot, isCpu = false)
            ?: error("no QNN context model (unet.bin) under ${modelRoot.absolutePath}")
        val npuSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            isNpuSupported(Build.SOC_MODEL) else false
        val (backend, modelDir) = when (normalized) {
            "mnn" -> (cpuDir ?: error("no MNN model (unet.mnn) under ${modelRoot.absolutePath}"))
                .let { "mnn" to it }
            "qnn" -> "qnn" to qnnDir
            else -> pickBackend(cpuDir, qnnDir, npuSupported)
                ?: error("no local-dream weights under ${modelRoot.absolutePath}")
        }

        if (currentModelPath == modelDir.absolutePath && isReady) return backend
        stop()
        val runtimeDir = prepareRuntimeDir()

        if (backend == "qnn") {
            val ok = runCatching { startServer(modelDir, runtimeDir, "qnn", isCpu = false) }
                .onFailure { Log.w(TAG, "QNN start failed: ${it.message}") }
                .getOrDefault(false)
            if (ok) {
                currentModelPath = modelDir.absolutePath
                currentBackend = "qnn"
                return "qnn"
            }
            if (normalized == "qnn") error("QNN backend failed to start (explicit request, no fallback)")
            stop()
            val cpuModel = cpuDir ?: error("QNN failed and no MNN fallback model exists")
            startServer(cpuModel, runtimeDir, "mnn", isCpu = true).also {
                if (!it) error("both QNN and MNN/CPU backends failed for ${modelRoot.name}")
            }
            currentModelPath = cpuModel.absolutePath
            currentBackend = "mnn"
            Log.i(TAG, "fell back to MNN/CPU backend")
            return "mnn"
        }

        val ok = startServer(modelDir, runtimeDir, "mnn", isCpu = true)
        if (!ok) error("MNN backend failed to start for ${modelRoot.name}")
        currentModelPath = modelDir.absolutePath
        currentBackend = "mnn"
        return "mnn"
    }

    private suspend fun startServer(
        modelDir: File,
        runtimeDir: File,
        backend: String,
        isCpu: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        val executable = resolveExecutable(runtimeDir)
            ?: run { Log.e(TAG, "$EXECUTABLE_NAME not found in nativeLibraryDir or $RUNTIME_DIR"); return@withContext false }
        val command = buildCommand(executable, modelDir, runtimeDir, isCpu)
        Log.i(TAG, "spawning: ${command.joinToString(" ")}")
        val process = ProcessBuilder(command)
            .directory(executable.parentFile)
            .redirectErrorStream(true)
            .apply { environment().putAll(buildEnvironment(runtimeDir)) }
            .start()
        serverProcess = process
        isServerReady = false
        drainStdout(process)

        // QNN context-load warmup and MNN CPU setup are both slow first boots; these are
        // OGAM's shipped budgets, not guesses.
        val timeoutMs = if (isCpu) 180_000L else 120_000L
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                Log.e(TAG, "server died while warming up (exit ${runCatching { process.exitValue() }.getOrNull()})")
                return@withContext false
            }
            if (healthOk()) {
                isServerReady = true
                Log.i(TAG, "server ready on 127.0.0.1:$SERVER_PORT (backend $backend)")
                return@withContext true
            }
            delay(500)
        }
        Log.e(TAG, "server not ready within ${timeoutMs / 1000}s")
        false
    }

    private fun drainStdout(process: Process) {
        Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { Log.i(TAG, "[server] $it") }
            } catch (_: IOException) {
                // process gone; the monitor below reports the exit.
            }
        }.apply { name = "localdream-stdout"; isDaemon = true }.start()
    }

    private fun healthOk(): Boolean = try {
        val req = Request.Builder().url("http://127.0.0.1:$SERVER_PORT/health").get().build()
        healthClient.newCall(req).execute().use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }

    /** Stream a generation. The flow completes after [LocalDreamEvent.Completed]. */
    fun generate(request: LocalDreamRequest): Flow<LocalDreamEvent> = flow {
        if (!isReady) error("local-dream server is not running; load a model first")
        generationCancelled.set(false)
        val body = generationJson(request).toRequestBody(JSON_MEDIA)
        val httpRequest = Request.Builder()
            .url("http://127.0.0.1:$SERVER_PORT/generate")
            .header("Accept", "text/event-stream")
            .post(body)
            .build()
        val call = client.newCall(httpRequest)
        activeCall = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val err = runCatching { response.body?.string()?.take(500) }.getOrNull()
                    error("generate HTTP ${response.code}: ${err ?: response.message}")
                }
                val source = response.body?.source() ?: error("generate returned no body")
                var completed: SseEvent.Complete? = null
                while (true) {
                    val line = runCatching { source.readUtf8Line() }.getOrElse {
                        // call.cancel() surfaces as an IOException mid-stream.
                        if (generationCancelled.get()) return@flow else throw it
                    } ?: break
                    if (!line.startsWith("data:")) continue
                    when (val event = parseSseData(line.substring(5).trim())) {
                        is SseEvent.Progress -> emit(
                            LocalDreamEvent.Progress(
                                event.step, event.totalSteps,
                                event.previewBase64.takeIf { it.isNotEmpty() }?.let {
                                    runCatching { rgbBase64ToPng(it, request.width, request.height) }.getOrNull()
                                },
                            ),
                        )
                        is SseEvent.Complete -> completed = event
                        null -> Unit
                    }
                }
                val done = completed
                    ?: if (generationCancelled.get()) return@flow
                    else error("stream ended without a complete event")
                emit(
                    LocalDreamEvent.Completed(
                        pngBytes = rgbBase64ToPng(done.imageBase64, done.width, done.height),
                        width = done.width,
                        height = done.height,
                        seed = done.seed,
                        elapsedMs = done.elapsedMs,
                    ),
                )
            }
        } finally {
            activeCall = null
        }
    }.flowOn(Dispatchers.IO)

    /** Abort the in-flight generation (mid-SSE); safe to call when idle. */
    fun cancel() {
        generationCancelled.set(true)
        runCatching { activeCall?.cancel() }
    }

    /** Kill the subprocess. The model may be reloaded later; nothing persists. */
    suspend fun stop() = withContext(Dispatchers.IO) {
        runCatching { activeCall?.cancel() }
        serverProcess?.let { p ->
            p.destroy()
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        serverProcess = null
        currentModelPath = null
        currentBackend = null
        isServerReady = false
    }
}

/**
 * One SSE `data:` payload from `/generate`, typed per the engine's HTTP contract. File-level
 * on purpose: companion-nested types are not reachable through the class name from other
 * files (run 132's `Unresolved reference 'SseEvent'`), and these are wire-format types, not
 * engine state.
 */
internal sealed interface SseEvent {
    data class Progress(val step: Int, val totalSteps: Int, val previewBase64: String) : SseEvent
    data class Complete(
        val imageBase64: String,
        val width: Int,
        val height: Int,
        val seed: Int,
        val elapsedMs: Long,
    ) : SseEvent
}

private fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNullSafe()

private fun JsonObject.intOrNull(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
private fun JsonObject.longOrNull(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? = try {
    content
} catch (_: Exception) {
    null
}
