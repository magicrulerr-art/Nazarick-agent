package me.rerere.localdream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AURA-014 ghost, caught in a unit test this time: an installed pack whose Model row
 * never survived (draft pane died, app killed mid-flow) must be re-registered from disk,
 * and a provider carrying models while disabled must be enabled — the picker drops
 * disabled providers outright, so registering without enabling fixes nothing.
 */
class LocalDreamLibraryRepairTest {

    @Test fun installedPackWithoutRowRegistersAndEnables() {
        val repair = reconcileLocalDreamLibrary(
            registeredModelIds = emptySet(),
            diskInstalledIds = setOf("chilloutmix-8gen2"),
            enabled = false,
        )
        assertEquals(listOf("chilloutmix-8gen2"), repair.register.map { it.id })
        assertTrue(repair.enable)
        assertTrue(repair.changed)
    }

    @Test fun consistentProviderChangesNothing() {
        val repair = reconcileLocalDreamLibrary(
            registeredModelIds = setOf("chilloutmix-8gen2"),
            diskInstalledIds = setOf("chilloutmix-8gen2"),
            enabled = true,
        )
        assertFalse(repair.changed)
    }

    @Test fun rowsPresentButDisabledGetEnabled() {
        val repair = reconcileLocalDreamLibrary(
            registeredModelIds = setOf("chilloutmix-8gen2"),
            diskInstalledIds = emptySet(),
            enabled = false,
        )
        assertTrue(repair.enable)
        assertTrue(repair.register.isEmpty())
    }

    @Test fun nothingInstalledEnablesNothing() {
        val repair = reconcileLocalDreamLibrary(
            registeredModelIds = emptySet(),
            diskInstalledIds = emptySet(),
            enabled = false,
        )
        assertFalse(repair.changed)
    }

    @Test fun idsOutsideTheCatalogAreIgnored() {
        val repair = reconcileLocalDreamLibrary(
            registeredModelIds = emptySet(),
            diskInstalledIds = setOf("gpt-6-diffusion"),
            enabled = false,
        )
        assertTrue(repair.register.isEmpty())
        assertFalse(repair.enable)
    }
}
