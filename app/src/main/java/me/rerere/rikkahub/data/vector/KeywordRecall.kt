package me.rerere.rikkahub.data.vector

/**
 * Keyword recall for the state where there is nothing to embed with.
 *
 * `memory_search` used to answer "unavailable" the moment the embedding model was missing or
 * declined by [me.rerere.llamacpp.MemoryBudget], which threw away the one thing that does not
 * need a model: the text of the notes themselves. This is that text, ranked by Okapi BM25 -
 * per-term frequency with saturation, a length norm, and an inverse-document-frequency weight so
 * a rare word outranks a word that appears in every file. Pure JVM, no tokenizer, no model.
 *
 * Scores are BM25, NOT cosine: they share no scale with [VectorSearchRules] and mean nothing
 * across the two modes, which is exactly why every hit produced here has to be labelled (see
 * [MARKER]). A model that compares a 7.4 keyword score against a 0.82 similarity will draw the
 * wrong conclusion, and the label is the only thing preventing it.
 */
object KeywordRecall {

    /** Written into every degraded result so the reader knows which yardstick it is holding. */
    const val MARKER = "keyword-fallback"

    /** Term-frequency saturation. 1.2 is the textbook default; higher rewards raw repetition. */
    private const val K1 = 1.2

    /** Length normalization. 0 = no penalty for long chunks, 1 = full penalty. */
    private const val B = 0.75

    /** Queries are short; a one-character token is noise ("a", "и") that matches everything. */
    private const val MIN_TOKEN_LENGTH = 2

    /**
     * Lower-cases and splits on anything that is not a letter or a digit.
     *
     * Deliberately not a stemmer: stemming would merge "test"/"testing" at the cost of silently
     * merging things it should not, and the notes are written by the model itself, so vocabulary
     * is already consistent. Character-class judgement follows [Character.isLetterOrDigit] so
     * Cyrillic and CJK survive (they are what the fleet's own notes contain).
     */
    fun tokenize(text: String): List<String> {
        val tokens = ArrayList<String>(text.length / 6 + 1)
        val current = StringBuilder()
        for (ch in text) {
            if (ch.isLetterOrDigit()) {
                current.append(ch.lowercaseChar())
            } else if (current.isNotEmpty()) {
                tokens += current.toString()
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens.filter { it.length >= MIN_TOKEN_LENGTH }
    }

    /**
     * Ranks [candidates] (item -> document text) against [query], best first.
     *
     * Never throws on a degenerate query: an empty or all-stopword-ish query yields no hits, so
     * the caller answers "no passage matched" instead of crashing a tool call the model can still
     * act on. Zero-scoring candidates are dropped rather than returned as filler.
     */
    fun <T> rank(query: String, candidates: List<Pair<T, String>>, limit: Int): VectorSearchResult<T> {
        if (limit <= 0) return VectorSearchResult(hits = emptyList(), skipped = 0)
        val terms = tokenize(query).distinct()
        if (terms.isEmpty() || candidates.isEmpty()) return VectorSearchResult(emptyList(), 0)

        val documents = candidates.map { (_, text) -> tokenize(text) }
        val totalLength = documents.sumOf { it.size }
        if (totalLength == 0) return VectorSearchResult(emptyList(), 0)
        val avgLength = totalLength.toDouble() / documents.size

        // Document frequency per query term, one pass over the corpus.
        val documentFrequency = HashMap<String, Int>(terms.size)
        val wanted = terms.toHashSet()
        for (document in documents) {
            val present = HashSet<String>(terms.size)
            for (token in document) {
                if (token in wanted) present += token
            }
            for (term in present) documentFrequency[term] = (documentFrequency[term] ?: 0) + 1
        }

        val n = documents.size
        val scored = ArrayList<ScoredChunk<T>>(candidates.size)
        for ((index, candidate) in candidates.withIndex()) {
            val document = documents[index]
            if (document.isEmpty()) continue
            val frequency = HashMap<String, Int>()
            for (token in document) {
                if (token in wanted) frequency[token] = (frequency[token] ?: 0) + 1
            }
            var score = 0.0
            for (term in terms) {
                val tf = frequency[term] ?: continue
                val df = documentFrequency[term] ?: continue
                // Robertson-Sparck Jones idf, shifted by +1 so it is never negative.
                val idf = kotlin.math.ln(1.0 + (n - df + 0.5) / (df + 0.5))
                val tfNorm = tf * (K1 + 1.0) /
                    (tf + K1 * (1.0 - B + B * document.size / avgLength))
                score += idf * tfNorm
            }
            if (score > 0.0) scored += ScoredChunk(candidate.item, score.toFloat())
        }

        // sortByDescending is stable, so equal scores keep corpus order: a re-run of the same
        // query over the same notes returns the same list, which a model can rely on.
        scored.sortByDescending { it.score }
        if (scored.size > limit) scored.subList(limit, scored.size).clear()
        return VectorSearchResult(hits = scored, skipped = 0)
    }
}
