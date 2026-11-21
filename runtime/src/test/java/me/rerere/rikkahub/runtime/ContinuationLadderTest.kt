package me.rerere.rikkahub.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A1 escalation ladder (loop contract, AURA-021 v1.0): every continuation MUST raise
 * max_tokens strictly above the prior attempt — baseline is the assistant's configured
 * maxTokens of the attempt being continued. This test is the A1 clause of
 * floor/spec-loop-v2-contract.md; the LC-3 cap (2) and the visible terminal failure are
 * device-proven in z7 G1, the ladder itself is pinned here.
 */
class ContinuationLadderTest {

    @Test
    fun `first escalation starts from the assistant baseline and is strictly larger`() {
        val first = nextEscalatedMaxTokens(previous = null, baseline = 1024)
        assertTrue("must exceed baseline", first > 1024)
        assertEquals(1024 + 512, first)
    }

    @Test
    fun `ladder is strictly increasing for both small and large budgets`() {
        var prevSmall: Int? = null
        var last = 256
        repeat(4) {
            val next = nextEscalatedMaxTokens(prevSmall, last)
            assertTrue("small budget ladder must strictly increase", next > last)
            prevSmall = next
            last = next
        }
        var a = nextEscalatedMaxTokens(null, 8192)
        var b = nextEscalatedMaxTokens(a, 8192)
        var c = nextEscalatedMaxTokens(b, 8192)
        assertTrue(a < b && b < c)
        assertEquals(8192 + 4096, a)
        assertEquals(a + maxOf(256, a / 2), b)
        assertEquals(b + maxOf(256, b / 2), c)
    }

    @Test
    fun `tiny budgets still get a visible minimum jump`() {
        assertEquals(64 + 256, nextEscalatedMaxTokens(null, 64))
        assertEquals(320 + 256, nextEscalatedMaxTokens(320, 64))
    }

    @Test
    fun `MAX_CONTINUATIONS is the contract cap of two`() {
        assertEquals(2, MAX_CONTINUATIONS)
    }
}
