package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.BrainPolicy
import org.junit.Assert.assertEquals
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
}
