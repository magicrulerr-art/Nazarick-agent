package me.rerere.llamacpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AURA-020 Phase 1.5: the hardware survey must rank honestly on fake devices with exact
 * numbers, and on the real curated catalog for the fleet's actual RAM sizes (z7 12 GB,
 * z8 16 GB). The validation that convinced us to port it: the 40 % sweet-spot rule picks
 * what the bench chose by hand.
 */
class DeviceFitTest {

    private val small = LlamaCppCatalogEntry("S", "r/s", "s.gguf", 600_000_000L, 4)
    private val mid = LlamaCppCatalogEntry("M", "r/m", "m.gguf", 2_600_000_000L, 8)
    private val big = LlamaCppCatalogEntry("B", "r/b", "b.gguf", 5_700_000_000L, 12)

    @Test
    fun `fits respects both size ceiling and minMemGb floor`() {
        assertTrue(DeviceFit.fits(mid, 8.0))
        assertFalse(DeviceFit.fits(big, 8.0))
        assertFalse(DeviceFit.fits(mid, 0.0))
    }

    @Test
    fun `picks degrade into one model on a small device`() {
        val p = DeviceFit.picks(listOf(small, mid, big), 4.0)
        assertEquals("S", p.fast?.displayName)
        assertEquals("S", p.balanced?.displayName)
        assertEquals("S", p.best?.displayName)
    }

    @Test
    fun `picks spread slots on a big device`() {
        val p = DeviceFit.picks(listOf(small, mid, big), 16.0)
        assertEquals("S", p.fast?.displayName)
        assertEquals("M", p.balanced?.displayName)
        assertEquals("B", p.best?.displayName)
    }

    @Test
    fun `picks are empty when nothing fits`() {
        val p = DeviceFit.picks(listOf(big), 2.0)
        assertNull(p.fast)
        assertTrue(p.isEmpty)
    }

    @Test
    fun `ramFitScore penalises oversized models`() {
        assertTrue(DeviceFit.ramFitScore(mid, 4.0) > DeviceFit.ramFitScore(mid, 12.0))
    }

    @Test
    fun `real catalog ranks for the fleet`() {
        val z7 = DeviceFit.picks(LlamaCppCatalog.ENTRIES, 12.0)
        assertEquals("Qwen3.5 9B", z7.best?.displayName)
        val z8 = DeviceFit.picks(LlamaCppCatalog.ENTRIES, 16.0)
        assertEquals("Qwen3.5 9B", z8.best?.displayName)
        assertEquals("Qwen3 0.6B", z8.fast?.displayName)
    }
}
