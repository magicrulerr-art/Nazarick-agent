package me.rerere.localdream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.locallm.ModelInstall

/**
 * QNN/MNN image generation, on-device, through the local-dream subprocess engine.
 *
 * Shaped exactly like `SdCppProvider` (which see for the house posture: text half refuses
 * loudly, the setting travels in with each call, mutable state lives in the shared engine).
 * The one deliberate difference: model rows key on the **catalog id**, not a file name,
 * because a local-dream model is an extracted directory, not one file.
 */
class LocalDreamProvider(
    private val context: Context,
    private val engine: LocalDreamEngine = LocalDreamEngine(context),
) : Provider<ProviderSetting> {

    override suspend fun listModels(providerSetting: ProviderSetting): List<Model> =
        providerSetting.models

    override suspend fun generateText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): me.rerere.ai.provider.TextGenerationResult =
        error("local-dream is an image engine; nothing to chat with")

    override suspend fun streamText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow { error("local-dream is an image engine; nothing to stream") }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = flow {
        val entry = resolveEntry(params.model)
        val dir = modelDir(entry)
        val spec = effectiveSpec(params, entry)

        // Loading is idempotent per directory (engine short-circuits when already warm).
        // "auto" on purpose (the v0.7 default): a chip that cannot hold the QNN context gets
        // MNN with a log line instead of a red error. The v0.8 mode lever may override it —
        // "qnn" then fails loudly instead of falling back, which is what "force NPU" means.
        engine.load(dir, requestedBackend = spec.backend)

        var png: ByteArray? = null
        engine.generate(
            LocalDreamRequest(
                prompt = params.prompt,
                // ImageGenerationParams carries no negative prompt today; the engine takes
                // its own default (empty), and the field stays wired for when the shared
                // params grow one.
                steps = spec.steps,
                cfg = spec.cfg,
                seed = spec.seed,
                width = spec.width,
                height = spec.height,
            ),
        ).collect { event ->
            when (event) {
                is LocalDreamEvent.Progress -> Unit // preview UI is a later ticket; do not drop the flow
                is LocalDreamEvent.Completed -> png = event.pngBytes
            }
        }
        val bytes = png ?: error("generation cancelled or ended without a result")
        emit(
            ImageGenerationItem(
                data = Base64.getEncoder().encodeToString(upscalePng(bytes, spec.upscale)),
                mimeType = "image/png",
            ),
        )
    }

    /** The imggen page's cancel button ends up here (mirrors SdCppProvider.cancelGeneration). */
    fun cancelGeneration() = engine.cancel()

    /** Rows resolve by catalog id first, then by zip name, then by what is on disk. */
    private fun resolveEntry(model: Model): QnnModelEntry =
        LocalDreamCatalog.entryById(model.modelId)
            ?: LocalDreamCatalog.entryFor(model.modelId)
            ?: LocalDreamCatalog.models.firstOrNull { modelDir(it).isDirectory }
            ?: LocalDreamCatalog.models.first()

    /** `local-models/localdream/<entry-id>/` — the installer's landing directory. */
    private fun modelDir(entry: QnnModelEntry): File =
        File(ModelInstall.localModelsDir(context), "${LocalDreamCatalog.RUNTIME_DIRNAME}/${entry.id}")
}

/**
 * Aspect ratios at SD1.5's native playground. The compiled QNN contexts are 512-class
 * graphs; the engine's own support table caps every side at 512, so the choices are the
 * three nearest points on it rather than the free arithmetic sd-cpp can get away with.
 */
internal fun dimensions(aspect: ImageAspectRatio): Pair<Int, Int> = when (aspect) {
    ImageAspectRatio.PORTRAIT -> 384 to 512
    ImageAspectRatio.LANDSCAPE -> 512 to 384
    else -> 512 to 512
}

/** The resolved knobs for one generation, after levers, clamps and catalog defaults. */
internal data class DreamSpec(
    val steps: Int,
    val cfg: Double,
    val seed: Int,
    val width: Int,
    val height: Int,
    val upscale: Int,
    val backend: String,
)

/**
 * v0.8 sampling levers -> engine request values (pure, unit-tested). A null lever always
 * falls back to exactly what v0.7 sent: catalog steps/cfg, aspect-derived size, random seed,
 * no upscale, "auto" backend. Non-null levers are clamped to the engine's sane envelope —
 * the /generate contract is a string dump with no server-side validation worth trusting,
 * and a coerced generation beats a corrupt one. A seed <= 0 keeps the engine's own
 * "0 means random" convention, so the UI's -1 needs no special case below.
 */
internal fun effectiveSpec(params: ImageGenerationParams, entry: QnnModelEntry): DreamSpec {
    val (aw, ah) = dimensions(params.aspectRatio)
    return DreamSpec(
        steps = params.steps?.coerceIn(1, 150) ?: entry.defaultSteps,
        cfg = params.guidanceScale?.coerceIn(0.0, 30.0) ?: entry.defaultCfg,
        seed = params.seed?.takeIf { it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0,
        width = params.width ?: aw,
        height = params.height ?: ah,
        upscale = params.upscaleFactor?.coerceIn(1, 4) ?: 1,
        backend = params.engineBackend?.lowercase()
            ?.takeIf { it == "auto" || it == "qnn" || it == "mnn" } ?: "auto",
    )
}

/**
 * Post-generation resize by [factor] (the upscale lever). Honest scope: bilinear resize,
 * not an AI upscaler — the QNN/MNN engine has none, and a bigger honest pixel count beats
 * a magic one. Best-effort by design: any decode/encode hiccup returns the original PNG,
 * because losing the upscale must never lose the generation.
 */
internal fun upscalePng(png: ByteArray, factor: Int): ByteArray {
    if (factor <= 1) return png
    val src = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return png
    var dst: Bitmap? = null
    return try {
        dst = Bitmap.createScaledBitmap(src, src.width * factor, src.height * factor, true)
        val out = ByteArrayOutputStream()
        dst.compress(Bitmap.CompressFormat.PNG, 100, out)
        val scaled = out.toByteArray()
        if (scaled.isNotEmpty()) scaled else png
    } catch (e: Exception) {
        android.util.Log.w("LocalDreamProvider", "upscale x$factor failed, keeping original", e)
        png
    } finally {
        if (dst != null && dst !== src) dst.recycle()
        src.recycle()
    }
}
