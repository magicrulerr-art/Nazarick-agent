package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * US-009r (AC1 granularity): the per-message record names the brain, not just the side of the
 * network it came from - `tomb:<agentId>@<host>` for the fleet, `edge:<modelId tail>` for
 * everything else, and the bare kind when there is nothing more to say.
 */
class BrainBadgeDetailTest {

    private val model = Model(modelId = "test-model", displayName = "Test Model")

    private fun nazarick(baseUrl: String, agentId: String) =
        ProviderSetting.Nazarick(baseUrl = baseUrl, agentId = agentId)

    @Test
    fun `nazarick records the agent id and the host of the base url`() {
        assertEquals(
            "tomb:aura@127.0.0.1",
            BrainBadge.storedDetail(model, nazarick("http://127.0.0.1:8765/v1", "aura")),
        )
    }

    @Test
    fun `nazarick host keeps the host only - no scheme port path or userinfo`() {
        assertEquals(
            "tomb:sebas@tomb.example.com",
            BrainBadge.storedDetail(model, nazarick("https://tomb.example.com/v1/chat/completions", "sebas")),
        )
        // The Bearer token can ride in the URL; a persisted message must not carry it.
        assertEquals(
            "tomb:demiurge@tomb.example.com",
            BrainBadge.storedDetail(model, nazarick("https://user:s3cret@tomb.example.com:8443/v1", "demiurge")),
        )
    }

    @Test
    fun `malformed nazarick base url records an unknown host instead of nothing`() {
        assertEquals("tomb:aura@?", BrainBadge.storedDetail(model, nazarick("not a url", "aura")))
        assertEquals("tomb:aura@?", BrainBadge.storedDetail(model, nazarick("tomb-local", "aura")))
        assertEquals("tomb:aura@?", BrainBadge.storedDetail(model, nazarick("", "aura")))
    }

    @Test
    fun `empty agent id still records the tomb it reached`() {
        assertEquals("tomb:@127.0.0.1", BrainBadge.storedDetail(model, nazarick("http://127.0.0.1:8765", "")))
    }

    @Test
    fun `edge records the model id tail`() {
        assertEquals(
            "edge:gemini-x",
            BrainBadge.storedDetail(
                Model(modelId = "google/gemini-x", displayName = "Gemini X"),
                ProviderSetting.OpenAI(),
            ),
        )
        assertEquals(
            "edge:gpt-4o",
            BrainBadge.storedDetail(Model(modelId = "gpt-4o", displayName = "GPT-4o"), ProviderSetting.Google()),
        )
    }

    @Test
    fun `edge with no model or no model id records the bare kind`() {
        assertEquals("edge", BrainBadge.storedDetail(null, ProviderSetting.OpenAI()))
        assertEquals("edge", BrainBadge.storedDetail(Model(), ProviderSetting.OpenAI()))
    }

    @Test
    fun `no provider records the legacy empty value`() {
        assertEquals("", BrainBadge.storedDetail(model, null))
        assertEquals("", BrainBadge.storedDetail(null, null))
    }

    @Test
    fun `detail always starts with the coarse kind so legacy readers still match`() {
        val tomb = BrainBadge.storedDetail(model, nazarick("http://127.0.0.1:8765", "aura"))
        val edge = BrainBadge.storedDetail(Model(modelId = "google/gemini-x"), ProviderSetting.OpenAI())
        assertTrue(tomb, tomb.startsWith(BrainBadge.STORED_TOMB))
        assertTrue(edge, edge.startsWith(BrainBadge.STORED_EDGE))
        // Same kind prefix as the coarse source the chip label is built from.
        assertEquals(BrainBadge.stored(BadgeKind.TOMB), tomb.substringBefore(':'))
        assertEquals(BrainBadge.stored(BadgeKind.EDGE), edge.substringBefore(':'))
    }

    @Test
    fun `stored kind is untouched by the detail payload`() {
        assertEquals("tomb", BrainBadge.stored(BadgeKind.TOMB))
        assertEquals("edge", BrainBadge.stored(BadgeKind.EDGE))
        assertEquals("", BrainBadge.stored(BadgeKind.NONE))
    }
}
