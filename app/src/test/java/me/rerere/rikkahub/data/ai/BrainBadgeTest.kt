package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * US-009: the answering-brain badge rule.
 *
 * Nazarick-branded providers answer from the Tomb; every other provider answers
 * from the Edge; a chat with no model or no provider has no badge at all.
 */
class BrainBadgeTest {

    private val model = Model(modelId = "test-model", displayName = "Test Model")

    @Test
    fun `nazarick provider badges as tomb`() {
        assertEquals(BadgeKind.TOMB, BrainBadge.kind(model, ProviderSetting.Nazarick()))
    }

    @Test
    fun `openai provider badges as edge`() {
        assertEquals(BadgeKind.EDGE, BrainBadge.kind(model, ProviderSetting.OpenAI()))
    }

    @Test
    fun `google provider badges as edge`() {
        assertEquals(BadgeKind.EDGE, BrainBadge.kind(model, ProviderSetting.Google()))
    }

    @Test
    fun `missing model has no badge`() {
        assertEquals(BadgeKind.NONE, BrainBadge.kind(null, ProviderSetting.Nazarick()))
        assertEquals(BadgeKind.NONE, BrainBadge.kind(null, ProviderSetting.OpenAI()))
    }

    @Test
    fun `missing provider has no badge`() {
        assertEquals(BadgeKind.NONE, BrainBadge.kind(model, null))
    }

    @Test
    fun `missing both has no badge`() {
        assertEquals(BadgeKind.NONE, BrainBadge.kind(null, null))
    }
}
