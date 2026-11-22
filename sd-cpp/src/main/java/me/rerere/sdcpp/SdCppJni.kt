package me.rerere.sdcpp

import android.util.Log

/**
 * A decoded raster straight out of the engine: raw RGBA, no container.
 *
 * Encoding to PNG happens in Kotlin via [PngEncoder] rather than through
 * [android.graphics.Bitmap] — this module compiles without the framework's graphics classes,
 * and a `ByteArray` has no honest route into `Bitmap.createBitmap` anyway. See that file for
 * why the byte order is worth stating out loud.
 */
class SdRaster(
    val width: Int,
    val height: Int,
    val channel: Int,
    val pixels: ByteArray,
) {
    /** True when the buffer matches the geometry — the engine promises 4-channel RGBA. */
    fun isConsistent(): Boolean = pixels.size == width * height * channel && channel == 4
}

/** Receives step ticks for the generation currently in flight. */
fun interface SdProgressSink {
    fun onStep(step: Int, steps: Int, seconds: Float)
}

/**
 * The entire JNI surface for sd.cpp, mirroring
 * [me.rerere.llamacpp.LlamaCppJni]: one object, every native contract in one place.
 */
object SdCppJni {

    init {
        System.loadLibrary("sdcpp_jni")
    }

    /**
     * Loads a diffusion model and returns an opaque handle, or 0 when the file cannot be read.
     *
     * Heavy: this maps the whole model. [me.rerere.sdcpp.SdCppEngine] keeps exactly one alive.
     */
    external fun nativeLoadModel(path: String, nThreads: Int, enableMmap: Boolean, sink: SdProgressSink?): Long

    /** Releases a handle from [nativeLoadModel]. Passing 0 is a no-op; double-free is UB. */
    external fun nativeFreeModel(handle: Long)

    /** Asks a running [nativeGenerate] to stop; the call returns the images it already finished. */
    external fun nativeCancel(handle: Long)

    /**
     * Runs one generation. Returns the first image of the batch, or null on failure.
     *
     * [initRgba] is optional: with a non-empty buffer the model does img2img at [strength]
     * instead of text-to-image.
     */
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        batch: Int,
        initRgba: ByteArray?,
        initWidth: Int,
        initHeight: Int,
        strength: Float,
    ): SdRaster?
}

internal fun sdLog(message: String) = Log.i("SdCppEngine", message)
