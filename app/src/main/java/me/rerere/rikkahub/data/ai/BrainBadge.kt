package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import java.net.URI

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

    /**
     * US-009r (AC1 granularity): what a single **message** records — the kind plus *which* brain
     * answered, so a bubble can be traced to the agent or model that wrote it instead of to a
     * brand. [stored] stays the coarse chip-label source; this is the per-message payload.
     *
     *  - Nazarick → `tomb:<agentId>@<host>`. The host is read out of `baseUrl` with scheme, port,
     *    path and userinfo dropped ([URI.host]), because the Bearer token can ride in that URL and
     *    a message row is persisted data. An empty or unparseable `baseUrl` records `?` rather
     *    than dropping the record; the tomb is still the brain even when no model id resolved,
     *    which is why this branch keys on the provider and not on [kind].
     *  - any other provider with a model → `edge:<modelId after the last '/'>`, so the
     *    vendor-qualified id `google/gemini-x` records as `edge:gemini-x`.
     *  - an edge turn with no model → the bare `edge`; no provider at all → `""`, the legacy
     *    value, deliberately indistinguishable from a pre-badge message and so rendering no chip.
     *
     * The text before `:` is always one of the [stored] literals, so readers that only know the
     * coarse kind — [me.rerere.rikkahub.ui.pages.chat.brainBadgeLabel] — keep working unchanged.
     */
    fun storedDetail(model: Model?, provider: ProviderSetting?): String = when {
        provider == null -> ""
        provider is ProviderSetting.Nazarick -> tombDetail(provider)
        model == null -> STORED_EDGE
        else -> model.modelId
            .substringAfterLast('/')
            .takeIf { it.isNotEmpty() }
            ?.let { "$STORED_EDGE:$it" }
            ?: STORED_EDGE
    }

    /** `tomb:<agentId>@<host>`; [UNKNOWN_HOST] when `baseUrl` carries no parseable host. */
    private fun tombDetail(provider: ProviderSetting.Nazarick): String {
        val host = runCatching { URI(provider.baseUrl).host }.getOrNull()
        return "$STORED_TOMB:${provider.agentId}@${host ?: UNKNOWN_HOST}"
    }

    private const val UNKNOWN_HOST = "?"

    fun kind(model: Model?, provider: ProviderSetting?): BadgeKind {
        if (model == null || provider == null) return BadgeKind.NONE
        return when (provider) {
            is ProviderSetting.Nazarick -> BadgeKind.TOMB
            else -> BadgeKind.EDGE
        }
    }
}
