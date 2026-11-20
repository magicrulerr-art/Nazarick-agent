package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.Tool

/**
 * Contract LC-2 / roster §2.2 (AURA-021 v1.0): the last gate a tool passes on its way to the model.
 *
 * ## Why the group allowlist is not enough
 *
 * `Assistant.toolAllowlist` narrows the **local** face, and `ToolSurfaceResolver.resolve` is indeed
 * the only place the local face is built. But `ChatService` stacks three more families on top of it
 * - workspace, cold memory, skills, MCP - at two inline sites that never pass through that resolver
 * (`ChatService.kt:2170` for the turn, `:1544` for the rerun rebuild). A ceiling that stops at the
 * local face is therefore not a ceiling: one installed skill, one MCP server, and the surface is
 * wider than the reviewed roster again. That re-opens the z7/z8 defect class (one phone 15 tools,
 * the next 16) with a new cause, and it re-opens the token budget too, since every extra row is a
 * full schema in the prompt.
 *
 * So the roster is enforced where the finished list leaves the harness: [enforce] takes the tool
 * objects about to be handed to the provider - local, workspace, memory, skill and MCP alike - and
 * drops every one the roster does not name.
 *
 * ## Semantics (roster §2, unchanged)
 *
 * | value | meaning |
 * |---|---|
 * | `null` | no roster: every tool survives, byte-identical to the pre-contract behaviour |
 * | `emptyList()` | **no tools at all** (plus harness plumbing, see below): an empty ceiling is a choice, not "unset" |
 * | a list | exactly those tool names, nothing else - a ceiling, never a preference |
 *
 * Harness plumbing the model did not choose is exempt by name ([HARNESS_TOOL_NAMES]). The exemption
 * is roster §3 item 2, still awaiting a ruling; it is a name list rather than a predicate so it
 * cannot grow by accident, and a test asserts it stays two entries long.
 *
 * The filter runs AFTER skill/MCP append and BEFORE the request is built, so it also decides whether
 * `tool_search` / `tool_open` are offered at all: with a roster in force and the catalogue ids not
 * named, a guardian cannot go looking for tools it was not given.
 *
 * Pure JVM: only `Tool.name` is read, so the invariant is testable without Android.
 */
object ToolSurfaceGuard {

    /**
     * Tools the harness injects for its own bookkeeping; the assistant never picks them, so the
     * roster does not speak about them. Keep this list embarrassingly short - every entry is a hole
     * in the ceiling.
     */
    val HARNESS_TOOL_NAMES: Set<String> = setOf(
        "compact_context",
        "mission_report",
    )

    /**
     * The tools to send, given a finished surface [tools] and the assistant's [allowlist] of exact
     * tool names. Order is preserved, so a roster never reshuffles the prompt (a small model
     * anchors on position; a roster that reorders is a behaviour change on its own).
     */
    fun enforce(
        tools: List<Tool>,
        allowlist: Collection<String>?,
        exempt: Set<String> = HARNESS_TOOL_NAMES,
    ): List<Tool> {
        if (allowlist == null) return tools
        val allowed = allowlist.toHashSet()
        return tools.filter { it.name in allowed || it.name in exempt }
    }

    /** Names of [tools], sorted - the shape a test compares against a roster table. */
    fun namesOf(tools: List<Tool>): List<String> = tools.map { it.name }.sorted()

    /**
     * The assertion behind "assembly proves every outgoing tool is allowlist-approved": throws when
     * [tools] carries a name the roster does not grant. Used at debug assemblies where a silent drop
     * would hide a wiring bug instead of reporting it.
     */
    fun assertApproved(tools: List<Tool>, allowlist: Collection<String>?, exempt: Set<String> = HARNESS_TOOL_NAMES) {
        if (allowlist == null) return
        val allowed = allowlist.toHashSet()
        val strays = tools.map { it.name }.filter { it !in allowed && it !in exempt }
        if (strays.isNotEmpty()) {
            throw IllegalStateException("tools escaped the allowlist: $strays")
        }
    }
}
