package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.LlmFinishReason

@Serializable
sealed class UIMessageAnnotation {
    @Serializable
    @SerialName("url_citation")
    data class UrlCitation(
        val title: String,
        val url: String
    ) : UIMessageAnnotation()

    /**
     * Why this assistant message's generation ended (AURA-021, LC-1/LC-2), stamped by
     * the stream handler when the provider's Finish chunk arrives and forwarded into
     * history with the message. Persisted reasoning depends on it: the loop's
     * continuation branch (LC-3) reads it back from history to tell a cut-off thought
     * ([LlmFinishReason.Length]) from a clean one, so it must travel with the message
     * rather than live only in the moment it was streamed. [raw] keeps the provider's
     * own string for post-mortems; absent on pre-contract messages.
     */
    @Serializable
    @SerialName("finish_reason")
    data class FinishReason(
        val reason: LlmFinishReason,
        val raw: String? = null,
    ) : UIMessageAnnotation()
}
