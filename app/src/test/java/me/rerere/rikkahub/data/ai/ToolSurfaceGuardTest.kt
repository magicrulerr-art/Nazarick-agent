package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AURA-021 v1.0 contract LC-2 (AURA-021 ruling D4): a guardian's tool surface is a hard ceiling.
 *
 * The symptom that shipped was two phones disagreeing because one offered 15 tools and the other 16,
 * so these tests guard the SHAPE of the answer as much as its content: the ceiling never widens,
 * never re-orders, and never quietly swallows a mistyped id.
 */
class ToolSurfaceGuardTest {

    private fun fake(name: String): Tool = Tool(
        name = name,
        description = "fake $name",
        parameters = { buildJsonObject { put("type", "object") } },
        execute = { listOf(UIMessagePart.Text("ok")) },
    )

    private val ids = listOf("search_web", "memory_search", "web_fetch", "tap", "compact_context")
    private val tools = ids.map { fake(it) }

    @Test
    fun noCeilingReturnsTheVerySameList() {
        // Every assistant carried null before the roster existed; a rollout that changed behaviour
        // for un-rostered assistants would be a rollout of the wrong thing.
        assertSame(tools, ToolSurfaceGuard.enforce(tools, null))
    }

    @Test
    fun emptyCeilingMeansNoToolsNotNoSetting() {
        assertEquals(listOf("compact_context"), ToolSurfaceGuard.namesOf(ToolSurfaceGuard.enforce(tools, emptyList())))
    }

    @Test
    fun ceilingKeepsExactlyTheGrantedIds() {
        val out = ToolSurfaceGuard.enforce(tools, listOf("search_web", "memory_search"))
        assertEquals(listOf("memory_search", "search_web"), ToolSurfaceGuard.namesOf(out))
    }

    @Test
    fun harnessPlumbingSurvivesACeilingThatNeverNamedIt() {
        // compact_context is plumbing the model does not choose (roster §3 item 2). Dropping it
        // would cost long-conversation capacity instead of narrowing a surface.
        val out = ToolSurfaceGuard.enforce(tools, listOf("search_web"))
        assertEquals(listOf("compact_context", "search_web"), ToolSurfaceGuard.namesOf(out))
        assertEquals("every exempt name is a hole in the ceiling", 2, ToolSurfaceGuard.HARNESS_TOOL_NAMES.size)
    }

    @Test
    fun assemblyOrderSurvivesTheFilter() {
        // A re-sorted surface reads as a different assistant across builds — the drift class LC-2
        // exists to kill, so ordering is an asserted property, not an accident of filter().
        val out = ToolSurfaceGuard.enforce(tools, listOf("tap", "search_web", "memory_search"))
        assertEquals(listOf("search_web", "memory_search", "tap"), out.map { it.name })
    }

    @Test
    fun mistypedGrantMatchesNothingRatherThanEverything() {
        // The trap: a roster spelling web search "web_search" binds to no Tool, and a naive
        // implementation would fall back to the whole catalogue.
        assertEquals(listOf("compact_context"), ToolSurfaceGuard.namesOf(ToolSurfaceGuard.enforce(tools, listOf("web_search"))))
    }

    @Test
    fun assertApprovedNamesTheToolsThatEscapedTheCeiling() {
        val escaped = runCatching {
            ToolSurfaceGuard.assertApproved(tools, listOf("search_web", "memory_search"))
        }.exceptionOrNull()
        assertNotNull("an escaping tool must throw", escaped)
        assertTrue(escaped is IllegalStateException)
        assertTrue(escaped.message.orEmpty(), escaped.message!!.contains("web_fetch"))
        assertNull(
            runCatching {
                ToolSurfaceGuard.assertApproved(listOf(fake("search_web"), fake("compact_context")), listOf("search_web"))
            }.exceptionOrNull(),
        )
    }
}
