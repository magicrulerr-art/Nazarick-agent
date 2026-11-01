package me.rerere.sdcpp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** One generation request, already resolved to concrete pixel dimensions. */
data class SdGenerationRequest(
    val modelFile: File,
    val prompt: String,
    val negativePrompt: String = "",
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfg: Float,
    val seed: Long = -1L,
    val batch: Int = 1,
    /** RGBA pixels for img2img; null means text-to-image. */
    val initImage: SdRaster? = null,
    val strength: Float = 0.75f,
    val onStep: SdProgressSink? = null,
) {
    init {
        require(width > 0 && height > 0) { "dimensions must be positive, got ${width}x$height" }
        require(steps > 0) { "steps must be positive, got $steps" }
    }
}

/**
 * Runs sd.cpp on this device.
 *
 * One loaded model at a time, guarded by a [Mutex]. That is not a shortcut, it is the memory
 * budget: a SD1.5 context is ~1.8 GB of weights and the fold shares RAM with everything else,
 * so a second concurrent context is an OOM with extra steps. [generate] loads on demand and
 * keeps the context warm, because loading dominates a single generation.
 *
 * The engine is deliberately ignorant of providers, settings and Room — it takes a [File] and
 * returns pixels, which is why it is testable without a device.
 */
class SdCppEngine(private val nThreads: Int = Runtime.getRuntime().availableProcessors()) {

    private val mutex = Mutex()
    private var handle = 0L
    private var loadedPath: String? = null

    val loadedModel: String? get() = loadedPath

    /**
     * Generate one batch. Returns the first image; the rest of the batch is dropped for now
     * (the JNI layer exposes items[0] only) — see [SdCppJni.nativeGenerate].
     *
     * @throws IllegalStateException when the model cannot be loaded or the engine fails.
     */
    suspend fun generate(request: SdGenerationRequest): SdRaster = mutex.withLock {
        ensureLoaded(request.modelFile, request.onStep)
        val raster = withContext(Dispatchers.Default) {
            SdCppJni.nativeGenerate(
                handle,
                request.prompt,
                request.negativePrompt,
                request.width,
                request.height,
                request.steps,
                request.cfg,
                request.seed,
                request.batch,
                request.initImage?.pixels,
                request.initImage?.width ?: 0,
                request.initImage?.height ?: 0,
                request.strength,
            )
        } ?: error("sd.cpp produced no image for \"${request.prompt.take(60)}\"")
        check(raster.isConsistent()) { "engine returned ${raster.channel}-channel ${raster.width}x${raster.height}" }
        raster
    }

    /** Stop the generation in flight, keeping whatever images it already finished. */
    fun cancel() {
        if (handle != 0L) SdCppJni.nativeCancel(handle)
    }

    /** Drop the loaded context. Call when leaving the screen that can generate. */
    suspend fun unload() = mutex.withLock {
        SdCppJni.nativeFreeModel(handle)
        handle = 0L
        loadedPath = null
    }

    private fun ensureLoaded(model: File, sink: SdProgressSink?) {
        val path = model.absolutePath
        if (handle != 0L && loadedPath == path) return
        if (handle != 0L) {
            SdCppJni.nativeFreeModel(handle)
            handle = 0L
            loadedPath = null
        }
        require(model.isFile) { "no model at $path — download it from the local models page first" }
        sdLog("loading ${model.name} on $nThreads threads")
        handle = SdCppJni.nativeLoadModel(path, nThreads, enableMmap = true, sink = sink)
        check(handle != 0L) { "sd.cpp could not load $path" }
        loadedPath = path
    }
}

/**
 * Pure geometry for a generation request.
 *
 * Diffusion models want dimensions divisible by 64 (8× VAE downsample × 2× patch), and asking
 * for anything else either fails or gets silently resized by the engine. Everything here rounds
 * inward to that grid so what the user sees is what runs.
 */
object SdRenderPlan {

    /** Round down to the 64px grid the VAE and patches agree on; never below one cell. */
    fun snap(value: Int): Int = (value / 64).coerceAtLeast(1) * 64

    /**
     * Square for [baseSize], 3:4 portrait, 4:3 landscape — all on the grid.
     *
     * Deliberately conservative ratios: SD1.5 was trained at 512² and drifts off its training
     * aspect far more visibly than it drifts off a step count.
     */
    fun square(baseSize: Int): Pair<Int, Int> = snap(baseSize) to snap(baseSize)

    fun portrait(baseSize: Int): Pair<Int, Int> {
        val long = snap(baseSize)
        return snap(long * 3 / 4) to long
    }

    fun landscape(baseSize: Int): Pair<Int, Int> {
        val long = snap(baseSize)
        return long to snap(long * 3 / 4)
    }

    /**
     * Seconds for one image at a measured cost per step.
     *
     * The honest version of "how long will this take": [msPerStep] comes from the previous
     * generation on *this* device, not from a spec sheet. Returns null before the first sample,
     * so the UI can say "measuring" instead of inventing a number.
     */
    fun estimateSeconds(steps: Int, msPerStep: Long?): Double? {
        if (msPerStep == null || msPerStep <= 0) return null
        return steps * msPerStep / 1000.0
    }
}
