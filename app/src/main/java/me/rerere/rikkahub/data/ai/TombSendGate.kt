package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.BrainPolicy
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode

/** Shown in the conversation when a turn is held by [TombSendGate] instead of being answered. */
const val TOMB_QUEUED_NOTICE: String = "queued for tomb: sent on reconnect"

/**
 * The send-path half of the offline Tomb story (Companion-Edge V2 US-010 AC2): a Tomb-bound turn
 * spoken while the Tomb is unreachable is enqueued rather than failed, and flushed automatically
 * once the Tomb answers again - order preserved, one entry per turn id.
 *
 * The gate is deliberately pure and synchronous: the caller hands it the probe answer
 * (`Settings.tombReachableProbed`) and the assistant's already-resolved policy, so the ordering
 * contract proves out in a plain unit test with no network, no Android and no coroutine.
 *
 * Caller contract: consult [onSubmit] only for turns [AutoRouter] resolved to [Decision.TOMB].
 * An [BrainPolicy.AUTO] turn that offline routing already dropped to the local chain
 * ([Decision.EDGE_OFFLINE_NOTICE]) is answered locally and must never be held here - which is why
 * this gate takes no `hasLocalFallback` argument of its own and only refuses [BrainPolicy.EDGE]
 * outright.
 */
object TombSendGate {

    /** What the send path does next with the turn it just offered. */
    enum class Gate { PASSED_THROUGH, QUEUED }

    private val queue = TombQueue()
    private val lock = Any()

    /**
     * Holds [turnText] under [turnId] when a tomb-bound turn cannot be delivered, and reports
     * [Gate.QUEUED]; anything the Tomb can take right now (and every [BrainPolicy.EDGE] turn)
     * returns [Gate.PASSED_THROUGH] untouched. Re-offering an id already pending - a retry after a
     * lost response - stays [Gate.QUEUED] and does not duplicate the entry.
     */
    fun onSubmit(
        policy: BrainPolicy,
        tombReachable: Boolean,
        turnText: String,
        turnId: String,
    ): Gate {
        if (tombReachable) return Gate.PASSED_THROUGH
        if (policy == BrainPolicy.EDGE) return Gate.PASSED_THROUGH
        synchronized(lock) { queue.enqueue(TombTurn(id = turnId, text = turnText)) }
        return Gate.QUEUED
    }

    /**
     * Hands back the backlog oldest-first when the probe says the Tomb is reachable, and nothing at
     * all while it is not - so a caller can call this unconditionally at the top of a send. Reconnect
     * is detected here, probe-at-next-send, rather than by a connectivity listener: there is no
     * background pump until US-012 owns one.
     */
    fun drainOnReconnect(tombReachable: Boolean): List<TombTurn> =
        if (tombReachable) synchronized(lock) { queue.drain() } else emptyList()

    /** Drops every pending turn - test isolation, and the hook a signed-out session will need. */
    fun clear() {
        synchronized(lock) { queue.drain() }
    }
}

/** The conversation as shown after a tomb-bound turn was accepted but held: the turn, then the notice. */
internal fun Conversation.withTombQueuedTurn(parts: List<UIMessagePart>): Conversation = copy(
    messageNodes = messageNodes +
            UIMessage(role = MessageRole.USER, parts = parts).toMessageNode() +
            UIMessage(
                role = MessageRole.SYSTEM,
                parts = listOf(UIMessagePart.Text(TOMB_QUEUED_NOTICE)),
                isSynthetic = true,
            ).toMessageNode(),
)

/** The conversation with a flushed [text] turn appended, ready for the provider call that replays it. */
internal fun Conversation.withReplayedTombTurn(text: String): Conversation = copy(
    messageNodes = messageNodes + UIMessage.user(text).toMessageNode(),
)
