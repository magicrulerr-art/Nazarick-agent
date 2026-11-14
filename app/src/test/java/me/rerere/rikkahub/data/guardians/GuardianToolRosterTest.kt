package me.rerere.rikkahub.data.guardians

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AURA-021 v1.0 contract LC-2: the roster IS the ceiling, so the table itself is under test.
 *
 * The failure mode being defended against is not a crash, it is a guardian quietly losing or gaining
 * capability: an id renamed in the registry stops matching and the guardian goes blind, or a row
 * grows past the review and the prompt grows with it. Both are cheap to assert and expensive to
 * notice on a phone.
 */
class GuardianToolRosterTest {

    private val NINE = listOf(
        "Demiurge", "Albedo", "Shalltear", "Cocytus", "Sebas",
        "Victim", "Mare", "Aura", "Pandora's Actor",
    )

    @Test
    fun `the roster covers exactly the Nine`() {
        assertEquals(NINE, GuardianToolRoster.ROSTER.keys.toList())
    }

    @Test
    fun `every row respects the eight-tool ceiling`() {
        // The cap is a token-budget rule as much as a behaviour rule: each row is a full JSON schema
        // in every prompt, and a 4B model pays for all of it.
        for ((name, ids) in GuardianToolRoster.ROSTER) {
            assertTrue(ids.isNotEmpty(), "$name must carry a surface, not an accidental empty list")
            assertTrue(ids.size <= 8, "$name grants ${ids.size} tools, ceiling is 8")
            assertEquals(ids.size, ids.distinct().size, "$name lists a tool twice")
        }
    }

    @Test
    fun `every granted id is a real registry id`() {
        // Reviewers wrote "web_search" for the tool the registry names "search_web". A string
        // precondition cannot catch that, this can: the id set here is the registry enumeration.
        val unknown = GuardianToolRoster.grantedIds().filter { it !in GuardianToolRoster.KNOWN_TOOL_IDS }
        assertEquals(emptyList(), unknown, "roster grants ids the registry does not declare")
    }

    @Test
    fun `ids are registry ids and never display labels`() {
        for (id in GuardianToolRoster.grantedIds()) {
            assertTrue(Regex("^[a-z0-9_]+$").matches(id), "$id is not a Tool.name-shaped id")
        }
    }

    @Test
    fun `only the live-fact assistants can reach the web`() {
        // "Answers live-fact questions" was the owner's reason for search_web. Shalltear reports what
        // an action produced and carries scrape_web instead; Cocytus and Pandora's Actor have no
        // live-fact duty at all.
        val withSearch = GuardianToolRoster.ROSTER
            .filter { "search_web" in it.value }
            .keys.sorted()
        assertEquals(GuardianToolRoster.LIVE_FACT_GRANTEES.sorted(), withSearch)
    }

    @Test
    fun `no row grants a discovery tool`() {
        // With at most eight tools there is nothing left to discover; tool_search/tool_open/tool_close
        // would be a round trip spent looking for a surface that is already the whole surface.
        for ((name, ids) in GuardianToolRoster.ROSTER) {
            for (banned in listOf("tool_search", "tool_open", "tool_close")) {
                assertTrue(banned !in ids, "$name grants $banned under an eight-tool ceiling")
            }
        }
    }

    @Test
    fun `an unknown name means no ceiling, never an empty surface`() {
        // An assistant the user renamed or added is theirs; falling back to [] would leave them
        // unable to act at all, which reads as a broken app rather than a guard.
        assertNull(GuardianToolRoster.toolIdsFor("Nobody"))
        assertEquals(8, GuardianToolRoster.toolIdsFor("Aura")?.size)
        // Names are matched trimmed because the Room row can carry whitespace from an edit.
        assertEquals(GuardianToolRoster.toolIdsFor("Aura"), GuardianToolRoster.toolIdsFor("  Aura  "))
    }
}
