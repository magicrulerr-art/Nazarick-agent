package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.BadgeKind
import me.rerere.rikkahub.data.ai.BrainBadge
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * US-009r: the chip on a bubble reads a message's stored `brainKind` through the same table as
 * the top bar. Since a message now stores the *detail* (`tomb:aura@host`, `edge:gemini-x`) and
 * the top bar stores the coarse kind, the label must key on the part before the first `:` - so
 * the same brain gets the same word from both, and neither one ever leaks the detail text into
 * the UI. Unrecognised input, including the `""` every pre-US-009r message decodes to, stays
 * label-less, which is what draws no chip.
 */
class BrainBadgeLabelTest {

    @Test
    fun `coarse kinds keep their wording`() {
        assertEquals("Tomb", brainBadgeLabel(BrainBadge.STORED_TOMB))
        assertEquals("Edge", brainBadgeLabel(BrainBadge.STORED_EDGE))
    }

    @Test
    fun `detail payloads label by the kind in front of the colon`() {
        assertEquals("Tomb", brainBadgeLabel("tomb:aura@127.0.0.1"))
        assertEquals("Edge", brainBadgeLabel("edge:gemini-x"))
        assertEquals("Tomb", brainBadgeLabel("tomb:aura@?"))
        assertEquals("Edge", brainBadgeLabel("edge:"))
    }

    @Test
    fun `a message's stored detail and the live top-bar kind label identically`() {
        val tomb = BrainBadge.storedDetail(
            Model(modelId = "test-model", displayName = "Test Model"),
            ProviderSetting.Nazarick(baseUrl = "https://tomb.example.com/v1", agentId = "aura"),
        )
        val edge = BrainBadge.storedDetail(
            Model(modelId = "google/gemini-x", displayName = "Gemini X"),
            ProviderSetting.OpenAI(),
        )
        assertEquals(brainBadgeLabel(BadgeKind.TOMB), brainBadgeLabel(tomb))
        assertEquals(brainBadgeLabel(BadgeKind.EDGE), brainBadgeLabel(edge))
        assertEquals("Tomb", brainBadgeLabel(tomb))
        assertEquals("Edge", brainBadgeLabel(edge))
    }

    @Test
    fun `legacy empty and unrecognised values yield no label`() {
        // "" is what every message persisted before US-009r decodes to => no chip (AC3).
        assertEquals("", brainBadgeLabel(""))
        assertEquals("", brainBadgeLabel("nazarick:aura@127.0.0.1"))
        assertEquals("", brainBadgeLabel(":tomb"))
        assertEquals("", brainBadgeLabel("Tomb"))
    }
}
