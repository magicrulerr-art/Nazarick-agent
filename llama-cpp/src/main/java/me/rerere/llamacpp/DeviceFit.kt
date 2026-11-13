package me.rerere.llamacpp

/**
 * Hardware survey (AURA-020 Phase 1.5, port of the Off Grid AI "picks for this device"
 * idea): rank the curated catalog against the RAM this device actually has and hand the
 * picker three slots - Fast, Balanced, Best. Pure by construction like [ContextPlanner]:
 * the catalog contributes its own sizes, the device contributes one number, and the
 * whole ranking runs in JVM tests without a phone.
 */
object DeviceFit {
    /** Weights plus KV plus headroom need slack; never recommend a file eating more
     *  than this share of device RAM. Mirrors MemoryGuard's posture without importing it. */
    private const val SIZE_RAM_CEILING = 0.75
    private const val GB = 1_000_000_000.0

    /** An entry is installable here when its file stays under the size ceiling AND the
     *  catalog's own minMemGb agrees. */
    fun fits(entry: LlamaCppCatalogEntry, deviceRamGb: Double): Boolean =
        deviceRamGb > 0.0 &&
            entry.sizeBytes <= deviceRamGb * SIZE_RAM_CEILING * GB &&
            entry.minMemGb <= deviceRamGb

    /** OGAM's ramFitScore, same shape: sweet spot when weights take ~40% of RAM, hard
     *  penalty past 75%. Lower is better. */
    fun ramFitScore(entry: LlamaCppCatalogEntry, deviceRamGb: Double): Double {
        if (deviceRamGb <= 0.0) return Double.MAX_VALUE
        val ratio = entry.sizeBytes / (deviceRamGb * GB)
        val penalty = if (ratio > 0.75) (ratio - 0.75) * 4.0 else 0.0
        return Math.abs(ratio - 0.4) + penalty
    }

    data class Picks(
        val fast: LlamaCppCatalogEntry?,
        val balanced: LlamaCppCatalogEntry?,
        val best: LlamaCppCatalogEntry?,
    ) {
        val isEmpty: Boolean get() = fast == null && balanced == null && best == null
    }

    /** Fast = smallest capable entry; Best = largest under the ceiling; Balanced =
     *  largest that still leaves half the device's RAM untouched. Slots degrade into
     *  each other on small devices rather than showing empty rows. */
    fun picks(entries: List<LlamaCppCatalogEntry>, deviceRamGb: Double): Picks {
        val ok = entries.filter { fits(it, deviceRamGb) }.sortedBy { it.sizeBytes }
        if (ok.isEmpty()) return Picks(null, null, null)
        val fast = ok.first()
        val best = ok.last()
        val balanced = ok.filter { it.sizeBytes <= deviceRamGb * 0.5 * GB }.lastOrNull() ?: fast
        return Picks(fast, balanced, best)
    }
}
