package me.rerere.localdream

import android.content.Context
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
        val (width, height) = dimensions(params.aspectRatio)

        // Loading is idempotent per directory (engine short-circuits when already warm).
        // `auto` here on purpose: a chip that cannot hold the QNN context gets MNN with a
        // log line instead of a red error, which is how OGAM ships it and why their NPU
        // path survives weird ROMs.
        engine.load(dir, requestedBackend = "auto")

        var png: ByteArray? = null
        engine.generate(
            LocalDreamRequest(
                prompt = params.prompt,
                // ImageGenerationParams carries no negative prompt today; the engine takes
                // its own default (empty), and the field stays wired for when the shared
                // params grow one.
                steps = entry.defaultSteps,
                cfg = entry.defaultCfg,
                width = width,
                height = height,
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
                data = Base64.getEncoder().encodeToString(bytes),
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
