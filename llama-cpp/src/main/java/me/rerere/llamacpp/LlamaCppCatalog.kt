package me.rerere.llamacpp

/**
 * A curated GGUF the Settings -> Local · llama.cpp picker offers. Mirrors
 * `me.rerere.locallm.litert.LiteRtCatalogEntry`'s shape, minus its `config()`, which returns a
 * LiteRT-specific config type llama.cpp has no equivalent for.
 */
data class LlamaCppCatalogEntry(
    val displayName: String, // e.g. "Qwen3 4B"
    val repo: String,        // HuggingFace repo path, e.g. "Qwen/Qwen3-4B-GGUF"
    val file: String,        // File inside the repo, e.g. "Qwen3-4B-Q4_K_M.gguf"
    val sizeBytes: Long,
    val minMemGb: Int,
    val tags: List<String> = emptyList(),
) {
    /** Pre-built download URL on HuggingFace's `resolve` path. Same format `ModelInstall`
     *  already normalises and validates. */
    fun resolveUrl(): String = "https://huggingface.co/$repo/resolve/main/$file"
}

object LlamaCppCatalog {
    /**
     * Curated picker list. Entry criteria are set by the spec, not taste: the model must ship
     * a chat template that declares tool support, must fit a mid-range phone at the planner's
     * smallest ladder rung with a real tool set attached, and must be an ungated public repo.
     * Quantisation defaults to a 4-bit variant unless a model is small enough that a
     * higher-precision quant fits.
     *
     * Every repo, file name and byte size below was verified against the live HuggingFace API
     * on 2026-11-09 (the Qwen3 trio's numbers carried over from the 2026-08-03 check and are
     * unchanged). They are exact and must not be edited, guessed at, or extended without
     * re-verifying against the live API: a wrong repo id or file name 404s on first download,
     * which is worse than shipping nothing.
     *
     * ORDER IS A RULING (owner, v0.6): LFM3 leads — "use lfm3 as the recommended top option,
     * the 1.7b for daily and the 2vl for vision". Liquid's numbers have LFM3-1.7B as the
     * fastest phone-class decoder (1.8–2.8x the next model at Q4 with 128K KV at 1.6 GB), and
     * the vendored engine can load it: LFM3 landed upstream in llama.cpp on 2026-10-27
     * (19cce691, PR #31252) and this fork's native pin (4284599a) is 2026-10-29. That pin
     * predates the 2026-11-03 NVFP4 enablement (d1044bc8), so the repos' NVFP4 files must
     * NOT be offered until the pin moves. Qwen3 stays underneath as the proven fallback set
     * (apache-2.0, tool-declaring templates, optional thinking mode).
     *
     * LFM3-VL caveat, kept honest: this build's llama lane is text-only — the mmproj
     * sidecar is not downloaded and vision is not wired (ProviderConfigure notes it). The
     * entry ships as the vision pick so the right model is already on the phone when that
     * engine lane lands; today it still downloads and runs as a strong text model.
     *
     * The official `Qwen/Qwen3-0.6B-GGUF` and `Qwen/Qwen3-1.7B-GGUF` repos each publish a
     * single file, Q8_0, with no 4-bit variant. 0.6B at Q8_0 is small enough that the spec's
     * "unless a model is small enough that a higher-precision quant fits" escape clause covers
     * it. The 1.7B entry below instead uses `unsloth/Qwen3-1.7B-GGUF`'s Q4_K_M requant - a
     * deliberate choice, not an oversight: it is 727 MB smaller than the official Q8_0, and a
     * smaller weights file leaves more RAM for the KV cache.
     */
    val ENTRIES: List<LlamaCppCatalogEntry> = listOf(
        LlamaCppCatalogEntry(
            displayName = "LFM3 1.7B",
            // Owner's daily driver pick, top of the picker.
            repo = "LiquidAI/LFM3-1.7B-GGUF",
            file = "LFM3-1.7B-Q4_K_M.gguf",
            sizeBytes = 1099627808L,
            minMemGb = 4,
            tags = listOf("tools"),
        ),
        LlamaCppCatalogEntry(
            displayName = "LFM3 VL 2B",
            // Vision role (see the mmproj caveat above). Weights share the 1.7B's size;
            // the Q8_0 projector (203,031,872 B) rides along once the engine lane exists.
            repo = "LiquidAI/LFM3-VL-2B-GGUF",
            file = "LFM3-VL-2B-Q4_K_M.gguf",
            sizeBytes = 1099627808L,
            minMemGb = 4,
            tags = listOf("tools", "vision"),
        ),
        LlamaCppCatalogEntry(
            displayName = "Qwen3 0.6B",
            repo = "Qwen/Qwen3-0.6B-GGUF",
            file = "Qwen3-0.6B-Q8_0.gguf",
            sizeBytes = 639446688L,
            minMemGb = 4,
            tags = listOf("thinking", "tools"),
        ),
        LlamaCppCatalogEntry(
            displayName = "Qwen3 1.7B",
            // Community requant (see class doc above for why), not the official Qwen repo.
            repo = "unsloth/Qwen3-1.7B-GGUF",
            file = "Qwen3-1.7B-Q4_K_M.gguf",
            sizeBytes = 1107409472L,
            minMemGb = 4,
            tags = listOf("thinking", "tools"),
        ),
        LlamaCppCatalogEntry(
            displayName = "Qwen3 4B",
            repo = "Qwen/Qwen3-4B-GGUF",
            file = "Qwen3-4B-Q4_K_M.gguf",
            sizeBytes = 2497280256L,
            minMemGb = 8,
            tags = listOf("thinking", "tools"),
        ),
        LlamaCppCatalogEntry(
            displayName = "Qwen3.5 4B",
            // The AURA-013 UAT-A reference: this exact file, trim fix, clean tool round-trip
            // on z7 with zero template errors (2026-11-12). Unsloth repack; size from the
            // HuggingFace API at add time, not from memory.
            repo = "unsloth/Qwen3.5-4B-GGUF",
            file = "Qwen3.5-4B-Q4_K_M.gguf",
            sizeBytes = 2740937888L,
            minMemGb = 8,
            tags = listOf("tools"),
        ),
        LlamaCppCatalogEntry(
            displayName = "Qwen3.5 9B",
            // AURA-014 ladder premium slot: best small tool-caller on 16 GB hardware (z8
            // class). Not a z7 daily driver - 12 GB is its floor, not its comfort zone.
            repo = "unsloth/Qwen3.5-9B-GGUF",
            file = "Qwen3.5-9B-Q4_K_M.gguf",
            sizeBytes = 5680522464L,
            minMemGb = 12,
            tags = listOf("tools"),
        ),
    )
}
