package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.NAZARICK_PROVIDER_ID
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.runtime.util.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * DEF-A (US-004d): an upgraded install must end up with the built-in `nazarick` row on disk.
 *
 * The stored fixture below is the shape z7 actually had (backup rikkahub_backup_20261124_141438:
 * 26 providers, 0 of type `nazarick`, deletedBuiltInProviderIds=[]) — built-in rows plus a
 * user-added provider whose display name is also "Nazarick" but whose type is `openai`.
 */
class PreferenceStoreV6MigrationTest {

    private val userRow = ProviderSetting.OpenAI(
        id = Uuid.parse("de2c50a7-835c-4944-b736-d1652f45dda7"),
        enabled = false,
        name = "Nazarick",
        baseUrl = "http://127.0.0.1:8099",
    )
    private val builtInRow = ProviderSetting.OpenAI(
        id = Uuid.parse("1eeea727-9ee5-4cae-93e6-6fb01a4d051e"),
        enabled = false,
        name = "OpenAI",
    )
    private val storedWithoutNazarick: String =
        JsonInstant.encodeToString(listOf<ProviderSetting>(builtInRow, userRow))

    private fun effectiveProvidersOf(json: String): List<ProviderSetting> =
        JsonInstant.decodeFromString(json)

    @Test
    fun `stored list without a nazarick row gains exactly one nazarick row`() {
        val seeded = seedMissingNazarickRow(storedWithoutNazarick, null)
        assertNotNull("seed must write a replacement blob", seeded)

        val effective = effectiveProvidersOf(seeded!!)
        val nazarickRows = effective.filterIsInstance<ProviderSetting.Nazarick>()
        assertEquals("exactly one Nazarick row", 1, nazarickRows.size)

        val row = nazarickRows.single()
        assertEquals(NAZARICK_PROVIDER_ID, row.id)
        assertFalse("seeded built-in must stay disabled", row.enabled)
        assertTrue("seeded row must be flagged built-in", row.builtIn)
        assertEquals("Nazarick", row.name)
        assertEquals("", row.baseUrl)
        assertEquals("", row.token)
        assertFalse(row.companion_enabled)

        // Nothing the user had is lost or reordered: the seed is append-only.
        assertEquals(
            listOf(builtInRow.id, userRow.id, NAZARICK_PROVIDER_ID),
            effective.map { it.id },
        )
    }

    @Test
    fun `second seed pass is idempotent`() {
        val seeded = seedMissingNazarickRow(storedWithoutNazarick, null)
        assertNotNull(seeded)
        assertNull("already-seeded blob must not be rewritten", seedMissingNazarickRow(seeded!!, null))
        assertEquals(1, effectiveProvidersOf(seeded).filterIsInstance<ProviderSetting.Nazarick>().size)
    }

    @Test
    fun `deletedDefaultIds containing NAZARICK_PROVIDER_ID suppresses the row`() {
        val deleted = JsonInstant.encodeToString(setOf(NAZARICK_PROVIDER_ID.toString()))
        assertNull(seedMissingNazarickRow(storedWithoutNazarick, deleted))
        // A different built-in in the deleted list must not suppress this one.
        val otherDeleted = JsonInstant.encodeToString(setOf(builtInRow.id.toString()))
        assertNotNull(seedMissingNazarickRow(storedWithoutNazarick, otherDeleted))
    }

    @Test
    fun `an already persisted nazarick row is left alone even without a serialized id`() {
        // JsonInstant uses encodeDefaults = false, so a persisted default row may carry only
        // the discriminator. Presence must still be detected there.
        assertNull(seedMissingNazarickRow("""[{"type":"nazarick"}]""", null))
        assertNull(
            seedMissingNazarickRow(
                """[{"type":"openai","id":"$NAZARICK_PROVIDER_ID"}]""", null
            )
        )
    }

    @Test
    fun `fresh install with no stored providers is left to the read-side seed`() {
        assertNull(seedMissingNazarickRow(null, null))
        assertNull(seedMissingNazarickRow("", null))
        assertNull(seedMissingNazarickRow("   ", null))
        // Undecodable blob: leave it to decodeProvidersTolerant instead of rewriting blind.
        assertNull(seedMissingNazarickRow("""{"not":"an array"}""", null))
    }

    @Test
    fun `migration stamps version 6 and persists the row before any consumer reads`() {
        val migration = PreferenceStoreV6Migration()
        val upgraded = mutablePreferencesOf(
            SettingsStore.VERSION to 5,
            SettingsStore.PROVIDERS to storedWithoutNazarick,
        )
        assertTrue(runBlocking { migration.shouldMigrate(upgraded) })
        assertTrue(runBlocking { migration.shouldMigrate(emptyPreferences()) })

        val migrated = runBlocking { migration.migrate(upgraded) }
        assertEquals(6, migrated[SettingsStore.VERSION])
        val stored = migrated[SettingsStore.PROVIDERS]
        assertNotNull("providers blob must be written to disk", stored)
        assertEquals(1, effectiveProvidersOf(stored!!).filterIsInstance<ProviderSetting.Nazarick>().size)

        val alreadyMigrated = mutablePreferencesOf(SettingsStore.VERSION to 6)
        assertFalse(runBlocking { migration.shouldMigrate(alreadyMigrated) })
    }
}
