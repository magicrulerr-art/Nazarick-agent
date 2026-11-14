package me.rerere.rikkahub.data.guardians

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AURA-021 v1.0 contract LC-2: the roster IS the ceiling, so the table itself is under test.
 *
 * The failure mode defended against is not a crash, it is a guardian quietly losing or gaining
 * capability: an id renamed in the registry stops matching and the guardian goes blind, or a row
 * grows past the review and every prompt grows with it. Cheap to assert, expensive to notice on a
 * phone.
 */
class GuardianToolRosterTest {

    private val NINE = listOf(
        "Demiurge", "Albedo", "Shalltear", "Cocytus", "Sebas",
        "Victim", "Mare", "Aura", "Pandora's Actor",
    )

    @Test
    fun rosterCoversExactlyTheNine() {
        assertEquals(NINE, GuardianToolRoster.ROSTER.keys.toList())
    }

    @Test
    fun everyRowRespectsTheEightToolCeiling() {
        // The cap is a token-budget rule as much as a behaviour rule: each row is a full JSON schema
        // in every prompt, and a 4B model pays for all of it.
        for ((name, ids) in GuardianToolRoster.ROSTER) {
            assertTrue("$name must carry a surface, not an accidental empty list", ids.isNotEmpty())
            assertTrue("$name grants ${ids.size} tools, ceiling is 8", ids.size <= 8)
            assertEquals("$name lists a tool twice", ids.size, ids.distinct().size)
        }
    }

    @Test
    fun everyGrantedIdIsARealRegistryId() {
        // Both reviewers wrote "web_search" for the tool the registry names "search_web". A string
        // precondition cannot catch that; this can, because KNOWN_TOOL_IDS is the registry
        // enumeration transcribed.
        val unknown = GuardianToolRoster.grantedIds().filter { it !in GuardianToolRoster.KNOWN_TOOL_IDS }
        assertEquals("roster grants ids the registry does not declare", emptyList<String>(), unknown)
    }

    @Test
    fun idsAreRegistryIdsAndNeverDisplayLabels() {
        val shape = Regex("^[a-z0-9_]+$")
        for (id in GuardianToolRoster.grantedIds()) {
            assertTrue("$id is not a Tool.name-shaped id", shape.matches(id))
        }
    }

    @Test
    fun onlyTheLiveFactAssistantsCanReachTheWeb() {
        // "Answers live-fact questions" is the owner's stated reason for search_web. Shalltear reports
        // what an action produced and carries scrape_web instead; Cocytus and Pandora's Actor have no
        // live-fact duty.
        val withSearch = GuardianToolRoster.ROSTER.filter { "search_web" in it.value }.keys.sorted()
        assertEquals(GuardianToolRoster.LIVE_FACT_GRANTEES.sorted(), withSearch)
    }

    @Test
    fun noRowGrantsADiscoveryTool() {
        // Under an eight-tool ceiling there is nothing left to discover: tool_search/tool_open/
        // tool_close would spend a round trip hunting a surface that is already the whole surface.
        for ((name, ids) in GuardianToolRoster.ROSTER) {
            for (banned in listOf("tool_search", "tool_open", "tool_close")) {
                assertTrue("$name grants $banned under an eight-tool ceiling", banned !in ids)
            }
        }
    }

    @Test
    fun unknownNameMeansNoCeilingNeverAnEmptySurface() {
        // An assistant the user renamed or added is theirs; falling back to [] would leave them
        // unable to act at all, which reads as a broken app rather than a guard.
        assertNull(GuardianToolRoster.toolIdsFor("Nobody"))
        assertEquals(8, GuardianToolRoster.toolIdsFor("Aura")!!.size)
        // Names are matched trimmed because an edited Room row can carry whitespace.
        assertEquals(GuardianToolRoster.toolIdsFor("Aura"), GuardianToolRoster.toolIdsFor("  Aura  "))
    }
}
