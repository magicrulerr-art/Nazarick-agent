package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.provider.NAZARICK_PROVIDER_ID
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.runtime.util.JsonInstant
import kotlin.uuid.Uuid

/**
 * DEF-A (US-004d): the `nazarick` built-in row never reaches disk on upgraded installs.
 *
 * Built-in seeding lives only in the read-side `.map` of `SettingsStore.settingsFlowRaw`
 * (PreferencesStore.kt:729-817). That lambda transforms what a collector sees; it never
 * writes. The single writer of the persisted `providers` blob is `persistSettings`
 * (PreferencesStore.kt:419), driven by `update()` / `update { fn }`, and none of the V1-V5
 * migrations touch the key. So an upgraded install materialises the row in memory only, and
 * anything that reads the stored snapshot — the backup Export to File serialization
 * (`BackupManager.createBackup` -> `settingsFlowRaw.first()`, BackupManager.kt:43), and every
 * launch after a write that didn't carry the row — sees 26 providers and 0 `nazarick`
 * (z7, 2026-11-24, deletedBuiltInProviderIds=[]).
 *
 * This migration puts the row on disk, once, before any consumer reads: pages, AutoRouter /
 * GenerationLoop (`settingsFlow.value`) and the backup export all agree afterwards, and a
 * restart cannot lose it again.
 */
class PreferenceStoreV6Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 6
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        seedMissingNazarickRow(
            storedProvidersJson = prefs[SettingsStore.PROVIDERS],
            deletedBuiltInProviderIdsJson = prefs[SettingsStore.DELETED_BUILTIN_PROVIDER_IDS],
        )?.let { prefs[SettingsStore.PROVIDERS] = it }
        prefs[SettingsStore.VERSION] = 6
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

/**
 * Appends the built-in `nazarick` row to a stored `providers` JSON array.
 *
 * Returns the replacement blob, or null when nothing must be written: the stored list is
 * absent/blank (fresh install - the read-side seed already covers it, and writing defaults
 * here would freeze them as "user data"), undecodable (leave the blob to
 * `decodeProvidersTolerant` rather than rewrite it blind), already carries the row, or the
 * user deleted it (`deletedBuiltInProviderIds` wins, same gate as the read-side merge).
 *
 * Existing entries are re-emitted untouched instead of decoded-and-re-encoded, so fields this
 * build doesn't know about survive the upgrade path byte-for-byte.
 */
internal fun seedMissingNazarickRow(
    storedProvidersJson: String?,
    deletedBuiltInProviderIdsJson: String?,
): String? {
    if (storedProvidersJson.isNullOrBlank()) return null
    val stored = runCatching { JsonInstant.parseToJsonElement(storedProvidersJson) as? JsonArray }
        .getOrNull() ?: return null
    if (NAZARICK_PROVIDER_ID in parseDeletedBuiltInProviderIds(deletedBuiltInProviderIdsJson)) {
        return null
    }

    val defaultRow = nazarickDefaultRowJson()
    val discriminator = (defaultRow as JsonObject)[DISCRIMINATOR_KEY]
    val nazarickId = NAZARICK_PROVIDER_ID.toString()

    val alreadySeeded = stored.any { element ->
        val obj = element as? JsonObject ?: return@any false
        // A persisted row may omit `id` (JsonInstant uses encodeDefaults = false, and the id
        // equals the default), so the discriminator is the second signal for "already there".
        obj[DISCRIMINATOR_KEY] == discriminator ||
            (obj["id"] as? JsonPrimitive)?.contentOrNull == nazarickId
    }
    if (alreadySeeded) return null

    return JsonInstant.encodeToString(JsonArray(stored.toList() + defaultRow))
}

/** The built-in default row exactly as `DEFAULT_PROVIDERS` ships it, serialized for disk. */
private fun nazarickDefaultRowJson(): JsonElement {
    val default: ProviderSetting = DEFAULT_PROVIDERS.firstOrNull { it.id == NAZARICK_PROVIDER_ID }
        ?: ProviderSetting.Nazarick(builtIn = true)
    return JsonInstant.parseToJsonElement(
        JsonInstant.encodeToString(ProviderSetting.serializer(), default)
    )
}

/** Mirrors the read-side decode of `DELETED_BUILTIN_PROVIDER_IDS` (PreferencesStore.kt:566). */
private fun parseDeletedBuiltInProviderIds(raw: String?): Set<Uuid> {
    if (raw.isNullOrBlank()) return emptySet()
    return runCatching {
        JsonInstant.decodeFromString<Set<String>>(raw)
            .mapNotNull { id -> runCatching { Uuid.parse(id) }.getOrNull() }
            .toSet()
    }.getOrDefault(emptySet())
}

/** `JsonInstant`'s polymorphic class discriminator, matching the persisted blob layout. */
private const val DISCRIMINATOR_KEY = "type"
