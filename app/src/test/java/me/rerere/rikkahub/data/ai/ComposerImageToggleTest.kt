package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.BrainPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * US-011r AC1: pure-state tests for the composer Edge|Tomb image-destination toggle
 * ([initialChoice] / [tombEnabled]).
 */
class ComposerImageToggleTest {

    @Test
    fun `EDGE policy preselects EDGE and Tomb is disabled`() {
        // The absolute rule: under EDGE the image never silently leaves the device,
        // even when a tomb endpoint is configured.
        assertEquals(
            ImageDestination.EDGE,
            initialChoice(policy = BrainPolicy.EDGE, tombConfigured = true),
        )
        assertEquals(false, tombEnabled(BrainPolicy.EDGE))
    }

    @Test
    fun `TOMB policy with a configured tomb preselects TOMB`() {
        assertEquals(
            ImageDestination.TOMB,
            initialChoice(policy = BrainPolicy.TOMB, tombConfigured = true),
        )
        assertEquals(true, tombEnabled(BrainPolicy.TOMB))
    }

    @Test
    fun `AUTO with no tomb configured falls back to EDGE but stays selectable`() {
        // No endpoint to upload to → keep the image on the device, yet AUTO is not
        // the absolute EDGE policy, so the Tomb option is not greyed out.
        assertEquals(
            ImageDestination.EDGE,
            initialChoice(policy = BrainPolicy.AUTO, tombConfigured = false),
        )
        assertEquals(true, tombEnabled(BrainPolicy.AUTO))
    }
}
