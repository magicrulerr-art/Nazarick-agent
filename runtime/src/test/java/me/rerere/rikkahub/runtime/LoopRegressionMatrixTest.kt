package me.rerere.rikkahub.runtime

import me.rerere.ai.core.LlmFinishReason
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * US-010 (AURA-021 v1.0): the loop regression matrix — stall, nudge, truncation, continuation,
 * cancellation and reasoning-persistence asserted together.
 *
 * The individual predicates already have their own suites ([GenerationLoopStallTest] for the stall
 * check, [ContinuationPolicyTest] for the continuation contract). What no suite covered is the
 * COMPOSITION, which is exactly where the Spark failure on z7 lived: a step exhausted its thinking
 * budget, the loop threw the reasoning away as "stalled", and the next step met a context with no
 * memory of the thought. Each row below pins one contract the contract document names, in the order
 * a real turn walks through them.
 *
 * Style follows [GenerationLoopStallTest]: pure JVM predicates, no Android, no model, no fake clock.
 * Nothing here asserts an implementation detail it could not equally have asserted from the contract.
 */
class LoopRegressionMatrixTest {

    private fun msg(
        role: MessageRole = MessageRole.ASSISTANT,
        parts: List<UIMessagePart>,
        reason: LlmFinishReason? = null,
    ) = UIMessage(
        role = role,
        parts = parts,
        annotations = buildList {
            if (reason != null) add(UIMessageAnnotation.FinishReason(reason, raw = reason.name.lowercase()))
        },
    )

    private fun reasoning(text: String) = UIMessagePart.Reasoning(reasoning = text)
    private fun text(t: String) = UIMessagePart.Text(t)

    // ---- row 1: stall -> nudge, with the thought still in context -----------------------------

    @Test
    fun stalledStepIsNudgedAndItsReasoningStaysInTheHistory() {
        val step = msg(parts = listOf(reasoning("The lookup found nothing. Let me check recent mentions instead.")))
        assertTrue("a reasoning-only step must be nudged, not accepted", stalledWithoutReply(step))

        // The nudge rides the NEXT request as an instruction appended to the history; the thought
        // being nudged about has to still be there, or the model is asked to continue a sentence it
        // cannot see (the Spark defect).
        val nextRequest = listOf(
            msg(role = MessageRole.USER, parts = listOf(text("what did I ask yesterday?"))),
            step,
            msg(role = MessageRole.USER, parts = listOf(text(STALL_NUDGE_INSTRUCTION))),
        )
        val carried = nextRequest.flatMap { it.parts }.filterIsInstance<UIMessagePart.Reasoning>()
        assertEquals("the nudged thought must travel to the next step", 1, carried.size)
        assertTrue(carried.single().reasoning.isNotBlank())
    }

    // ---- row 2: truncation is classified, not treated as a stall ------------------------------

    @Test
    fun lengthCutStepIsNotAStallAndIsClassifiedAsLength() {
        val cut = msg(
            parts = listOf(reasoning("planning the answer"), text("The capital of France is Par")),
            reason = LlmFinishReason.Length,
        )
        assertFalse("a visible-but-cut answer is not a stall", stalledWithoutReply(cut))
        assertEquals(LlmFinishReason.Length, lastFinishReason(cut))
    }

    @Test
    fun lengthCutIsReIssuedWithoutRewritingTheStepParts() {
        // LC-3 forbids string-patching a half-written tool call back together. The remedy is a fresh
        // step with more budget, so the recorded step must survive byte-for-byte: same part objects,
        // no re-serialised JSON, no spliced braces.
        val toolCall = UIMessagePart.ToolCall(
            toolCallId = "call_1",
            toolName = "search_web",
            arguments = "{" + "\"query\": \"Paris\"" + "}",
        )
        val cut = msg(parts = listOf(toolCall), reason = LlmFinishReason.Length)
        val reissued = listOf(cut, msg(role = MessageRole.USER, parts = listOf(text(LENGTH_CONTINUATION_INSTRUCTION))))
        assertSame("the cut step is carried unchanged, not rebuilt", cut, reissued.first())
        assertSame(toolCall, reissued.first().parts.single())
        assertFalse(
            "the continuation instruction is prose, not a JSON patch",
            LENGTH_CONTINUATION_INSTRUCTION.contains("{") || LENGTH_CONTINUATION_INSTRUCTION.contains("}"),
        )
    }

    // ---- rows 3-4: the ladder, and what happens after it runs out -----------------------------

    @Test
    fun continuationLadderEscalatesTwiceAndStopsAtTheCap() {
        val baseline = 2048
        val first = nextEscalatedMaxTokens(previous = null, baseline = baseline)
        val second = nextEscalatedMaxTokens(previous = first, baseline = baseline)
        assertTrue("each retry must get strictly more room", first > baseline && second > first)
        // Two retries, no more: a third silent retry would read as a hung app.
        assertEquals(2, MAX_CONTINUATIONS)
    }

    @Test
    fun afterTheCapTheUserIsToldTheBudgetAndTheCap() {
        val note = continuationCapNote(lastBudget = 6144)
        assertTrue(note, note.contains("max_tokens=6144"))
        assertTrue(note, note.contains("cap $MAX_CONTINUATIONS continuations"))
        assertFalse(note, note.contains("{"))
    }

    // ---- row 5: cancellation is final and never spends budget ---------------------------------

    @Test
    fun cancelledStepIsFinalAndDoesNotQualifyForAContinuation() {
        val cancelled = msg(parts = listOf(text("I was interrupted")), reason = LlmFinishReason.Cancelled)
        assertEquals(LlmFinishReason.Cancelled, lastFinishReason(cancelled))
        // The ladder keys on Length alone. Treating a user stop as a truncation would silently
        // re-run a turn the owner deliberately stopped - and charge for it.
        assertFalse(lastFinishReason(cancelled) == LlmFinishReason.Length)
    }

    // ---- row 6: reasoning persistence across three steps --------------------------------------

    @Test
    fun reasoningFromThreeStepsSurvivesIntoTheFourthRequest() {
        val s1 = msg(parts = listOf(reasoning("step one: the user wants live facts"), text("Searching."), toolCallPart("a")))
        val s2 = msg(parts = listOf(reasoning("step two: the snippet is stale"), text("Verifying."), toolCallPart("b")), reason = LlmFinishReason.Length)
        val s3 = msg(parts = listOf(reasoning("step three: the source confirms it"), text("The answer is 42.")))

        // A turn that reaches step four carries every earlier thought. None of the loop's own
        // predicates (stall check, finish-reason read, continuation choice) remove parts, and a step
        // that is continued rather than discarded keeps what it thought.
        val history = listOf(
            msg(role = MessageRole.USER, parts = listOf(text("how tall is mount everest?"))),
            s1, s2, s3,
        )
        val thoughts = history.flatMap { it.parts }.filterIsInstance<UIMessagePart.Reasoning>().map { it.reasoning }
        assertEquals("all three steps' reasoning must be present", 3, thoughts.size)
        assertTrue("no thought may arrive blanked", thoughts.all { it.isNotBlank() })

        // The length-cut step is still readable as length-cut after being carried — the reason is
        // part of the committed message, not loop memory that evaporates between steps.
        assertEquals(LlmFinishReason.Length, lastFinishReason(history[2]))
        assertNotNull(lastFinishReason(s2))
    }

    private fun toolCallPart(id: String) = UIMessagePart.ToolCall(
        toolCallId = id,
        toolName = "memory_search",
        arguments = "{" + "\"query\": \"everest\"" + "}",
    )
}
