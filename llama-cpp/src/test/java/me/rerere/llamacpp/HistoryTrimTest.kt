package me.rerere.llamacpp

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTrimTest {

    private fun conversation(turns: Int) = (1..turns).map { UIMessage.user("message number $it") }

    @Test
    fun `a conversation inside the budget is untouched`() {
        val messages = conversation(3)
        assertEquals(messages, ChatRequestMapper.trimToBudget(messages, budgetBytes = 100_000))
    }

    @Test
    fun `oldest turns are dropped first when the budget is exceeded`() {
        val messages = conversation(50)
        val trimmed = ChatRequestMapper.trimToBudget(messages, budgetBytes = 200)
        assertTrue("must drop something", trimmed.size < messages.size)
        assertTrue("the newest turn must survive", trimmed.last() == messages.last())
        assertTrue("the oldest turn must be gone", !trimmed.contains(messages.first()))
    }

    @Test
    fun `the newest turn is never dropped even if it alone exceeds the budget`() {
        // Dropping it would send an empty conversation, which is worse than a
        // prompt that overflows and produces a readable error.
        val messages = conversation(5)
        val trimmed = ChatRequestMapper.trimToBudget(messages, budgetBytes = 1)
        assertEquals(1, trimmed.size)
        assertEquals(messages.last(), trimmed.first())
    }

    @Test
    fun `a system message must not push out the only user turn`() {
        // Regression: the old loop stopped at working.size > 1, so [system, user]
        // over budget dropped the user turn (the only droppable message) and left
        // just the system message. conversation(5) above never caught this because
        // it has no system message, so the survivor happened to be the newest turn
        // by coincidence, not because the system-present case was exercised.
        val messages = listOf(
            UIMessage.system("s".repeat(10_000)),
            UIMessage.user("hi"),
        )
        val trimmed = ChatRequestMapper.trimToBudget(messages, budgetBytes = 50)
        assertTrue(
            "a user-role message must survive trimming",
            trimmed.any { it.role == MessageRole.USER },
        )
        // Nothing is droppable but the persona itself, so the turn count holds and the
        // oversized system text is what shrinks.
        assertEquals("no turn may be dropped here", messages.size, trimmed.size)
        assertTrue(
            "a persona bigger than the budget must shrink rather than overflow the context",
            systemTextOf(trimmed.first()).length < 10_000,
        )
    }

    @Test
    fun `the newest turn survives when older turns sit behind a system message`() {
        val messages = listOf(
            UIMessage.system("s".repeat(10_000)),
            UIMessage.user("first"),
            UIMessage.assistant("second"),
            UIMessage.user("third"),
        )
        val trimmed = ChatRequestMapper.trimToBudget(messages, budgetBytes = 50)
        assertTrue(
            "a user-role message must survive trimming",
            trimmed.any { it.role == MessageRole.USER },
        )
        assertEquals("the system message must stay first", MessageRole.SYSTEM, trimmed.first().role)
        assertEquals(messages.last(), trimmed.last())
    }

    @Test
    fun `a persona that alone exceeds the budget is cut to the floor, head first`() {
        // Measured in the field: the bundled agent-core skill contributes ~31 KB of system
        // text, which against an 8,192-token llama.cpp context is more than the whole input
        // budget. It used to be sent whole, so the first token needed a ten-minute prefill
        // and the watchdog killed every attempt.
        val persona = "persona prose ".repeat(3_000) // ~36 KB
        val trimmed = ChatRequestMapper.trimToBudget(
            listOf(UIMessage.system(persona), UIMessage.user("hi")),
            budgetBytes = 0,
        )

        val text = systemTextOf(trimmed.first())
        val kept = text.substringBefore("\n…")
        assertTrue("the persona must be cut", kept.length < persona.length)
        assertTrue("the head of the persona must survive", persona.startsWith(kept))
        assertTrue("the model must be told the persona was cut", text.contains("truncated"))
        assertTrue("a persona must not vanish entirely", text.length > 1_000)
    }

    @Test
    fun `truncating a persona never splits a multi-byte character`() {
        // A cut landing between the two bytes of é emits U+FFFD at the seam.
        val persona = "x" + "é".repeat(2_000) // 1 byte + 2 bytes each
        val trimmed = ChatRequestMapper.trimToBudget(
            listOf(UIMessage.system(persona), UIMessage.user("hi")),
            budgetBytes = 0,
        )

        val kept = systemTextOf(trimmed.first()).substringBefore("\n…")
        assertFalse(
            "replacement character in ${kept.takeLast(4)}",
            kept.contains('\uFFFD'),
        )
        assertTrue("the head must be a real prefix of the persona", persona.startsWith(kept))
    }

    private fun systemTextOf(message: UIMessage): String =
        message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
}
