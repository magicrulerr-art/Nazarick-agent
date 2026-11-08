package me.rerere.localdream

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Modality

/**
 * Curated QNN/MNN image models for the local-dream engine.
 *
 * Every entry is a **zip archive from the xororz HuggingFace zoo**, verified against the
 * HF tree API on 2026-11-08 (exact bytes are asserted in `LocalDreamCatalogTest`, same
 * house rule as `SdCppCatalogTest`: a re-upload that changes a file must fail a build
 * rather than silently lie about whether a phone can hold it).
 *
 * The `_8gen2` suffix is not a model-generation marker — it is the **flagship SoC-family
 * compile** of the same checkpoint (OGAM's compat rule: any flagship device runs the
 * `8gen2` context; `min` targets non-flagship NPUs). The Fold's SM8650 is flagship-class
 * (Hexagon v73, the same DSP generation as 8gen2's SM8550), so this app ships `8gen2`
 * zips only; a `min` or `8gen1` entry would be a second compatibility story nobody tested.
 */
data class QnnModelEntry(
    val id: String,
    val displayName: String,
    /** Zip name on HuggingFace. */
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    /** Which backend pack this is: "qnn" (NPU context) or "mnn" (CPU/GPU graphs). */
    val packKind: String,
    val defaultSteps: Int = 20,
    val defaultCfg: Double = 7.5,
    val note: String = "",
)

object LocalDreamCatalog {

    const val PROVIDER_ID = "local_dream"

    /** Directory under `local-models/` this runtime's packs live in. */
    const val RUNTIME_DIRNAME = "localdream"

    /** SD1.5 checkpoints: the whole zoo is ViT-L/14 @ 512. */
    const val BASE_SIZE = 512

    val models: List<QnnModelEntry> = listOf(
        QnnModelEntry(
            id = "chilloutmix-8gen2",
            displayName = "ChilloutMix · QNN (NPU)",
            fileName = "ChilloutMix_qnn2.28_8gen2.zip",
            url = "https://huggingface.co/xororz/sd-qnn/resolve/main/ChilloutMix_qnn2.28_8gen2.zip",
            sizeBytes = 1_069_856_038L,
            packKind = "qnn",
            note = "Photoreal SD1.5 favourite, pre-compiled for flagship Hexagon. " +
                "The reason this engine exists.",
        ),
        QnnModelEntry(
            id = "absolutereality-8gen2",
            displayName = "AbsoluteReality · QNN (NPU)",
            fileName = "AbsoluteReality_qnn2.28_8gen2.zip",
            url = "https://huggingface.co/xororz/sd-qnn/resolve/main/AbsoluteReality_qnn2.28_8gen2.zip",
            sizeBytes = 1_054_661_172L,
            packKind = "qnn",
            note = "Photoreal SD1.5 checkpoint, second flavour for the same DSP compile.",
        ),
        QnnModelEntry(
            id = "dreamshaperv8-8gen2",
            displayName = "DreamShaper V8 · QNN (NPU)",
            fileName = "DreamShaperV8_qnn2.28_8gen2.zip",
            url = "https://huggingface.co/xororz/sd-qnn/resolve/main/DreamShaperV8_qnn2.28_8gen2.zip",
            sizeBytes = 1_032_290_626L,
            packKind = "qnn",
            note = "Art/photoreal hybrid SD1.5 checkpoint.",
        ),
        QnnModelEntry(
            id = "chilloutmix-mnn",
            displayName = "ChilloutMix · MNN (CPU/GPU)",
            fileName = "ChilloutMix.zip",
            url = "https://huggingface.co/xororz/sd-mnn/resolve/main/ChilloutMix.zip",
            sizeBytes = 1_203_917_293L,
            packKind = "mnn",
            note = "Fallback pack for devices whose NPU refuses the context; also what " +
                "auto-mode falls back TO when a QNN boot fails on a shared pack.",
        ),
    )

    fun entryFor(fileName: String): QnnModelEntry? = models.firstOrNull { it.fileName == fileName }

    fun entryById(id: String): QnnModelEntry? = models.firstOrNull { it.id == id }

    /**
     * The [Model] row a successful install registers. `modelId` is the **entry id** — unlike
     * sd-cpp (one GGUF per file) a local-dream model is a *directory*, and the id names it.
     */
    fun modelFor(entry: QnnModelEntry): Model =
        Model(
            modelId = entry.id,
            displayName = entry.displayName,
            type = ModelType.IMAGE,
            outputModalities = listOf(Modality.IMAGE),
        )
}
