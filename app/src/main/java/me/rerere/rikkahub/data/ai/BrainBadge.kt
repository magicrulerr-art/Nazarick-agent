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

    fun kind(model: Model?, provider: ProviderSetting?): BadgeKind {
        if (model == null || provider == null) return BadgeKind.NONE
        return when (provider) {
            is ProviderSetting.Nazarick -> BadgeKind.TOMB
            else -> BadgeKind.EDGE
        }
    }
}
