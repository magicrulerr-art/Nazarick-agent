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

    // US-009r: the finalize site that writes UIMessage.brainKind lives in ChatService and is
    // not JVM-testable, so the pure mapping it uses is covered here instead.
    @Test
    fun `kinds store as the policy literals`() {
        assertEquals("tomb", BrainBadge.stored(BadgeKind.TOMB))
        assertEquals("edge", BrainBadge.stored(BadgeKind.EDGE))
    }

    @Test
    fun `no kind stores empty which is also the legacy value`() {
        assertEquals("", BrainBadge.stored(BadgeKind.NONE))
    }

    @Test
    fun `end to end mapping from provider to stored kind`() {
        assertEquals("tomb", BrainBadge.stored(BrainBadge.kind(model, ProviderSetting.Nazarick())))
        assertEquals("edge", BrainBadge.stored(BrainBadge.kind(model, ProviderSetting.OpenAI())))
        // No resolvable brain => "" => the bubble renders no chip (AC3).
        assertEquals("", BrainBadge.stored(BrainBadge.kind(null, null)))
    }
}
