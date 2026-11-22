package me.rerere.sdcpp

/**
 * AURA-014 — repair plan for the sd.cpp provider after a download died outside the app.
 *
 * The v0.03 ship had a bug the owner found on a fresh install: the sd.cpp model downloaded
 * (the tile proved it with a Delete button — the file was on disk) yet the model never
 * appeared in the image picker. Two independent halves, both real:
 *
 *  1. The provider row registers only in the download button's `onSuccess`. An app that is
 *     killed mid-flow, a registration write that loses a race — either leaves a whole GGUF
 *     on disk that no `Model` points at, and the picker lists `Model`s, not files.
 *  2. The bundled sd.cpp provider ships `enabled = false` and the tile never flipped it:
 *     [ModelListState].filteredProviders drops disabled providers outright, so even a
 *     registered model stays unselectable.
 *
 * [reconcileSdLibrary] is the pure half of the repair: disk truth (files in the runtime
 * directory) beats registration truth (whatever survived), and a provider that carries at
 * least one model — registered or recoverable — should be enabled. The impure half lives
 * in the settings tile, which applies the plan through the normal edit path.
 */
data class SdLibraryRepair(
    /** Catalog entries present on disk but missing from the provider's model list. */
    val register: List<SdModelEntry> = emptyList(),
    /** True when the provider carries models yet sits disabled. */
    val enable: Boolean = false,
) {
    val changed: Boolean get() = register.isNotEmpty() || enable
}

/**
 * Pure plan: [diskFileNames] are the `.gguf` names actually present in the sd.cpp runtime
 * directory; [registeredModelIds] are the provider's registered `Model.modelId`s (which is
 * where the tile records the file name). Names the catalog doesn't know are ignored —
 * sd.cpp could load them but the app cannot size or parameterise them, same rule as
 * [SdModelInstaller.adopt].
 */
fun reconcileSdLibrary(
    registeredModelIds: Set<String>,
    diskFileNames: Set<String>,
    enabled: Boolean,
): SdLibraryRepair {
    val register = diskFileNames
        .mapNotNull { SdCppCatalog.entryFor(it) }
        .filter { it.fileName !in registeredModelIds }
        .sortedBy { it.id }
    val hasModels = registeredModelIds.isNotEmpty() || register.isNotEmpty()
    return SdLibraryRepair(register = register, enable = hasModels && !enabled)
}
