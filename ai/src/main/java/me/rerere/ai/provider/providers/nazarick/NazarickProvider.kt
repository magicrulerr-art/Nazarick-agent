package me.rerere.ai.provider.providers.nazarick

import kotlinx.coroutines.flow.Flow
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk

/**
 * US-001 scaffold provider for [ProviderSetting.Nazarick]. The wire protocol for chat is
 * US-004's job; until then every inference entry point refuses with one named message so a
 * straggler chat routed at a Nazarick model fails loudly instead of silently. listModels
 * returns empty so the model picker stays honest (nothing is offered that cannot answer).
 */
class NazarickProvider : Provider<ProviderSetting.Nazarick> {

    override suspend fun listModels(providerSetting: ProviderSetting.Nazarick): List<Model> =
        emptyList()

    override suspend fun generateText(
        providerSetting: ProviderSetting.Nazarick,
        messages: List<me.rerere.ai.ui.UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = error(NAZARICK_CHAT_PENDING)

    override suspend fun streamText(
        providerSetting: ProviderSetting.Nazarick,
        messages: List<me.rerere.ai.ui.UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = error(NAZARICK_CHAT_PENDING)

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: me.rerere.ai.provider.ImageGenerationParams,
    ): Flow<ImageGenerationItem> = error(NAZARICK_CHAT_PENDING)

    companion object {
        const val NAZARICK_CHAT_PENDING = "Nazarick chat lands in US-004"
    }
}
