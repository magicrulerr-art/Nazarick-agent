package me.rerere.localdream

/**
 * AURA-017 — the AURA-014 ghost, exorcised a second time.
 *
 * The QNN tile registered its Model row in the download button's `onSuccess` — through the
 * pane's *draft* `onEdit`. A back-navigation after a completed download (or a killed app
 * mid-flow) drops the draft and with it the row, leaving a whole extracted pack on disk that
 * no `Model` points at and that the image picker — which lists Models, not files — cannot
 * show. Exactly the v0.03 sd.cpp failure, reproduced on the first owner install of run 135.
 *
 * [reconcileLocalDreamLibrary] is the pure half of the same repair [reconcileSdLibrary]
 * introduced: disk truth beats registration truth, and a provider that carries at least one
 * model — registered or recoverable — should be enabled, because `ModelListState` drops
 * disabled providers outright. The impure half lives in the settings tile and writes through
 * `SettingsStore`, not just the draft callback.
 */
data class LocalDreamLibraryRepair(
    /** Catalog entries whose packs are on disk but missing from the provider's model list. */
    val register: List<QnnModelEntry> = emptyList(),
    /** True when the provider carries models yet sits disabled. */
    val enable: Boolean = false,
) {
    val changed: Boolean get() = register.isNotEmpty() || enable
}

/**
 * Pure plan: [diskInstalledIds] are catalog entry ids whose extracted directories passed the
 * installer's marker check; [registeredModelIds] are the provider's registered
 * `Model.modelId`s (local-dream keys rows on the **entry id**, unlike sd.cpp's file names).
 * Ids the catalog doesn't know are ignored — same rule as sd-cpp's `adopt`.
 */
fun reconcileLocalDreamLibrary(
    registeredModelIds: Set<String>,
    diskInstalledIds: Set<String>,
    enabled: Boolean,
): LocalDreamLibraryRepair {
    val register = LocalDreamCatalog.models
        .filter { it.id in diskInstalledIds }
        .filter { it.id !in registeredModelIds }
        .sortedBy { it.id }
    val hasModels = registeredModelIds.isNotEmpty() || register.isNotEmpty()
    return LocalDreamLibraryRepair(register = register, enable = hasModels && !enabled)
}
