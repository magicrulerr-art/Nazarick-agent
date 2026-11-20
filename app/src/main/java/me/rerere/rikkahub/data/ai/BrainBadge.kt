package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting

/**
 * US-009: which answering brain is currently in use, as a badge.
 *
 * Pure rule, no state: Nazarick-branded providers answer from the Tomb,
 * everything else answers from the Edge.
 */
enum class BadgeKind { NONE, EDGE, TOMB }

object BrainBadge {

    /**
     * US-009r: the persisted form of a kind, written into `UIMessage.brainKind` when a turn
     * finalizes. Lower-case literals matching `BrainPolicy`'s `@SerialName` values, because the
     * field records which brain answered and must read the same way in a dump either way.
     * [BadgeKind.NONE] stores `""` — the same value a legacy message decodes to, so "no brain
     * recorded" and "this message predates the badge" are deliberately indistinguishable and
     * both render no chip.
     */
    fun stored(kind: BadgeKind): String = when (kind) {
        BadgeKind.TOMB -> STORED_TOMB
        BadgeKind.EDGE -> STORED_EDGE
        BadgeKind.NONE -> ""
    }

    const val STORED_TOMB = "tomb"
    const val STORED_EDGE = "edge"

    fun kind(model: Model?, provider: ProviderSetting?): BadgeKind {
        if (model == null || provider == null) return BadgeKind.NONE
        return when (provider) {
            is ProviderSetting.Nazarick -> BadgeKind.TOMB
            else -> BadgeKind.EDGE
        }
    }
}
