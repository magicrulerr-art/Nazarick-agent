package me.rerere.ai.core

import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessageAnnotation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LC-1 (AURA-021 v1.0): the typed finish reason is normalized at the boundary and
 * survives message persistence. Engine end-of-text spellings are a CLEAN stop — a
 * misclassification here is what turned truncated thoughts into "stalls" (the
 * 2026-11-12 forget-and-loop defect). Unknown is terminal, never an error.
 */
class LlmFinishReasonTest {

    @Test
    fun `provider dialects normalize to the typed enum`() {
        assertEquals(LlmFinishReason.Stop, LlmFinishReason.normalize("stop"))
        assertEquals(LlmFinishReason.Stop, LlmFinishReason.normalize(" STOP_SEQUENCE "))
        assertEquals(LlmFinishReason.Stop, LlmFinishReason.normalize("end_turn"))
        // engine end-of-text variants -> clean stop (LC-1 explicit requirement)
        assertEquals(LlmFinishReason.Stop, LlmFinishReason.normalize("eos"))
        assertEquals(LlmFinishReason.Stop, LlmFinishReason.normalize("end_of_text"))
        assertEquals(LlmFinishReason.Stop, LlmFinishReason.normalize("
"))
        assertEquals(LlmFinishReason.Length, LlmFinishReason.normalize("length"))
        assertEquals(LlmFinishReason.Length, LlmFinishReason.normalize("max_tokens"))
        assertEquals(LlmFinishReason.ToolCalls, LlmFinishReason.normalize("tool_calls"))
        assertEquals(LlmFinishReason.ToolCalls, LlmFinishReason.normalize("function_call"))
        assertEquals(LlmFinishReason.Error, LlmFinishReason.normalize("error"))
        assertEquals(LlmFinishReason.Cancelled, LlmFinishReason.normalize("cancelled"))
        assertEquals(LlmFinishReason.Cancelled, LlmFinishReason.normalize("user_stopped"))
        assertEquals(LlmFinishReason.Unknown, LlmFinishReason.normalize(null))
        assertEquals(LlmFinishReason.Unknown, LlmFinishReason.normalize("something_new"))
    }

    @Test
    fun `llama classification measures the cap instead of guessing`() {
        // budget remaining + stop word -> clean stop even with almost no output
        assertEquals(
            LlmFinishReason.Stop,
            classifyLlamaFinish(stopWordReached = true, decodedTokens = 3, maxTokens = 1024, emittedToolCalls = 0),
        )
        // budget fully consumed without a stop word -> Length (continueable)
        assertEquals(
            LlmFinishReason.Length,
            classifyLlamaFinish(stopWordReached = false, decodedTokens = 1024, maxTokens = 1024, emittedToolCalls = 0),
        )
        // a stop word exactly AT the cap is still a clean stop (never a false Length)
        assertEquals(
            LlmFinishReason.Stop,
            classifyLlamaFinish(stopWordReached = true, decodedTokens = 1024, maxTokens = 1024, emittedToolCalls = 0),
        )
        // tool calls win over both
        assertEquals(
            LlmFinishReason.ToolCalls,
            classifyLlamaFinish(stopWordReached = false, decodedTokens = 1024, maxTokens = 1024, emittedToolCalls = 1),
        )
        // no budget configured (server default) -> never claim Length
        assertEquals(
            LlmFinishReason.Stop,
            classifyLlamaFinish(stopWordReached = false, decodedTokens = 9999, maxTokens = null, emittedToolCalls = 0),
        )
    }

    @Test
    fun `finish reason annotation round-trips through message json`() {
        val json = Json { ignoreUnknownKeys = true }
        val note = UIMessageAnnotation.FinishReason(LlmFinishReason.Length, raw = "length")
        val encoded = json.encodeToString(UIMessageAnnotation.serializer(), note)
        val decoded = json.decodeFromString(UIMessageAnnotation.serializer(), encoded)
        assertEquals(note, decoded)
    }
}
