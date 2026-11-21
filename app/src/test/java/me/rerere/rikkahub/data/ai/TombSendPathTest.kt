package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.BrainPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * US-010 AC2: a Tomb-bound turn submitted while the Tomb is unreachable is enqueued and flushed
 * automatically on reconnect, order preserved.
 *
 * This test pins the send-path contract that [TombSendGate] implements, and it is deliberately pure
 * JUnit4: the gate takes the probe answer and the already-resolved policy as plain arguments, so
 * neither Android nor a network is needed to prove the ordering rule the acceptance criterion names.
 *
 * Caller contract pinned here: the gate is consulted only for turns the router already resolved to
 * the Tomb (explicit [BrainPolicy.TOMB], or [BrainPolicy.AUTO] whose [AutoRouter] decision came
 * back [Decision.TOMB]). Turns AutoRouter dropped to the local chain never reach the gate, so the
 * gate needs no `hasLocalFallback` argument of its own.
 */
class TombSendPathTest {

    @Before
    fun dropPendingTurns() = TombSendGate.clear()

    @Test
    fun offlineTombTurnsAreQueuedAndFlushedInOrder() {
        val first = TombSendGate.onSubmit(
            policy = BrainPolicy.TOMB,
            tombReachable = false,
            turnText = "first offline turn",
            turnId = "turn-1",
        )
        val second = TombSendGate.onSubmit(
            policy = BrainPolicy.AUTO,
            tombReachable = false,
            turnText = "second offline turn",
            turnId = "turn-2",
        )

        assertEquals(TombSendGate.Gate.QUEUED, first)
        assertEquals(TombSendGate.Gate.QUEUED, second)

        val flushed = TombSendGate.drainOnReconnect(tombReachable = true)

        assertEquals(listOf("turn-1", "turn-2"), flushed.map { it.id })
        assertEquals(listOf("first offline turn", "second offline turn"), flushed.map { it.text })
        assertTrue("flush must leave nothing behind", TombSendGate.drainOnReconnect(true).isEmpty())
    }

    @Test
    fun reachableTombNeverQueuesAndNothingIsHeldBack() {
        for (policy in BrainPolicy.entries) {
            assertEquals(
                "reachable turns pass through for $policy",
                TombSendGate.Gate.PASSED_THROUGH,
                TombSendGate.onSubmit(
                    policy = policy,
                    tombReachable = true,
                    turnText = "live turn",
                    turnId = "live-${policy.name}",
                ),
            )
        }
        assertTrue(TombSendGate.drainOnReconnect(tombReachable = true).isEmpty())
    }

    @Test
    fun unreachableAutoAndTombHoldOneEntryPerTurnId() {
        // Unreachable + local fallback available: AutoRouter sends EDGE turns to the local chain, so
        // only the Tomb-bound policies may ever be held.
        assertEquals(
            TombSendGate.Gate.PASSED_THROUGH,
            TombSendGate.onSubmit(BrainPolicy.EDGE, false, "local answer", "edge-1"),
        )

        assertEquals(
            TombSendGate.Gate.QUEUED,
            TombSendGate.onSubmit(BrainPolicy.TOMB, false, "spoken once", "turn-dup"),
        )
        assertEquals(
            TombSendGate.Gate.QUEUED,
            TombSendGate.onSubmit(BrainPolicy.TOMB, false, "spoken twice", "turn-dup"),
        )

        val flushed = TombSendGate.drainOnReconnect(tombReachable = true)
        assertEquals(1, flushed.size)
        assertEquals(listOf("turn-dup"), flushed.map { it.id })
        assertEquals(listOf("spoken once"), flushed.map { it.text })
    }
}
