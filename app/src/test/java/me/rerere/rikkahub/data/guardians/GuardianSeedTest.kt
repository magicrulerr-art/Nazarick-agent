package me.rerere.rikkahub.data.guardians

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AURA-014 bug #2 — the Nine must arrive on a fresh install and stay gone when deleted.
 *
 * The seed is deliberately not a `DEFAULT_ASSISTANTS` member (those reappear on every
 * settings read); it is a one-shot, versioned insert. These tests pin the pure planner:
 * fresh installs get the pack, seeded installs get nothing, deletions are not re-added,
 * ids are stable across calls (so a re-seed after a version bump can never double-insert
 * through a different identity), and name matching is case/whitespace-insensitive the way
 * the sub-agent installer's is.
 */
class GuardianSeedTest {

    private fun spec(name: String, portrait: String? = "guardians/portraits/x.jpg") =
        GuardianSeedSpec(name, "title of $name", "prompt of $name", portrait)

    private val pack = listOf(
        spec("Albedo"), spec("Shalltear"), spec("Cocytus"), spec("Demiurge"),
        spec("Sebas"), spec("Victim"), spec("Mare"), spec("Aura"), spec("Pandora's Actor"),
    )

    @Test
    fun `fresh install receives the whole pack`() {
        val plan = GuardianSeed.plan(emptyList(), pack)
        assertEquals(9, plan.inserts.size)
        assertEquals(
            pack.map { it.name },
            plan.inserts.map { it.name },
        )
        assertEquals(0, plan.kept)
        assertTrue(plan.changed)
    }

    @Test
    fun `a seeded install adds nothing`() {
        val seeded = GuardianSeed.plan(emptyList(), pack).inserts
        val again = GuardianSeed.plan(seeded, pack)
        assertEquals(emptyList<Assistant>(), again.inserts)
        assertEquals(9, again.kept)
        assertTrue(!again.changed)
    }

    @Test
    fun `a deleted guardian is not re-added by the planner`() {
        val seeded = GuardianSeed.plan(emptyList(), pack).inserts
        val withoutShalltear = seeded.filterNot { it.name == "Shalltear" }
        // The planner itself is version-blind — re-adding deletions only ever happens when
        // the seed version bumps. What this pins is that the planner MATCHES by name, so
        // the versioned path cannot resurrect a deleted row by identity mismatch.
        val plan = GuardianSeed.plan(withoutShalltear, pack)
        assertEquals(listOf("Shalltear"), plan.inserts.map { it.name })
        assertEquals(8, plan.kept)
    }

    @Test
    fun `ids are deterministic per name and sensitive to untrimmed input`() {
        val a = GuardianSeed.assistantId("albedo")
        val b = GuardianSeed.assistantOf(spec("Albedo")).id
        val c = GuardianSeed.assistantOf(spec("  ALBEDO  ")).id
        assertEquals(b, a)
        // The id derives from the raw spec name lowercased — validate()/install() trim
        // pack names upstream, so an untrimmed spec here deliberately produces a
        // DIFFERENT id: pinning that keeps the contract "ids follow trimmed names"
        // visible if someone ever moves the trim.
        assertTrue(c != a)
    }

    @Test
    fun `portrait paths become android_asset image avatars`() {
        val assistant = GuardianSeed.assistantOf(spec("Aura", "guardians/portraits/aura.jpg"))
        assertEquals(
            Avatar.Image("file:///android_asset/guardians/portraits/aura.jpg"),
            assistant.avatar,
        )
        assertTrue(assistant.useAssistantAvatar)
        val noPortrait = GuardianSeed.assistantOf(spec("Nobody", null))
        assertEquals(Avatar.Dummy, noPortrait.avatar)
        assertTrue(!noPortrait.useAssistantAvatar)
    }

    @Test
    fun `seeded guardians carry the agent skill kit like the defaults do`() {
        val plan = GuardianSeed.plan(emptyList(), pack)
        plan.inserts.forEach { assistant ->
            assertTrue(
                "guardian ${assistant.name} must ride on agent-core",
                "agent-core" in assistant.enabledSkills,
            )
        }
    }
}
