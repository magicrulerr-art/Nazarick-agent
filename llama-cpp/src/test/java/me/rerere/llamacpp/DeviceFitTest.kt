package me.rerere.llamacpp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AURA-020 Phase 1.5: the survey must rank honestly on fake devices with exact numbers
 * and on the real curated catalog for the fleet's actual RAM sizes (z7 12 GB, z8 16 GB).
 */
class DeviceFitTest {
    private val small = LlamaCppCatalogEntry("S", "r/s", "s.gguf", 600_000_000L, 4)
    private val mid = LlamaCppCatalogEntry("M", "r/m", "m.gguf", 2_600_000_000L, 8)
    private val big = LlamaCppCatalogEntry("B", "r/b", "b.gguf", 5_700_000_000L, 12)

    @Test
    fun fitsRespectsBothCeilingAndFloor() {
        assertTrue(DeviceFit.fits(mid, 8.0))
        assertFalse(DeviceFit.fits(big, 8.0))
        assertFalse(DeviceFit.fits(mid, 0.0))
    }

    @Test
    fun picksDegradeOnASmallDevice() {
        val p = DeviceFit.picks(listOf(small, mid, big), 4.0)
        assertEquals("S", p.fast?.displayName)
        assertEquals("S", p.balanced?.displayName)
        assertEquals("S", p.best?.displayName)
    }

    @Test
    fun picksSpreadSlotsOnABigDevice() {
        val p = DeviceFit.picks(listOf(small, mid, big), 16.0)
        assertEquals("S", p.fast?.displayName)
        assertEquals("M", p.balanced?.displayName)
        assertEquals("B", p.best?.displayName)
    }

    @Test
    fun picksEmptyWhenNothingFits() {
        val p = DeviceFit.picks(listOf(big), 2.0)
        assertNull(p.fast)
        assertTrue(p.isEmpty)
    }

    @Test
    fun ramFitScorePenalisesOversizedModels() {
        assertTrue(DeviceFit.ramFitScore(mid, 4.0) > DeviceFit.ramFitScore(mid, 12.0))
    }

    @Test
    fun realCatalogRanksForTheFleet() {
        val z7 = DeviceFit.picks(LlamaCppCatalog.ENTRIES, 12.0)
        assertEquals("Qwen3.5 9B", z7.best?.displayName)
        val z8 = DeviceFit.picks(LlamaCppCatalog.ENTRIES, 16.0)
        assertEquals("Qwen3.5 9B", z8.best?.displayName)
        assertEquals("Qwen3 0.6B", z8.fast?.displayName)
    }
}
