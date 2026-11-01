package me.rerere.sdcpp

/**
 * Curated local image models.
 *
 * Two entries, both verified against the HuggingFace API on the day they were added, and both
 * **single-file** — which is the property that decides whether "download and go" works. sd.cpp
 * will happily run SDXL, FLUX and Z-Image, but those need a separate VAE and text encoder, and
 * a catalog that downloads a diffusion model the app then cannot load is worse than a short
 * catalog. Multi-file entries land when [components] gets a download UI.
 *
 * Sizes are exact bytes, not rounded: [SdCppCatalogTest] asserts them, so a re-upload that
 * changes a file fails a build instead of silently lying about whether a phone can hold it.
 */
data class SdModelEntry(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    /** The resolution the model was trained at; the engine sizes requests off this. */
    val baseSize: Int,
    val defaultSteps: Int,
    val defaultCfg: Float,
    /** Extra files this model needs besides [fileName] (VAE, text encoders). Empty = self-contained. */
    val components: List<SdComponent> = emptyList(),
    val note: String = "",
)

/** A companion weight file, resolved next to the diffusion model in the runtime directory. */
data class SdComponent(
    val role: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
)

object SdCppCatalog {

    const val PROVIDER_ID = "local_sdcpp"

    /** Directory under `local-models/` this runtime's files live in. */
    const val RUNTIME_DIRNAME = "sdcpp"

    /** Rough RAM the engine needs on top of the file, used for the "will this phone cope" hint. */
    fun estimatedRamBytes(entry: SdModelEntry): Long = (entry.sizeBytes * 1.15).toLong()

    val models: List<SdModelEntry> = listOf(
        SdModelEntry(
            id = "sd15-q4_0",
            displayName = "Stable Diffusion 1.5 · Q4_0",
            fileName = "stable-diffusion-v1-5-pruned-emaonly-Q4_0.gguf",
            url = "https://huggingface.co/second-state/stable-diffusion-v1-5-GGUF/resolve/main/" +
                "stable-diffusion-v1-5-pruned-emaonly-Q4_0.gguf",
            sizeBytes = 1_566_768_416L,
            baseSize = 512,
            defaultSteps = 20,
            defaultCfg = 7.0f,
            note = "Smallest that still looks like SD1.5. The whole ecosystem of SD1.5 prompts " +
                "and negative prompts applies.",
        ),
        SdModelEntry(
            id = "sd15-q8_0",
            displayName = "Stable Diffusion 1.5 · Q8_0",
            fileName = "stable-diffusion-v1-5-pruned-emaonly-Q8_0.gguf",
            url = "https://huggingface.co/second-state/stable-diffusion-v1-5-GGUF/resolve/main/" +
                "stable-diffusion-v1-5-pruned-emaonly-Q8_0.gguf",
            sizeBytes = 1_763_578_176L,
            baseSize = 512,
            defaultSteps = 20,
            defaultCfg = 7.0f,
            note = "Near-lossless weights for a phone with room to spare.",
        ),
    )

    fun entryFor(fileName: String): SdModelEntry? = models.firstOrNull { it.fileName == fileName }

    fun entryById(id: String): SdModelEntry? = models.firstOrNull { it.id == id }
}
