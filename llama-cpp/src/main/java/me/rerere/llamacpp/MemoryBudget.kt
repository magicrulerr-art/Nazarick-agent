package me.rerere.llamacpp

/**
 * Global native-memory admission ledger (AURA-021 v1.0, loop-contract LC-6/LC-7).
 *
 * Every native model — the chat context and the embedding engine — lives in its own
 * [LlamaCppRuntime] / [LlamaCppEmbedder] instance and pre-checks its own load against
 * `MemoryGuard`. Individually correct, jointly naive: two instances each pass their
 * solo guard while together they double-book free RAM, and the process dies mid-load
 * (the Spark crash, z7 2026-11-12: a chat turn and an index build racing 3.8 GB of
 * weights into a 12 GB phone). The OS kill is the failure this ledger removes: a load
 * is a *decision* made against the bytes every other native holder has reserved, so
 * the second model is declined instead of the whole app being killed.
 *
 * Admission control, not a size guess (Demiurge's phrasing): reservations are made
 * before the native load and released when the handles go free; a declined caller
 * gets numbers, and only the caller chooses whether that is an error or a degradation.
 *
 * Locking is plain [synchronized] on purpose: the map operations are nanoseconds and
 * callers already hold their own serializing mutex; a suspend lock here would put a
 * suspension point inside load paths that run cleanup in `finally`.
 */
class MemoryBudget {

    sealed class Result {
        /** The reservation was recorded; roomAfterBytes is what remains for others. */
        data class Granted(val roomAfterBytes: Long) : Result()

        /** The reservation was refused and *nothing was recorded*. requiredBytes is
         *  what the caller asked for; roomBytes is what the ledger could have offered
         *  (free bytes minus what the other holders reserve). */
        data class Declined(val requiredBytes: Long, val roomBytes: Long) : Result()
    }

    private val lock = Any()
    private val holds = LinkedHashMap<String, Long>()

    /**
     * Reserves [bytes] for [id] against an [availableBytes] reading taken by the
     * caller at this moment. Re-reserving an existing [id] REPLACES its entry — the
     * model reloads at a different size — and an id never competes with itself.
     */
    fun tryReserve(id: String, bytes: Long, availableBytes: Long): Result = synchronized(lock) {
        val others = holds.filterKeys { it != id }.values.sum()
        val room = availableBytes - others
        if (bytes <= room) {
            holds[id] = bytes
            Result.Granted(room - bytes)
        } else {
            Result.Declined(requiredBytes = bytes, roomBytes = room)
        }
    }

    /** Removes the reservation for [id], if any. Idempotent — release races unload. */
    fun release(id: String) {
        synchronized(lock) { holds.remove(id) }
    }

    /** Snapshot of who currently holds what, for decline messages and tests. */
    fun outstanding(): Map<String, Long> = synchronized(lock) { holds.toMap() }
}

/**
 * The one ledger every native model in the process goes through. A process singleton
 * because native memory is: the chat runtime and the embedder are each unique in the
 * app graph, and the JVM tests reuse the object to set up contention on purpose.
 */
object NativeAdmission {
    val budget = MemoryBudget()

    /** Load declined because the global budget could not seat the model — distinct
     *  from [ModelTooLargeException] (this model vs this device) in that *something
     *  else* is holding the room; callers may treat both as degradation. */
    class DeclinedByBudgetException(message: String) : RuntimeException(message)

    const val CHAT_ID = "chat"
    const val EMBEDDING_ID = "embedding"
}
