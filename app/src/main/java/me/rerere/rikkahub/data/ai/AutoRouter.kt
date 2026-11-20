package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.BrainPolicy

/**
 * Where a turn actually goes. Pure data, no Android deps (Companion-Edge V2 US-010).
 *
 *  - [TOMB]                the turn is handed to the enabled Nazarick instance agent.
 *  - [EDGE]                the turn is answered locally, through the existing engines chain.
 *  - [EDGE_OFFLINE_NOTICE] same as [EDGE], but the caller additionally tells the user that the
 *                          Tomb was unreachable: the answer is local, and it is not what the
 *                          AUTO policy would have preferred.
 */
enum class Decision { TOMB, EDGE, EDGE_OFFLINE_NOTICE }

/**
 * The routing half of AUTO (US-010). An explicit [BrainPolicy.EDGE] or [BrainPolicy.TOMB] is the
 * user's word and is never second-guessed; only [BrainPolicy.AUTO] is resolved here, and only
 * against two facts the caller already holds - whether the Tomb can be reached right now, and
 * whether dropping to the local chain would even land on a model.
 *
 * Offline AUTO keeps using the Tomb when there is nothing local to fall back to: a queued turn is
 * better than a failed one, and [TombQueue] is what makes "later" possible.
 */
object AutoRouter {

    /**
     * Remaining-token budget floor (US-010r): below remaining-budget, prefer edge. When the caller
     * already knows how many tokens are left before the context window is exhausted and that number
     * is under this threshold, AUTO spends its last breath on the local chain instead of risking a
     * Tomb round-trip it can no longer afford.
     */
    const val TOKEN_BUDGET_THRESHOLD_TOKENS = 8_000

    fun decide(
        policy: BrainPolicy,
        tombReachable: Boolean,
        hasLocalFallback: Boolean,
    ): Decision = when (policy) {
        BrainPolicy.EDGE -> Decision.EDGE
        BrainPolicy.TOMB -> Decision.TOMB
        BrainPolicy.AUTO -> when {
            tombReachable -> Decision.TOMB
            hasLocalFallback -> Decision.EDGE_OFFLINE_NOTICE
            else -> Decision.TOMB
        }
    }

    /**
     * Budget-aware [decide] (US-010r). Same routing table, with one extra guard: below
     * remaining-budget, prefer edge - if [remainingTokens] is under [TOKEN_BUDGET_THRESHOLD_TOKENS]
     * and a local model can actually answer, AUTO stays local even though the Tomb is reachable,
     * spending the scarce tokens where the round-trip cannot afford them. Explicit policies are
     * untouched; an offline Tomb still routes as usual, because its [Decision.EDGE_OFFLINE_NOTICE]
     * says more to the user than the budget does.
     */
    fun decide(
        policy: BrainPolicy,
        tombReachable: Boolean,
        hasLocalFallback: Boolean,
        remainingTokens: Int,
    ): Decision {
        if (
            policy == BrainPolicy.AUTO &&
            tombReachable &&
            hasLocalFallback &&
            remainingTokens < TOKEN_BUDGET_THRESHOLD_TOKENS
        ) {
            return Decision.EDGE
        }
        return decide(policy, tombReachable, hasLocalFallback)
    }
}
