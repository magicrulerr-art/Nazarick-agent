package me.rerere.rikkahub.runtime.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The write gate is the only thing standing between a model's restatement habit and a memory
 * bank that grows without gaining information. These tests pin the three refusals and — just as
 * important — the cases that must **not** be refused.
 */
class MemoryWriteRulesTest {

    private fun record(id: Int, content: String, kind: MemoryKind = MemoryKind.NOTE) =
        MemoryRecord(id = id, content = content, kind = kind)

    // ---------------------------------------------------------------- normalisation

    @Test
    fun `normalise folds case whitespace and trailing punctuation`() {
        assertEquals("user prefers brief replies", MemoryWriteRules.normalize("  User   prefers brief replies.  "))
        assertEquals("用户喜欢简洁", MemoryWriteRules.normalize("用户喜欢简洁。"))
    }

    // ---------------------------------------------------------------- deterministic gate

    @Test
    fun `blank content is refused`() {
        val decision = MemoryWriteRules.decide("   \n ", emptyList())
        assertEquals(MemoryWriteDecision.Reject(MemoryRejectReason.BLANK), decision)
    }

    @Test
    fun `exact duplicate points at the existing record`() {
        val existing = listOf(record(7, "User prefers brief replies."))
        val decision = MemoryWriteRules.decide("user prefers brief replies", existing)
        assertEquals(
            MemoryWriteDecision.Reject(MemoryRejectReason.DUPLICATE, existing.single()),
            decision,
        )
    }

    @Test
    fun `a reworded restatement is refused with containment`() {
        val existing = listOf(record(3, "User prefers brief replies and dislikes padding"))
        val decision = MemoryWriteRules.decide(
            "User prefers brief replies and dislikes padding in answers",
            existing,
        )
        assertTrue("expected a duplicate, got $decision", decision is MemoryWriteDecision.Reject)
        assertEquals(3, (decision as MemoryWriteDecision.Reject).existing?.id)
    }

    @Test
    fun `chinese restatements are matched by bigram containment`() {
        val existing = listOf(record(5, "用户偏好简洁的回复方式"))
        val decision = MemoryWriteRules.decide("用户偏好简洁的回复", existing)
        assertEquals(
            MemoryWriteDecision.Reject(MemoryRejectReason.DUPLICATE, existing.single()),
            decision,
        )
    }

    @Test
    fun `a genuinely different memory is accepted`() {
        val existing = listOf(
            record(1, "User prefers brief replies"),
            record(2, "User works on an Android agent called Nazarick"),
        )
        assertEquals(
            MemoryWriteDecision.Accept,
            MemoryWriteRules.decide("User's timezone is China Standard Time", existing),
        )
    }

    @Test
    fun `short records fall back to equality instead of containment`() {
        // "Alex" and "Alexa" share one token; a ratio-only rule would call them duplicates.
        val existing = listOf(record(1, "Alex"))
        assertEquals(
            MemoryWriteDecision.Accept,
            MemoryWriteRules.decide("Alexa", existing),
        )
    }

    @Test
    fun `duplicate wins over capacity so the model gets an actionable error`() {
        val existing = (1..3).map { record(it, "fact number $it about the user") }
        val decision = MemoryWriteRules.decide(
            content = "fact number 1 about the user",
            existing = existing,
            cap = 3,
        )
        assertTrue(decision is MemoryWriteDecision.Reject)
        assertEquals(MemoryRejectReason.DUPLICATE, (decision as MemoryWriteDecision.Reject).reason)
    }

    @Test
    fun `capacity is refused once the bank is full`() {
        val existing = (1..3).map { record(it, "fact number $it about the user") }
        val decision = MemoryWriteRules.decide(
            content = "a completely unrelated new fact",
            existing = existing,
            cap = 3,
        )
        assertEquals(MemoryWriteDecision.Reject(MemoryRejectReason.CAPACITY), decision)
    }

    // ---------------------------------------------------------------- semantic tier

    @Test
    fun `cosine of identical vectors is one`() {
        val v = floatArrayOf(0.1f, 0.2f, 0.3f)
        assertEquals(1.0, MemoryWriteRules.cosine(v, v.copyOf()), 1e-6)
    }

    @Test
    fun `cosine of a width mismatch is zero rather than an exception`() {
        assertEquals(
            0.0,
            MemoryWriteRules.cosine(floatArrayOf(1f, 2f), floatArrayOf(1f, 2f, 3f)),
            1e-9,
        )
    }

    @Test
    fun `cosine of a zero vector is zero`() {
        assertEquals(
            0.0,
            MemoryWriteRules.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)),
            1e-9,
        )
    }

    @Test
    fun `embedding duplicate reports the closest record above the threshold`() {
        val near = record(11, "dislikes long answers")
        val far = record(12, "lives in Shanghai")
        val hit = MemoryWriteRules.duplicateByEmbedding(
            candidate = floatArrayOf(1f, 0f, 0f),
            stored = listOf(
                near to floatArrayOf(0.99f, 0.01f, 0f),
                far to floatArrayOf(0f, 1f, 0f),
            ),
        )
        assertEquals(near, hit)
    }

    @Test
    fun `embedding duplicate returns null when nothing is close enough`() {
        val existing = record(1, "unrelated")
        assertNull(
            MemoryWriteRules.duplicateByEmbedding(
                candidate = floatArrayOf(1f, 0f),
                stored = listOf(existing to floatArrayOf(0f, 1f)),
            )
        )
    }

    // ---------------------------------------------------------------- hints

    @Test
    fun `duplicate hint names the id and the edit action`() {
        val hint = MemoryWriteRules.rejectionHint(MemoryRejectReason.DUPLICATE, record(42, "x"))
        assertTrue(hint.contains("42"))
        assertTrue(hint.contains("edit"))
    }

    @Test
    fun `capacity hint asks for a delete or merge`() {
        val hint = MemoryWriteRules.rejectionHint(MemoryRejectReason.CAPACITY, null)
        assertTrue(hint.contains("full", ignoreCase = true))
    }
}
