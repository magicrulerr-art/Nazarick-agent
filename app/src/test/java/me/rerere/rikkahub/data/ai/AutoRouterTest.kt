package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.BrainPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * US-010: every branch of the AUTO routing table, plus the explicit guarantee that an offline AUTO
 * turn says so instead of silently answering locally.
 */
class AutoRouterTest {

    @Test
    fun explicitEdgeAlwaysRoutesEdge() {
        assertEquals(Decision.EDGE, AutoRouter.decide(BrainPolicy.EDGE, tombReachable = true, hasLocalFallback = true))
        assertEquals(Decision.EDGE, AutoRouter.decide(BrainPolicy.EDGE, tombReachable = true, hasLocalFallback = false))
        assertEquals(Decision.EDGE, AutoRouter.decide(BrainPolicy.EDGE, tombReachable = false, hasLocalFallback = true))
        assertEquals(Decision.EDGE, AutoRouter.decide(BrainPolicy.EDGE, tombReachable = false, hasLocalFallback = false))
    }

    @Test
    fun explicitTombAlwaysRoutesTomb() {
        assertEquals(Decision.TOMB, AutoRouter.decide(BrainPolicy.TOMB, tombReachable = true, hasLocalFallback = true))
        assertEquals(Decision.TOMB, AutoRouter.decide(BrainPolicy.TOMB, tombReachable = true, hasLocalFallback = false))
        assertEquals(Decision.TOMB, AutoRouter.decide(BrainPolicy.TOMB, tombReachable = false, hasLocalFallback = true))
        assertEquals(Decision.TOMB, AutoRouter.decide(BrainPolicy.TOMB, tombReachable = false, hasLocalFallback = false))
    }

    @Test
    fun autoRoutesTombWhileTombIsReachable() {
        assertEquals(Decision.TOMB, AutoRouter.decide(BrainPolicy.AUTO, tombReachable = true, hasLocalFallback = true))
        assertEquals(Decision.TOMB, AutoRouter.decide(BrainPolicy.AUTO, tombReachable = true, hasLocalFallback = false))
    }

    @Test
    fun offlineAutoShowsNoticeWhenLocalFallbackExists() {
        assertEquals(
            Decision.EDGE_OFFLINE_NOTICE,
            AutoRouter.decide(BrainPolicy.AUTO, tombReachable = false, hasLocalFallback = true),
        )
    }

    @Test
    fun offlineAutoKeepsTombWhenNothingLocalExists() {
        assertEquals(
            Decision.TOMB,
            AutoRouter.decide(BrainPolicy.AUTO, tombReachable = false, hasLocalFallback = false),
        )
    }

    /**
     * US-010r: the token budget threshold exists, is positive, and is genuinely consulted by the
     * [AutoRouter.decide] overload that takes remainingTokens - below remaining-budget, prefer edge.
     */
    @Test
    fun tokenBudgetThresholdIsPositiveAndUsedByDecideOverload() {
        assertTrue(AutoRouter.TOKEN_BUDGET_THRESHOLD_TOKENS > 0)
        assertEquals(8_000, AutoRouter.TOKEN_BUDGET_THRESHOLD_TOKENS)

        // Under budget: AUTO drops to the local chain even though the Tomb is reachable.
        assertEquals(
            Decision.EDGE,
            AutoRouter.decide(
                BrainPolicy.AUTO,
                tombReachable = true,
                hasLocalFallback = true,
                remainingTokens = AutoRouter.TOKEN_BUDGET_THRESHOLD_TOKENS - 1,
            ),
        )
        // At/above budget: the plain routing table still applies, so a reachable Tomb wins.
        assertEquals(
            Decision.TOMB,
            AutoRouter.decide(
                BrainPolicy.AUTO,
                tombReachable = true,
                hasLocalFallback = true,
                remainingTokens = AutoRouter.TOKEN_BUDGET_THRESHOLD_TOKENS,
            ),
        )
        // Nothing local to fall back to: the threshold cannot conjure an edge model.
        assertEquals(
            Decision.TOMB,
            AutoRouter.decide(
                BrainPolicy.AUTO,
                tombReachable = true,
                hasLocalFallback = false,
                remainingTokens = AutoRouter.TOKEN_BUDGET_THRESHOLD_TOKENS - 1,
            ),
        )
        // Offline keeps its notice even under budget: the user is told the Tomb was gone.
        assertEquals(
            Decision.EDGE_OFFLINE_NOTICE,
            AutoRouter.decide(
                BrainPolicy.AUTO,
                tombReachable = false,
                hasLocalFallback = true,
                remainingTokens = 1,
            ),
        )
        // Explicit policies are the user's word and the threshold never overrides them.
        assertEquals(
            Decision.TOMB,
            AutoRouter.decide(
                BrainPolicy.TOMB,
                tombReachable = true,
                hasLocalFallback = true,
                remainingTokens = 1,
            ),
        )
    }
}
