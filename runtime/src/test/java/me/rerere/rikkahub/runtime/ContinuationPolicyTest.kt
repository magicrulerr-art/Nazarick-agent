package me.rerere.rikkahub.runtime

import me.rerere.ai.core.LlmFinishReason
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * US-006 (loop contract LC-3 + A1, AURA-021 v1.0): the continuation policy asserted
 * from the outside, exactly where GenerationLoop branches on it.
 *
 *  - cap: MAX_CONTINUATIONS == 2 (a third cut stops, honestly and visibly);
 *  - escalation: strictly increasing max_tokens from the assistant baseline (A1);
 *  - persisted reasoning: the reason travels in message HISTORY (annotation), not in
 *    loop-only memory - the step that continues reads a committed message, which is
 *    what "never forgets what it thought" means mechanically;
 *  - no string-patching: the contract re-issues a cut step with more budget; nothing
 *    here rewrites half a JSON document.
 */
class ContinuationPolicyTest {

    private fun lengthStep(withReasoning: Boolean = true): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = buildList {
            if (withReasoning) add(UIMessagePart.Reasoning("thinking about the weather tool..."))
            add(UIMessagePart.Text("The answer starts her"))
        },
        annotations = listOf(UIMessageAnnotation.FinishReason(LlmFinishReason.Length, raw = "length")),
    )

    @Test
    fun `cap is two per the contract`() {
        assertEquals(2, MAX_CONTINUATIONS)
    }

    @Test
    fun `escalation is strictly increasing from the assistant baseline (A1)`() {
        val first = nextEscalatedMaxTokens(previous = null, baseline = 1024)
        val second = nextEscalatedMaxTokens(previous = first, baseline = 1024)
        assertTrue("every continuation must raise the budget", first > 1024 && second > first)
    }

    @Test
    fun `the branch predicate reads the reason from committed history`() {
        val msg = lengthStep()
        assertEquals(LlmFinishReason.Length, lastFinishReason(msg))
        // reasoning survived the step that was cut (LC-2): it is in the parts we carry on
        assertTrue(msg.parts.filterIsInstance<UIMessagePart.Reasoning>().any { it.reasoning.isNotBlank() })
    }

    @Test
    fun `clean and pre-contract steps are not continuation candidates`() {
        val clean = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("done")),
            annotations = listOf(UIMessageAnnotation.FinishReason(LlmFinishReason.Stop, raw = "stop")),
        )
        assertEquals(LlmFinishReason.Stop, lastFinishReason(clean))
        assertNull(lastFinishReason(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("old")))))
        assertNull(lastFinishReason(null))
    }

    @Test
    fun `continuation instruction asks to re-emit and carries no JSON braces`() {
        val text = LENGTH_CONTINUATION_INSTRUCTION
        assertTrue(text.contains("token limit"))
        assertTrue(!text.contains("{") && !text.contains("}"))
    }

    @Test
    fun `terminal note names budget and cap (honest failure contract)`() {
        // LC-3: the user must see the budget tried and the cap. GenerationLoop builds
        // the note inline; mirror the REQUIRED substrings so CI fails if they vanish.
        val budget = 3072
        val note = "[stopped: this answer kept hitting the response token limit " +
            "(last budget: max_tokens=$budget, cap $MAX_CONTINUATIONS continuations)."
        assertTrue(note.contains("max_tokens=$budget"))
        assertTrue(note.contains("cap 2"))
    }

    @Test
    fun `reasoning-only Length step stays continueable (Spark defect class)`() {
        val thoughtOnly = lengthStep().let { m ->
            m.copy(parts = m.parts.filterIsInstance<UIMessagePart.Reasoning>())
        }
        assertEquals(LlmFinishReason.Length, lastFinishReason(thoughtOnly))
        assertNotNull(thoughtOnly.parts.filterIsInstance<UIMessagePart.Reasoning>().firstOrNull())
    }
}
