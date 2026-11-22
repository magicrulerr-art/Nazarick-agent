package me.rerere.rikkahub.runtime

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

/**
 * LoopTypes — the value objects that decouple the generation loop (:runtime) from the
 * app's data layer (:app).
 *
 * P4 of the harness re-architecture: GenerationLoop moves into :runtime, an
 * Android-free module. The loop must not reference :app's `Settings` / `Assistant`
 * aggregates (their type graphs pull in DataStore, Room and UI concerns), so the
 * caller projects exactly what the loop reads into these two types.
 *
 * The field lists are not abstractions invented here — they are the precise set of
 * fields GenerationLoop was observed to read (P4 recon, 2026-10-18): 4 Settings fields
 * (one flattened from NetworkSetting into the two watchdog ints) and 16 Assistant
 * fields. If the loop later needs more, add the field HERE (compile error forces the
 * projection in :app to stay honest).
 */

/** The 5 settings fields the loop reads, flattened. Projected from :app's Settings by
 *  the caller (see ChatService). */
data class LoopSettings(
    /** Provider list the loop resolves `model.findProvider` against. */
    val providers: List<ProviderSetting>,
    /** Stream retry budget per model request. */
    val responseStreamMaxRetries: Int = 5,
    /** Whether to parse textual tool calls from the model output. */
    val parseTextToolCalls: Boolean = true,
    /** Stream watchdog: max seconds to wait for the FIRST chunk. */
    val streamFirstOutputTimeoutSeconds: Int = 120,
    /** Stream watchdog: max seconds of silence between chunks. */
    val streamStallTimeoutSeconds: Int = 120,
) {
    companion object {
        /** Hard ceiling for the stream watchdog, mirrors NetworkSetting.MAX_WATCHDOG_SECONDS
         *  (:app's PreferencesStore) — the module boundary means the mirror is manual, so the
         *  two MUST move together: run 44 proved a UI-side raise alone is silently coerced
         *  back to this number, killing a 10.6k-token local prefill at exactly 600 s.
         *  30 min: an in-process llama.cpp stream has no socket; slowest observed Fold prefill
         *  is ~1,100 s, and a phone-CPU turn that the ceiling aborts is a turn that never ends. */
        const val MAX_WATCHDOG_SECONDS = 1800
    }
}

/** The 16 assistant fields the loop reads, projected from :app's Assistant. */
data class LoopAssistant(
    val id: Uuid,
    val systemPrompt: String = "",
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val streamOutput: Boolean = true,
    val contextMessageLimit: Int = 0,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val enableMemory: Boolean = false,
    val useGlobalMemory: Boolean = false,
    val enableRecentChatsReference: Boolean = false,
    val enableToolExecutionRetry: Boolean = false,
    val allowConversationSystemPrompt: Boolean = false,
    val toolResultMaxTokens: Int? = null,
)

/** One memory entry as the loop's memory prompt needs it. Projected from
 *  :app's AssistantMemory (whose id is an Int). */
data class LoopMemory(
    val id: Int,
    val content: String,
)

/**
 * One durable fact for the prompt, projected from :app's `memory_fact` row.
 *
 * Short field names on purpose: this block is re-sent on every turn, and
 * `subject`/`predicate`/`objectValue` as JSON keys would cost more than the values.
 */
data class LoopFact(
    val subject: String,
    val predicate: String,
    val objectValue: String,
)

/** One recent-conversation entry for the "Recent Chats" prompt block. The date is
 *  pre-formatted by :app (its util layer owns the localisation). */
data class RecentChatEntry(
    val title: String,
    val lastChatDate: String,
)

/**
 * A durable breakpoint the loop emits at each step boundary (P5).
 *
 * :app persists it (run_checkpoints) outside the loop, keyed by run id, so a process
 * killed mid-run can be resumed from the same step — or, when resuming is unsafe, the
 * user can be told exactly where it stopped. The loop itself keeps no memory of it.
 */
data class LoopCheckpoint(
    val stepIndex: Int,
    /** [PHASE_GENERATING] while waiting on the model; [PHASE_EXECUTING_TOOL] when the
     *  next action is running the tools already listed in [pendingToolCallIds]. */
    val phase: String,
    /** Size of the request-visible transcript at this boundary (recovery sanity check). */
    val messageCount: Int,
    /** Tool calls that were approved/ready but not yet executed at this boundary. */
    val pendingToolCallIds: List<String>,
) {
    companion object {
        const val PHASE_GENERATING = "GENERATING"
        const val PHASE_EXECUTING_TOOL = "EXECUTING_TOOL"
    }
}
