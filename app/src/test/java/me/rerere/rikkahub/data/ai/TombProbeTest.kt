package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * US-010r: the probe must report an unreachable Tomb as false, and it must do so *fast* - a probe
 * that blocks the resolver for seconds would make offline AUTO worse than no probe at all.
 */
class TombProbeTest {

    @Test
    fun closedPortOnLoopbackIsUnreachableAndFailsFast() {
        // Port 1 on loopback refuses connections immediately - no DNS, no routing involved.
        val startedAt = System.nanoTime()
        val reachable = TombProbe.probe("http://127.0.0.1:1")
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertFalse(reachable)
        assertTrue("probe took ${elapsedMs}ms, must fail within 3000ms", elapsedMs < 3_000)
    }

    @Test
    fun malformedBaseUrlIsUnreachableInsteadOfThrowing() {
        assertFalse(TombProbe.probe("not a url"))
        assertFalse(TombProbe.probe(""))
    }
}
