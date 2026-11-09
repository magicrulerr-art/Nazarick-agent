package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.guardians.GuardianSeed
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * v0.6 owner ruling: "delete the 2 default assistants, we don't want them."
 * These pins are the whole contract — if one fails, an upgrade either resurrects the
 * donor skeletons or orphans somebody's chat history.
 */
class AssistantRetirementTest {

    @Test
    fun `default assistant list is empty after retirement`() {
        assertTrue(
            "DEFAULT_ASSISTANTS must stay empty — anything here re-appears on every load",
            DEFAULT_ASSISTANTS.isEmpty(),
        )
    }

    @Test
    fun `fallback id is Albedo by three independent derivations`() {
        val pinned = Uuid.parse("1f7021e8-fb41-3f31-b85e-d0db562c3d68")
        // 1. the datastore constant new conversations use,
        assertEquals(pinned, DEFAULT_ASSISTANT_ID)
        // 2. the slug-derived id of Albedo's seeded card (new chats must land on a REAL card),
        assertEquals(pinned, GuardianSeed.assistantId("albedo"))
        // 3. the literal baked into Migration_39_40's UPDATE (private there; if this drifts,
        //    re-home lands on a ghost id).
        assertEquals(
            pinned.toString(),
            java.util.UUID.nameUUIDFromBytes("nazarick-assistant:albedo".toByteArray()).toString(),
        )
    }

    @Test
    fun `retired ids are exactly the two donor skeletons`() {
        assertEquals(
            setOf(
                Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e"),
                Uuid.parse("3d47790c-c415-4b90-9388-751128adb0a0"),
            ),
            RETIRED_DEFAULT_ASSISTANT_IDS,
        )
    }

    @Test
    fun `retire filter drops the skeletons and touches nothing else`() {
        val albedo = GuardianSeed.assistantId("albedo")
        val user = Uuid.random()
        val loaded = listOf(
            Assistant(id = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e"), name = "Ainz"),
            Assistant(id = Uuid.parse("3d47790c-c415-4b90-9388-751128adb0a0"), name = "Ainz 2"),
            Assistant(id = albedo, name = "Albedo"),
            Assistant(id = user, name = "My card"),
        )
        val after = retireDefaultAssistants(loaded)
        assertEquals(listOf(albedo, user), after.map { it.id })
        // Idempotent: replaying the filter (every load runs it) is free.
        assertEquals(after, retireDefaultAssistants(after))
    }
}
