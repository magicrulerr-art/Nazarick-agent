package me.rerere.rikkahub.data.guardians

/**
 * Contract LC-2 (AURA-021 v1.0): the ratified per-guardian tool roster, as ONE table.
 *
 * Source of truth: `floor\spec-assistant-tool-allowlist.md` §1 (Demiurge acceptance + Albedo
 * record, ruling D4). The point of hard-coding it here instead of deriving it is the defect it
 * kills: on z7 one assistant carried 15 tools and on z8 the same one carried 16, because the
 * surface was assembled from whatever the catalogue and the retrieval happened to offer. A small
 * model anchors on a stable surface and fumbles a moving one (AURA-013), so for the Nine the
 * surface is now a reviewed constant: at most eight tools, exact ids, and nothing added at runtime.
 *
 * Ids are `Tool.name` values verbatim, not display labels. `search_web` is the web-search id the
 * registry actually declares (`data/ai/tools/SearchTools.kt`); both reviewers spelled it
 * `web_search`, which would have produced a roster entry resolving to nothing while a precondition
 * on the string still passed. [KNOWN_TOOL_IDS] is the mechanical guard against exactly that class
 * of mistake: every granted id must appear in it, and it is a transcription of the registry
 * enumeration in the spec's Appendix A.
 *
 * Pure JVM, deliberately: [FloorGuardiansTest] asserts this table against the pack, and the
 * runtime filter that reads it ([me.rerere.rikkahub.data.ai.ToolSurfaceGuard]) is tested here too.
 */
object GuardianToolRoster {

    /** Row order matches [FloorGuardians] pack order so a diff reads like the spec. */
    val ROSTER: Map<String, List<String>> = linkedMapOf(
        "Demiurge" to listOf(
            "search_web", "scrape_web", "memory_search", "memory_read",
            "get_time_info", "ask_user", "mission_create", "mission_dispatch",
        ),
        "Albedo" to listOf(
            "search_web", "scrape_web", "web_fetch", "memory_search",
            "memory_write", "create_calendar_event", "schedule_job", "get_time_info",
        ),
        "Shalltear" to listOf(
            "scrape_web", "open_url", "launch_app", "send_sms",
            "schedule_job", "read_window_tree", "tap", "take_screenshot",
        ),
        "Cocytus" to listOf(
            "get_time_info", "schedule_job", "create_calendar_event", "memory_write",
            "memory_search", "get_battery_status", "get_location", "read_sensor",
        ),
        "Sebas" to listOf(
            "search_web", "get_storage_info", "get_battery_status", "list_files",
            "move_file", "delete_file", "list_recent_notifications", "schedule_job",
        ),
        "Victim" to listOf(
            "read_file", "list_files", "find_files", "write_text_file",
            "memory_read", "memory_write", "memory_search", "get_time_info",
        ),
        "Mare" to listOf(
            "search_web", "memory_write", "memory_read", "memory_search",
            "recent_chats", "write_text_file", "take_photo", "transcribe_audio_file",
        ),
        "Aura" to listOf(
            "search_web", "scrape_web", "termux_run_command", "ssh_exec",
            "workspace_shell", "workspace_read_file", "workspace_edit_file", "workspace_write_file",
        ),
        // Keyed exactly as the pack spells it, apostrophe included: a lookup that misspells this
        // falls back to "no roster" and the guardian quietly gets the whole catalogue back.
        "Pandora's Actor" to listOf(
            "text_to_speech", "ask_user", "clipboard_tool", "get_time_info",
            "memory_read", "show_image", "share", "memory_write",
        ),
    )

    /**
     * The ceiling for [name], or null when the name is not one of the Nine.
     *
     * null means "no roster" (the pre-contract behaviour), NOT "no tools" - an unknown or
     * user-renamed guardian must not end up unable to act.
     */
    fun toolIdsFor(name: String): List<String>? = ROSTER[name.trim()]

    /** Granted to the assistants whose remit includes answering live-fact questions (§1 coverage). */
    val LIVE_FACT_GRANTEES: Set<String> = setOf("Demiurge", "Albedo", "Sebas", "Mare", "Aura")

    /**
     * Transcription of the registry enumeration (spec Appendix A: 73 files, 183 distinct ids);
     * only the ids this roster can name are listed, which is all the typo-guard needs.
     */
    val KNOWN_TOOL_IDS: Set<String> = setOf(
        "search_web", "scrape_web", "web_fetch", "open_url",
        "memory_search", "memory_read", "memory_write", "memory_index",
        "get_time_info", "ask_user", "clipboard_tool", "text_to_speech",
        "mission_create", "mission_dispatch", "mission_report",
        "create_calendar_event", "schedule_job",
        "launch_app", "send_sms", "read_window_tree", "tap", "take_screenshot",
        "get_battery_status", "get_location", "read_sensor",
        "get_storage_info", "list_files", "find_files", "move_file", "delete_file",
        "list_recent_notifications", "read_file", "write_text_file",
        "recent_chats", "take_photo", "transcribe_audio_file",
        "termux_run_command", "ssh_exec",
        "workspace_shell", "workspace_read_file", "workspace_edit_file", "workspace_write_file",
        "show_image", "share", "use_skill",
    )

    /** Every id the roster grants, sorted - the shape a test asserts against [KNOWN_TOOL_IDS]. */
    fun grantedIds(): List<String> = ROSTER.values.flatten().distinct().sorted()
}
