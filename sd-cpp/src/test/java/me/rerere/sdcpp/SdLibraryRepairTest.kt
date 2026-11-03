package me.rerere.sdcpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AURA-014 bug #1 — a downloaded sd.cpp model used to be invisible: registration lived
 * only in the download button's onSuccess, and the provider ships disabled while the
 * picker drops disabled providers outright. The repair plan is pure; these pin every
 * quadrant of (rows?, on disk?, enabled?).
 */
class SdLibraryRepairTest {

    private val entry = SdCppCatalog.models.first()
    private val fileName = entry.fileName

    @Test
    fun `file on disk without a row is registered and the provider enabled`() {
        val repair = reconcileSdLibrary(
            registeredModelIds = emptySet(),
            diskFileNames = setOf(fileName),
            enabled = false,
        )
        assertEquals(listOf(entry), repair.register)
        assertTrue(repair.enable)
        assertTrue(repair.changed)
    }

    @Test
    fun `registered and enabled is a no-op`() {
        val repair = reconcileSdLibrary(
            registeredModelIds = setOf(fileName),
            diskFileNames = setOf(fileName),
            enabled = true,
        )
        assertFalse(repair.changed)
    }

    @Test
    fun `registered but disabled flips enable without touching rows`() {
        val repair = reconcileSdLibrary(
            registeredModelIds = setOf(fileName),
            diskFileNames = setOf(fileName),
            enabled = false,
        )
        assertEquals(emptyList<SdModelEntry>(), repair.register)
        assertTrue(repair.enable)
    }

    @Test
    fun `an enabled provider with no models anywhere stays untouched`() {
        val repair = reconcileSdLibrary(emptySet(), emptySet(), enabled = true)
        assertFalse(repair.changed)
    }

    @Test
    fun `a disabled empty provider stays disabled — nothing to show yet`() {
        val repair = reconcileSdLibrary(emptySet(), emptySet(), enabled = false)
        assertFalse(repair.changed)
    }

    @Test
    fun `files outside the catalog are ignored — same rule as adopt`() {
        val repair = reconcileSdLibrary(
            registeredModelIds = emptySet(),
            diskFileNames = setOf("some-random-gguf-of-unknown-provenance.gguf"),
            enabled = false,
        )
        assertFalse(repair.changed)
    }

    @Test
    fun `a registered row whose file vanished is not un-registered — deletion is the user's`() {
        val repair = reconcileSdLibrary(
            registeredModelIds = setOf(fileName),
            diskFileNames = emptySet(),
            enabled = true,
        )
        assertFalse(repair.changed)
    }
}
