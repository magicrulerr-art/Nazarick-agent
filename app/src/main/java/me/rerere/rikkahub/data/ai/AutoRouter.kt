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
}
