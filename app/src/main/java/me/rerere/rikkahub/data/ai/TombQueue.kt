package me.rerere.rikkahub.data.ai

/**
 * One turn waiting for the Tomb (Companion-Edge V2 US-010). [id] is the dedup key: the send path
 * can retry an enqueue after the response was lost, and a turn must never reach the Tomb twice.
 */
data class TombTurn(val id: String, val text: String)

/**
 * FIFO holding pen for turns that were meant for an unreachable Tomb. Pure Kotlin, no Android
 * deps, no coroutine, no clock - US-010 only needs the ordering contract, and the persistence and
 * replay wiring arrive with the real probe in US-012.
 *
 * Order is insertion order and survives dedup: re-enqueuing an id already pending is a no-op that
 * leaves the original entry where it stands, so a retried turn never jumps the queue.
 */
class TombQueue {

    private val pending = LinkedHashMap<String, TombTurn>()

    val size: Int get() = pending.size

    fun isEmpty(): Boolean = pending.isEmpty()

    /** Adds [turn] to the back of the queue; a turn whose id is already pending is dropped. */
    fun enqueue(turn: TombTurn) {
        if (pending.containsKey(turn.id)) return
        pending[turn.id] = turn
    }

    /** Hands out everything pending, oldest first, and leaves the queue empty. */
    fun drain(): List<TombTurn> {
        if (pending.isEmpty()) return emptyList()
        val flushed = ArrayList<TombTurn>(pending.size)
        flushed.addAll(pending.values)
        pending.clear()
        return flushed
    }
}
