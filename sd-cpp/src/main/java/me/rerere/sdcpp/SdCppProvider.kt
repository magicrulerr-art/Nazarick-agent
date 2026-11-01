package me.rerere.sdcpp

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
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

    override suspend fun listModels(providerSetting: ProviderSetting): List<Model> =
        SdCppCatalog.models.map { entry ->
            Model(
                id = entry.id,
                name = entry.displayName,
                providerId = SdCppCatalog.PROVIDER_ID,
            )
        }

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

    private fun resolveEntry(model: Model): SdModelEntry =
        SdCppCatalog.entryById(model.id)
            ?: SdCppCatalog.entryFor(model.id)
            ?: SdCppCatalog.models.first()

    /** Where a catalog file lives: `local-models/sdcpp/<fileName>`, beside every other runtime. */
    private fun modelFile(entry: SdModelEntry): File =
        File(ModelInstall.localModelsDir(context), "${SdCppCatalog.RUNTIME_DIRNAME}/${entry.fileName}")

    private fun dimensions(baseSize: Int, aspect: ImageAspectRatio): Pair<Int, Int> = when (aspect) {
        ImageAspectRatio.PORTRAIT -> SdRenderPlan.portrait(baseSize)
        ImageAspectRatio.LANDSCAPE -> SdRenderPlan.landscape(baseSize)
        else -> SdRenderPlan.square(baseSize)
    }
}

/** RGBA out of the engine, PNG out of the platform's own encoder. */
internal fun SdRaster.toPngBase64(): String {
    val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    val out = ByteArrayOutputStream()
    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG compression failed" }
    bitmap.recycle()
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}
