package me.rerere.rikkahub.runtime.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extraction is one background model call whose output nobody reviews before it is stored. The
 * parser is therefore the only thing between "the model wrote a nice sentence" and "the fact
 * table is polluted", and it must degrade to fewer facts rather than to an exception.
 */
class MemoryFactRulesTest {

    @Test
    fun `a plain response is parsed`() {
        val facts = MemoryFactRules.parse(
            """{"facts":[{"subject":"user","predicate":"lives_in","object":"Shanghai","confidence":0.9}]}"""
        )
        assertEquals(1, facts.size)
        assertEquals("user", facts[0].subject)
        assertEquals("lives_in", facts[0].predicate)
        assertEquals("Shanghai", facts[0].objectValue)
        assertEquals(0.9, facts[0].confidence, 1e-6)
    }

    @Test
    fun `json wrapped in prose is still found`() {
        val raw = """
            Sure! Here is what I extracted:

            {"facts":[{"subject":"gateway","predicate":"requires_header","object":"X-Admin-Token","confidence":0.8}]}

            Let me know if you need anything else.
        """.trimIndent()
        val facts = MemoryFactRules.parse(raw)
        assertEquals(1, facts.size)
        assertEquals("X-Admin-Token", facts[0].objectValue)
    }

    @Test
    fun `one malformed entry does not discard the batch`() {
        val raw = """
            {"facts":[
              {"subject":"user","predicate":"prefers","object":"brief replies","confidence":0.9},
              {"subject":"","predicate":"uses","object":"vivo","confidence":0.9},
              {"subject":"user","predicate":"uses","object":"Termux","confidence":0.9}
            ]}
        """.trimIndent()
        val facts = MemoryFactRules.parse(raw)
        assertEquals(listOf("brief replies", "Termux"), facts.map { it.objectValue })
    }

    @Test
    fun `duplicate triples in one batch collapse`() {
        val raw = """
            {"facts":[
              {"subject":"user","predicate":"lives_in","object":"Shanghai","confidence":0.9},
              {"subject":"User","predicate":"lives_in","object":"shanghai","confidence":0.8}
            ]}
        """.trimIndent()
        // Same key, different casing of the object: the key dedupes them, which is the point —
        // the second would otherwise supersede the first in the same write.
        assertEquals(1, MemoryFactRules.parse(raw).size)
    }

    @Test
    fun `confidence words are understood`() {
        val raw = """
            {"facts":[
              {"subject":"a","predicate":"p1","object":"o1","confidence":"high"},
              {"subject":"b","predicate":"p2","object":"o2","confidence":"low"}
            ]}
        """.trimIndent()
        val facts = MemoryFactRules.parse(raw)
        assertEquals(listOf("o1"), facts.map { it.objectValue })
        assertEquals(0.9, facts[0].confidence, 1e-6)
    }

    @Test
    fun `a missing confidence is assumed rather than discarded`() {
        val raw = """{"facts":[{"subject":"user","predicate":"works_on","object":"Nazarick"}]}"""
        val facts = MemoryFactRules.parse(raw)
        assertEquals(1, facts.size)
        assertTrue(facts[0].confidence >= MemoryFactRules.MIN_CONFIDENCE)
    }

    @Test
    fun `the batch is capped`() {
        val entries = (1..20).joinToString(",") {
            """{"subject":"s$it","predicate":"p$it","object":"o$it","confidence":0.9}"""
        }
        val facts = MemoryFactRules.parse("""{"facts":[$entries]}""")
        assertEquals(MemoryFactRules.MAX_FACTS_PER_EXTRACTION, facts.size)
    }

    @Test
    fun `an empty facts array is a valid answer`() {
        assertTrue(MemoryFactRules.parse("""{"facts":[]}""").isEmpty())
    }

    @Test
    fun `garbage degrades to no facts instead of throwing`() {
        assertTrue(MemoryFactRules.parse("I could not find anything durable.").isEmpty())
        assertTrue(MemoryFactRules.parse("{not json at all}").isEmpty())
        assertTrue(MemoryFactRules.parse("").isEmpty())
    }

    @Test
    fun `predicates are normalised to snake case`() {
        assertEquals("lives_in", MemoryFactRules.normalizePredicate("  Lives In  "))
        assertEquals("requires", MemoryFactRules.normalizePredicate("_requires_"))
    }

    @Test
    fun `quotes around a value are stripped`() {
        // The object keeps its case (it can be a header name or an ID); the subject does not,
        // because it is a lookup key.
        assertEquals("Shanghai", MemoryFactRules.normalizeObject("\"Shanghai\""))
        assertEquals("shanghai", MemoryFactRules.normalizeSubject("'Shanghai'"))
    }

    @Test
    fun `the supersede key ignores the object`() {
        val a = ExtractedFact("user", "lives_in", "Beijing", 0.9)
        val b = ExtractedFact("user", "lives_in", "Shanghai", 0.9)
        assertEquals(MemoryFactRules.keyOf(a), MemoryFactRules.keyOf(b))
    }

    @Test
    fun `same assertion is case and whitespace insensitive`() {
        val candidate = ExtractedFact("user", "lives_in", "Shanghai", 0.9)
        assertTrue(MemoryFactRules.isSameAssertion("  shanghai ", candidate))
        assertFalse(MemoryFactRules.isSameAssertion("Beijing", candidate))
    }
}
