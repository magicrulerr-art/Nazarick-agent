package me.rerere.rikkahub.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keyword recall - the shape `memory_search` takes when there is nothing to embed with.
 *
 * The behaviour that matters is not "does BM25 sort" (it does, or the formula is wrong) but the
 * degradation contract: a declined embedding model must still return the notes, must never invent
 * semantic confidence, and must never crash a tool call the model could still act on. Those are
 * what the z7 failure mode looked like: the tool answered "unavailable" while the answer sat in a
 * Markdown file two directories away.
 */
class KeywordRecallTest {

    private fun rank(query: String, vararg docs: String) =
        KeywordRecall.rank(query, docs.mapIndexed { index, text -> "doc$index" to text }.toList(), limit = 10)

    private fun files(result: VectorSearchResult<String>): List<String> = result.hits.map { it.item }

    @Test
    fun `tokenizing keeps words and numbers and drops punctuation and single characters`() {
        assertEquals(
            listOf("z7", "uat", "gauntlet", "v1"),
            KeywordRecall.tokenize("z7 UAT-A gauntlet, v1.0!"),
        )
        // Letters from other scripts survive: the fleet's own notes are not English-only.
        assertEquals(listOf("модель", "нет"), KeywordRecall.tokenize("модель — нет"))
        assertTrue(KeywordRecall.tokenize("").isEmpty())
    }

    @Test
    fun `a document with no term overlap is not returned as filler`() {
        val result = rank("streaming", "we shipped the vps release", "nothing related here at all")
        assertEquals(emptyList<String>(), files(result))
    }

    @Test
    fun `an empty or untokenizable query returns no hits instead of throwing`() {
        // A 4B model happily sends query="" or query="a b"; the tool must answer, not crash.
        assertEquals(emptyList<String>(), files(rank("", "any document")))
        assertEquals(emptyList<String>(), files(rank("  ", "any document")))
        assertEquals(emptyList<String>(), files(rank("a b", "a document about a b")))
    }

    @Test
    fun `an empty corpus returns no hits`() {
        assertEquals(emptyList<String>(), files(rank("release")))
    }

    @Test
    fun `the document that actually matches the topic ranks first`() {
        val result = rank(
            "why did the streaming build fail",
            "we discussed dinner plans and the weather in Nazarick",
            "the streaming build failed because the tokenizer rejected the template",
            "a recipe for soup",
        )
        assertEquals("doc1", files(result).first())
        assertTrue(result.hits.all { it.score > 0f })
    }

    @Test
    fun `a rare term outranks a term that appears in every document`() {
        // idf is the whole point of not shipping grep: a query word present in the entire corpus
        // carries no signal, and a document matching only the discriminating word must not win.
        val corpus = listOf(
            "the build log mentions deploy and nothing else useful",
            "every note here mentions build, this one also mentions deploy rarely",
            "build build build build build, a wall of the common word",
        ).mapIndexed { index, text -> "doc$index" to text }

        val result = KeywordRecall.rank("build deploy", corpus, limit = 10)
        assertEquals(3, result.hits.size)
        // docs 0 and 1 both contain both terms; doc 2 has only the common one and must land last.
        assertEquals("doc2", files(result).last())
    }

    @Test
    fun `term frequency saturates instead of rewarding repetition linearly`() {
        val single = "the deploy script runs rel7.sh"
        val spam = ("$single " + "deploy ".repeat(40)).trim()
        val result = rank("deploy", "unrelated text about soup", spam, single)

        val scores = result.hits.associate { it.item to it.score }
        assertTrue("both documents match", listOf("doc1", "doc2").all { it in scores })
        // 40 mentions may beat 1, but not by 40x: with k1=1.2 the ceiling is ~(k1+1)/1 per term.
        assertTrue(
            "repetition must saturate, got ${scores["doc1"]} vs ${scores["doc2"]}",
            scores.getValue("doc1") < 4f * scores.getValue("doc2"),
        )
    }

    @Test
    fun `a long document is penalized relative to a short one with the same match`() {
        val shortDoc = "deploy rel7.sh"
        val longDoc = "deploy " + "padding words that dilute this document ".repeat(20)
        val result = rank("deploy", longDoc, shortDoc)

        assertEquals(
            "same term, shorter document wins under the length norm",
            "doc1",
            files(result).first(),
        )
    }

    @Test
    fun `limit is respected and the best-scoring documents are the ones kept`() {
        val corpus = (1..6).map { index -> "doc$index" to "deploy report number $index" }
        val result = KeywordRecall.rank("deploy", corpus, limit = 2)

        assertEquals(2, result.hits.size)
        assertTrue("scores must come back sorted descending", result.hits[0].score >= result.hits[1].score)
    }

    @Test
    fun `equal scores keep corpus order so a re-run answers the same way`() {
        // Greedy sampling plus a shuffled tie-break is how a small model gets two different
        // answers to the same question in the same conversation.
        val corpus = (1..4).map { index -> "doc$index" to "deploy" }
        val result = KeywordRecall.rank("deploy", corpus, limit = 10)

        assertEquals(listOf("doc1", "doc2", "doc3", "doc4"), files(result))
    }

    @Test
    fun `a limit of zero asks for nothing and gets nothing`() {
        assertEquals(0, KeywordRecall.rank("deploy", listOf("a" to "deploy"), limit = 0).hits.size)
    }

    @Test
    fun `scores are finite and positive, never the negative idf of an unseen term`() {
        val result = rank("deploy", "deploy deploy", "deploy once", "deploy in a very long document " + "tail ".repeat(50))
        assertFalse(result.hits.isEmpty())
        result.hits.forEach { hit ->
            assertTrue("score ${hit.score} must be finite", !hit.score.isNaN() && !hit.score.isInfinite())
            assertTrue("score ${hit.score} must be positive", hit.score > 0f)
        }
    }

    @Test
    fun `the marker names the mode so a bm25 number is never read as a cosine similarity`() {
        assertEquals("keyword-fallback", KeywordRecall.MARKER)
    }
}
