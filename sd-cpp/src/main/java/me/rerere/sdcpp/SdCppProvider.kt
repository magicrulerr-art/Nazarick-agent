package me.rerere.sdcpp

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.ai.core.TokenUsage
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
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Local image generation, on-device, through sd.cpp.
 *
 * Stateless like every other [Provider]: the setting travels in with each call, and all
 * mutable weight lives in [engine], which is shared so a second dispatch does not pay the
 * multi-second model load again.
 *
 * The text half refuses loudly — an image model asked to chat is a misconfiguration the user
 * should hear about, not a silent empty reply. `generate_image` and the imggen page are the
 * only callers this class is designed for.
 *
 * Typed against the base [ProviderSetting] rather than a `SdCppLocal` variant on purpose: the
 * sealed class gets its own case in the settings pass (every `when` over it must be extended,
 * fifteen sites), and nothing here needs that variant to work in the meantime.
 */
class SdCppProvider(
    private val context: Context,
    private val engine: SdCppEngine = SdCppEngine(),
) : Provider<ProviderSetting> {

    /**
     * The settings-owned list, like every other local provider: models arrive when the pane
     * installs a file and are registered through [SdModelInstaller], so there is no remote
     * catalogue to re-fetch at generation time.
     */
    override suspend fun listModels(providerSetting: ProviderSetting): List<Model> =
        providerSetting.models

    override suspend fun generateText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): me.rerere.ai.provider.TextGenerationResult =
        error("sd.cpp is an image engine; nothing to chat with")

    override suspend fun streamText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow { error("sd.cpp is an image engine; nothing to stream") }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = flow {
        val entry = resolveEntry(params.model)
        val (width, height) = dimensions(entry.baseSize, params.aspectRatio)
        val raster = engine.generate(
            SdGenerationRequest(
                modelFile = modelFile(entry),
                prompt = params.prompt,
                width = width,
                height = height,
                steps = entry.defaultSteps,
                cfg = entry.defaultCfg,
                batch = params.numOfImages.coerceAtLeast(1),
            ),
        )
        emit(ImageGenerationItem(data = raster.toPngBase64(), mimeType = "image/png"))
    }

    /** Ask [engine] to stop; the imggen page's cancel button ends up here via coroutine cancel. */
    fun cancelGeneration() = engine.cancel()

    /**
     * A model row names its weights through [Model.modelId] — the same contract llama.cpp uses,
     * where modelId is the GGUF's file name. Catalog id is accepted too, because the curated
     * picker creates rows with it.
     */
    private fun resolveEntry(model: Model): SdModelEntry =
        SdCppCatalog.entryFor(model.modelId)
            ?: SdCppCatalog.entryById(model.modelId)
            ?: installedEntryOrNull(model)
            ?: SdCppCatalog.models.first()

    /** A hand-copied GGUF outside the catalogue still has a file on disk; use it by name. */
    private fun installedEntryOrNull(model: Model): SdModelEntry? {
        val fileName = model.modelId.substringAfterLast('/')
        val file = File(ModelInstall.localModelsDir(context), "${SdCppCatalog.RUNTIME_DIRNAME}/$fileName")
        return if (file.isFile) {
            SdModelEntry(
                id = fileName,
                displayName = model.displayName.ifBlank { fileName },
                fileName = fileName,
                url = "",
                sizeBytes = file.length(),
                baseSize = 512,
                defaultSteps = 20,
                defaultCfg = 7.0f,
            )
        } else null
    }

    /** Where a catalog file lives: `local-models/sdcpp/<fileName>`, beside every other runtime. */
    private fun modelFile(entry: SdModelEntry): File =
        File(ModelInstall.localModelsDir(context), "${SdCppCatalog.RUNTIME_DIRNAME}/${entry.fileName}")

    private fun dimensions(baseSize: Int, aspect: ImageAspectRatio): Pair<Int, Int> = when (aspect) {
        ImageAspectRatio.PORTRAIT -> SdRenderPlan.portrait(baseSize)
        ImageAspectRatio.LANDSCAPE -> SdRenderPlan.landscape(baseSize)
        else -> SdRenderPlan.square(baseSize)
    }
}

/** RGBA out of the engine, PNG out of [PngEncoder] — see why that is not Bitmap's job. */
internal fun SdRaster.toPngBase64(): String =
    java.util.Base64.getEncoder().encodeToString(PngEncoder.encode(pixels, width, height, channel))
