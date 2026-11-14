package me.rerere.rikkahub.data.guardians

import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * US-008 (criterion a) — the assembled tool surface of each of the Nine equals the ratified roster,
 * proved through the field the seed writes and the gate the harness calls.
 *
 * This is the assertion the acceptance demands: not "the filter function works" (ToolSurfaceGuardTest
 * covers that) but "a guardian on a real device cannot exceed its row". So the surface fed to the
 * gate is built the way the harness builds one - local groups, search, missions, calendar, UI
 * automation, sensors, storage, files, workspace, skills, MCP, then the catalogue's discovery trio -
 * and it deliberately contains MORE than any guardian may have, including the two rows a device
 * adds by itself: an installed skill and an MCP tool. Those are the exact rows that made one phone
 * offer 15 tools and another 16.
 */
class GuardianRosterAssemblyTest {

    private fun fake(name: String): Tool = Tool(
        name = name,
        description = "fake $name",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }, required = null) },
        execute = { listOf(UIMessagePart.Text("ok")) },
    )

    /**
     * A generous but realistic assembly, in assembly order (NOT alphabetical, so the ordering
     * assertion below could not be satisfied by a filter that sorted). Every id the roster grants is
     * present, plus rows no guardian was granted.
     */
    private val assembledIds: List<String> = listOf(
        "get_time_info", "ask_user", "clipboard_tool", "memory_search", "memory_read", "memory_write",
        "search_web", "scrape_web", "web_fetch", "open_url",
        "mission_create", "mission_dispatch", "mission_report",
        "create_calendar_event", "schedule_job",
        "launch_app", "send_sms", "read_window_tree", "tap", "take_screenshot",
        "get_battery_status", "get_location", "read_sensor",
        "get_storage_info", "list_files", "find_files", "move_file", "delete_file", "list_recent_notifications",
        "read_file", "write_text_file", "recent_chats", "take_photo", "transcribe_audio_file",
        "termux_run_command", "ssh_exec",
        "workspace_shell", "workspace_read_file", "workspace_edit_file", "workspace_write_file",
        "text_to_speech", "show_image", "share", "use_skill",
        "tool_search", "tool_open", "tool_close", "compact_context",
        "my_installed_skill", "mcp__1__get_weather",
    )

    private val surface: List<Tool> = assembledIds.map { fake(it) }

    /** What reaches the model for [name], through the same two production pieces. */
    private fun reachesTheModel(name: String): List<String> {
        val assistant = GuardianSeed.assistantOf(
            GuardianSeedSpec(name = name, description = "", prompt = "persona", portrait = null, tools = null),
        )
        return me.rerere.rikkahub.data.ai.ToolSurfaceGuard
            .enforce(surface, assistant.allowedToolNames)
            .map { it.name }
    }

    @Test
    fun demiurgeAssemblyIsExactlyItsRowPlusHarnessPlumbing() {
        // Hand-written on purpose: an expectation computed from the same table the code reads would
        // prove nothing about ORDER, and order is part of the contract (a re-sorted surface reads as
        // a different assistant to a small model, and across builds it reads as drift).
        assertEquals(
            listOf(
                "get_time_info", "memory_search", "memory_read",
                "search_web", "scrape_web",
                "ask_user", "mission_create", "mission_dispatch",
                // plumbing the model never chose (ToolSurfaceGuard.HARNESS_TOOL_NAMES)
                "mission_report", "compact_context",
            ),
            reachesTheModel("Demiurge"),
        )
    }

    @Test
    fun everyGuardianReachesTheModelWithExactlyItsRowPlusPlumbing() {
        val plumbing = me.rerere.rikkahub.data.ai.ToolSurfaceGuard.HARNESS_TOOL_NAMES
        for ((name, row) in GuardianToolRoster.ROSTER) {
            val got = reachesTheModel(name)
            assertEquals("$name surface", (row + plumbing).toSet(), got.toSet())
            assertTrue(
                "$name carries ${got.size} tools; the prompt bound is 8 + ${plumbing.size} plumbing",
                got.size <= 8 + plumbing.size,
            )
        }
    }

    @Test
    fun noGuardianIsOfferedDiscoveryOrThirdPartyRows() {
        // The rows that broke the ceiling in the field: catalogue discovery, an installed skill, an
        // MCP tool. None of the Nine was granted them, so none may see them.
        val noise = listOf("tool_search", "tool_open", "tool_close", "my_installed_skill", "mcp__1__get_weather")
        for (name in GuardianToolRoster.ROSTER.keys) {
            val got = reachesTheModel(name)
            assertTrue("$name was offered $noise", noise.none { it in got })
        }
    }

    @Test
    fun onlyTheLiveFactGuardiansCanSearchTheWeb() {
        assertTrue("Demiurge must be able to answer a live-fact question", "search_web" in reachesTheModel("Demiurge"))
        for (name in listOf("Shalltear", "Cocytus", "Victim", "Pandora's Actor")) {
            assertFalse("$name has no live-fact duty", "search_web" in reachesTheModel(name))
        }
    }

    @Test
    fun anAssistantWithoutARowKeepsTheWholeSurface() {
        // The user's own assistants are not floor guards: null roster means the gate is a no-op, so
        // this change cannot alter behaviour for anyone but the Nine.
        val assistant = GuardianSeed.assistantOf(
            GuardianSeedSpec(name = "Ainz Ooal Gown", description = "", prompt = "supreme", portrait = null, tools = null),
        )
        assertEquals(null, assistant.allowedToolNames)
        assertEquals(
            assembledIds,
            me.rerere.rikkahub.data.ai.ToolSurfaceGuard.enforce(surface, assistant.allowedToolNames).map { it.name },
        )
    }

    @Test
    fun theSeedWritesTheRosterOntoTheFieldTheGateReads() {
        // The chain that actually ships: pack row -> Assistant.allowedToolNames (settings JSON) ->
        // ToolSurfaceGuard. If the seed stopped writing the field, every guardian below would fall
        // back to the whole catalogue and the tests above would still pass on their own tables.
        for ((name, row) in GuardianToolRoster.ROSTER) {
            val a = GuardianSeed.assistantOf(
                GuardianSeedSpec(name = name, description = "", prompt = "persona", portrait = null, tools = null),
            )
            assertEquals("$name writes its row", row, a.allowedToolNames)
        }
        assertEquals(4, GuardianSeed.SEED_VERSION)
    }
}
