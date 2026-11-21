package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.BrainPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * US-011: pure destination-rule tests for [ImageRouter.decide].
 */
class ImageRouterTest {

    @Test
    fun `override to TOMB stays EDGE when policy is EDGE`() {
        // EDGE never silently leaves the device, even with an explicit override.
        assertEquals(
            ImageDestination.EDGE,
            ImageRouter.decide(
                policy = BrainPolicy.EDGE,
                override = ImageDestination.TOMB,
                tombConfigured = true,
            ),
        )
    }

    @Test
    fun `AUTO with tomb configured routes to TOMB`() {
        assertEquals(
            ImageDestination.TOMB,
            ImageRouter.decide(
                policy = BrainPolicy.AUTO,
                override = null,
                tombConfigured = true,
            ),
        )
    }

    @Test
    fun `AUTO without tomb configured falls back to EDGE`() {
        assertEquals(
            ImageDestination.EDGE,
            ImageRouter.decide(
                policy = BrainPolicy.AUTO,
                override = null,
                tombConfigured = false,
            ),
        )
    }

    @Test
    fun `TOMB without tomb configured falls back to EDGE`() {
        assertEquals(
            ImageDestination.EDGE,
            ImageRouter.decide(
                policy = BrainPolicy.TOMB,
                override = null,
                tombConfigured = false,
            ),
        )
    }

    // --- Supporting coverage for the rule ordering (not required, but cheap) ---

    @Test
    fun `TOMB with tomb configured routes to TOMB`() {
        assertEquals(
            ImageDestination.TOMB,
            ImageRouter.decide(
                policy = BrainPolicy.TOMB,
                override = null,
                tombConfigured = true,
            ),
        )
    }

    @Test
    fun `explicit override to EDGE wins over a configured tomb`() {
        assertEquals(
            ImageDestination.EDGE,
            ImageRouter.decide(
                policy = BrainPolicy.AUTO,
                override = ImageDestination.EDGE,
                tombConfigured = true,
            ),
        )
    }

    @Test
    fun `override to TOMB wins for non-EDGE policy even when not configured`() {
        // Rule 2 (override wins) precedes rule 3 (needs tombConfigured).
        assertEquals(
            ImageDestination.TOMB,
            ImageRouter.decide(
                policy = BrainPolicy.AUTO,
                override = ImageDestination.TOMB,
                tombConfigured = false,
            ),
        )
    }

    @Test
    fun `EDGE policy with no override stays EDGE`() {
        assertEquals(
            ImageDestination.EDGE,
            ImageRouter.decide(
                policy = BrainPolicy.EDGE,
                override = null,
                tombConfigured = true,
            ),
        )
    }
}
