package me.rerere.rikkahub.data.vector

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One passage `memory_search` found, with the file it came from. */
data class ColdMemorySearchHit(
    val file: String,
    val chunkIndex: Int,
    val score: Float,
    val text: String,
)

/**
 * What `memory_search` returns for one call.
 *
 * [available] is false when nothing can be embedded - no model installed, or one that does not fit
 * this device - and that is a state the tool reports rather than throws: a fresh install has no
 * embedding model, and the model still has to be told what to do instead.
 */
data class ColdMemorySearchOutcome(
    val available: Boolean,
    val hits: List<ColdMemorySearchHit> = emptyList(),
    val indexedDocuments: Int = 0,
    /** Some passages were embedded by a different model: a rebuild is needed before trusting it. */
    val stale: Boolean = false,
    val note: String? = null,
    /**
     * [KeywordRecall.MARKER] when the hits came from keyword ranking because no
     * embedding model could be loaded (missing, or declined by the memory budget); null
     * for semantic hits. The two modes produce incomparable scores, so the mode travels
     * with the result rather than being inferred from them.
     */
    val recall: String? = null,
)

/**
 * The JSON `memory_search` answers with.
 *
 * Split out of the tool itself so it can be tested without the tool stack: this is where the
 * wording that steers the model lives - what to do when nothing is indexed, when the index is
 * stale, when semantic search is not set up at all - and a wording bug here is invisible until a
 * model does the wrong thing with it.
 */
object MemorySearchEnvelope {

    fun hits(dirLabel: String, query: String, outcome: ColdMemorySearchOutcome): String =
        buildJsonObject {
            put("dir", dirLabel)
            put("query", query)
            put("indexedDocuments", outcome.indexedDocuments)
            outcome.recall?.let { mode ->
                put("recall", mode)
                put(
                    "recallNote",
                    "No embedding model could be loaded, so these passages are keyword-ranked "
                        + "(BM25), not matched by meaning. Scores are not comparable with the "
                        + "cosine similarities of a semantic search, and a passage that shares no "
                        + "wording with the query cannot be found this way.",
                )
            }
            if (outcome.stale) {
                put("stale", true)
                put(
                    "staleNote",
                    "Some passages were embedded by a different model; a rebuild restores full accuracy.",
                )
            }
            put("hits", buildJsonArray {
                outcome.hits.forEach { hit ->
                    add(buildJsonObject {
                        put("file", hit.file)
                        put("chunkIndex", hit.chunkIndex)
                        put("score", hit.score)
                        put("text", hit.text.take(MAX_HIT_CHARS))
                        if (hit.text.length > MAX_HIT_CHARS) put("truncated", true)
                    })
                }
            })
            if (outcome.hits.isEmpty()) {
                put("note", emptyNote(outcome.indexedDocuments))
                if (outcome.note != null) put("detail", outcome.note!!)
            }
        }.toString()

    /**
     * Sent when neither engine could answer: no embedding model loaded AND nothing the
     * keyword fallback could read either (AURA-021 added that fallback, so this state is
     * rarer than it used to be - an empty or unreadable directory is now the only way in).
     * The hint stays an instruction: a model that merely learns search is off stops looking
     * for its notes entirely.
     */
    fun unavailable(detail: String?): String = buildJsonObject {
        put("error", "unavailable")
        put("detail", detail ?: "no embedding model is installed")
        put(
            "hint",
            "Neither semantic nor keyword search could run: no embedding model is loaded and " +
                "no readable Markdown document exists in this knowledge-base directory. Ask " +
                "the user to install an embedding model from the assistant's memory settings, " +
                "or list the notes with memory_index.",
        )
    }.toString()

    fun missingQuery(): String = buildJsonObject {
        put("error", "missing_argument")
        put("detail", "query is required")
    }.toString()

    private fun emptyNote(indexedDocuments: Int): String = if (indexedDocuments == 0) {
        "Nothing is indexed for this directory yet. Use memory_index and memory_read instead, or " +
            "ask the user to rebuild the search index."
    } else {
        "No passage matched. Try different words, or memory_index to see what exists."
    }
}

/** Passages one search returns unless the model asks for a different number. */
const val DEFAULT_SEARCH_LIMIT = 8

/** Upper bound on `limit`: a caller asking for a hundred passages is asking for a context blowup. */
const val MAX_SEARCH_LIMIT = 20

/** Characters of a passage returned to the model; the full text stays a `memory_read` away. */
const val MAX_HIT_CHARS = 800
