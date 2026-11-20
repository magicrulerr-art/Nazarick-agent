package me.rerere.ai.provider.providers.nazarick

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * US-005 seam: [defaultAgentIdFromRoster] decides which agent id the provider keeps
 * when a live roster arrives — a still-listed selection wins, then the first enabled
 * agent, then nothing. Pure JVM, same dialect as [NazarickRosterTest].
 */
class DefaultAgentIdFromRosterTest {

    private val roster = listOf(
        NazarickAgent("aura", "Aura", "beast tamer", true),
        NazarickAgent("mare", "Mare", "chronicler", true),
        NazarickAgent("sebas", "Sebas", "butler", false),
    )

    @Test
    fun `current id still listed in roster is kept`() {
        assertEquals("mare", defaultAgentIdFromRoster(roster, "mare"))
    }

    @Test
    fun `current id is kept even when that agent is disabled`() {
        assertEquals("sebas", defaultAgentIdFromRoster(roster, "sebas"))
    }

    @Test
    fun `stale or empty current id falls back to first enabled agent`() {
        assertEquals("aura", defaultAgentIdFromRoster(roster, "vanished"))
        assertEquals("aura", defaultAgentIdFromRoster(roster, ""))
    }

    @Test
    fun `empty or all-disabled roster yields empty id instead of inventing one`() {
        assertEquals("", defaultAgentIdFromRoster(emptyList(), "aura"))
        assertEquals(
            "",
            defaultAgentIdFromRoster(listOf(NazarickAgent("solo", "Solo", "", false)), "gone"),
        )
    }
}
