package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * US-010: three turns including a duplicate must flush in the order they were spoken, once each.
 */
class TombQueueTest {

    @Test
    fun drainPreservesInsertionOrderAndDropsDuplicateId() {
        val queue = TombQueue()
        queue.enqueue(TombTurn(id = "t1", text = "first"))
        queue.enqueue(TombTurn(id = "t2", text = "second"))
        queue.enqueue(TombTurn(id = "t1", text = "first again"))

        val flushed = queue.drain()

        assertEquals(2, flushed.size)
        assertEquals(listOf("t1", "t2"), flushed.map { it.id })
        assertEquals(listOf("first", "second"), flushed.map { it.text })
        assertTrue(queue.isEmpty())
    }

    @Test
    fun enqueueAfterDrainStartsAFreshOrder() {
        val queue = TombQueue()
        queue.enqueue(TombTurn(id = "a", text = "a"))
        queue.enqueue(TombTurn(id = "b", text = "b"))
        assertEquals(listOf("a", "b"), queue.drain().map { it.id })

        queue.enqueue(TombTurn(id = "b", text = "b replayed"))
        queue.enqueue(TombTurn(id = "a", text = "a replayed"))
        assertEquals(listOf("b", "a"), queue.drain().map { it.id })
    }

    @Test
    fun drainOnEmptyQueueReturnsNothing() {
        val queue = TombQueue()
        assertEquals(0, queue.size)
        assertTrue(queue.drain().isEmpty())
    }
}
