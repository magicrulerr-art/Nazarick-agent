package me.rerere.ai.core

/**
 * WHY a model step stopped, typed (AURA-021 v1.0, loop-contract LC-1).
 *
 * The wire says this in three dialects — OpenAI-style finish_reason strings, raw
 * engine stop-words, and sometimes nothing at all — and the loop previously never saw
 * any of it: a stream that hit the token cap looked exactly like a clean stop, so a
 * truncated thought was filed as a stall and the reasoning behind it was re-asked for
 * (owner report 2026-11-12: "agent forgets what it thought and loops"). One enum,
 * normalized at the provider boundary, is the contract the loop branches on:
 *
 *  - [Stop]      model finished on its own terms (engine end-of-text variants count);
 *  - [Length]    response-token cap cut the generation — a budget event, continueable;
 *  - [ToolCalls] generation paused for tool calls it emitted;
 *  - [Error]     the provider stream itself failed;
 *  - [Cancelled] the user (or the app) stopped the run;
 *  - [Unknown]   the provider never said and never hit a cap — pre-contract payloads
 *                and dialects we do not map yet. Unknown is a legitimate terminal
 *                value, never an error.
 */
enum class LlmFinishReason {
    Stop, Length, ToolCalls, Error, Cancelled, Unknown;

    companion object {
        /**
         * Normalizes provider dialects to the enum. Lower-cased and trimmed; unknown
         * strings map to [Unknown] rather than throwing — a new provider phrasing must
         * not crash message rendering for a classification field.
         */
        fun normalize(raw: String?): LlmFinishReason = when (raw?.trim()?.lowercase()) {
            null -> Unknown
            "stop", "stop_sequence", "end_turn", "stopend", "complete", "completed" -> Stop
            // engine end-of-text spellings: a stop-word reached is a clean finish (LC-1)
            "eos", "end_of_text", "endoftext", "im_end", "endoftexttoken", "<|endoftext|>" -> Stop
            "length", "max_tokens", "maxtokens", "incomplete", "context_length" -> Length
            "tool_calls", "tool_use", "function_call", "toolcall" -> ToolCalls
            "error", "failed", "content_filter" -> Error
            "cancelled", "canceled", "abort", "aborted", "user_stopped" -> Cancelled
            else -> Unknown
        }
    }
}

/**
 * Llama-specific classification (LC-1): llama.cpp's C API reports stop words, not
 * reasons, so the provider classifies at the emission point. The cap decision is a
 * MEASUREMENT, not a guess: the requested token budget vs tokens actually decoded —
 * if the model stopped on a stop-word there is budget left and this is
 * [LlmFinishReason.Stop] (`
`-style stop sequences must never be misread as a cut).
 * Tool calls win over Stop: a pause-for-tools with budget remaining is action, not
 * conclusion.
 */
fun classifyLlamaFinish(
    stopWordReached: Boolean,
    decodedTokens: Int,
    maxTokens: Int?,
    emittedToolCalls: Int,
): LlmFinishReason = when {
    emittedToolCalls > 0 -> LlmFinishReason.ToolCalls
    maxTokens != null && maxTokens > 0 && !stopWordReached && decodedTokens >= maxTokens -> LlmFinishReason.Length
    else -> LlmFinishReason.Stop
}
