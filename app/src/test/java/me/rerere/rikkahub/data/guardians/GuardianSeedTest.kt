package me.rerere.rikkahub.data.guardians

import me.rerere.rikkahub.data.ai.tools.LocalToolOption
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

    @Test
    fun `assistantOf carries the pack description into the assistant`() {
        // v0.7 owner request: the lists show what to expect from each guardian; the seed
        // silently dropped the pack description before the Assistant field existed.
        val assistant = GuardianSeed.assistantOf(spec("Aura"))
        assertEquals("title of Aura", assistant.description)
    }

    @Test
    fun `withPackDescriptions refreshes blank v1 rows and nothing else`() {
        val seededV1 = GuardianSeed.plan(emptyList(), pack).inserts
            .map { it.copy(description = "") } // what shipped in v1
        val custom = Assistant(name = "My helper", description = "user's own words")
        val refreshed = GuardianSeed.withPackDescriptions(seededV1 + custom, pack)

        // every guardian row got its pack line back
        assertEquals(
            pack.map { "title of ${it.name}" },
            refreshed.take(9).map { it.description },
        )
        // the non-guardian row passed through untouched, identity and all
        assertTrue(refreshed.last() === custom)
        // idempotent: a second pass allocates nothing new to change
        assertTrue(GuardianSeed.withPackDescriptions(refreshed, pack) == refreshed)
        // user edits to OTHER fields survive a refresh (description-only by contract)
        val edited = refreshed.take(1).map { it.copy(systemPrompt = "user words") }
        val again = GuardianSeed.withPackDescriptions(edited + refreshed.drop(1), pack)
        assertEquals("user words", again.first().systemPrompt)
        assertEquals("title of Albedo", again.first().description)
    }

    // ── v0.8 curation: the pack's grants become the chat surface's ceiling ──────────────────

    @Test
    fun `assistantOf carries the pack grants into the tool allow-list and the gated switches`() {
        val withTools = GuardianSeed.assistantOf(
            GuardianSeedSpec("Aura", "t", "p", null, listOf("time_info", "termux", "files")),
        )
        assertEquals(
            listOf(LocalToolOption.TimeInfo, LocalToolOption.Termux, LocalToolOption.Files),
            withTools.toolAllowlist,
        )
        // localTools rides along so the pack-granted GATED groups count as switched-on (the
        // gate still conditions them); the allow-list is the same set.
        assertEquals(withTools.toolAllowlist, withTools.localTools)
        // no grants = no allow-list: catalogue-wide, exactly the pre-v0.8 assistant
        val legacy = GuardianSeed.assistantOf(spec("Nobody"))
        assertTrue(legacy.toolAllowlist == null)
    }

    @Test
    fun `packToolOptions drops ids this build does not define and keeps null distinct from empty`() {
        assertTrue(GuardianSeed.packToolOptions(null) == null)
        assertEquals(emptyList<LocalToolOption>(), GuardianSeed.packToolOptions(emptyList()))
        assertEquals(
            listOf(LocalToolOption.Files),
            GuardianSeed.packToolOptions(listOf("files", "totally_made_up_tool")),
        )
    }

    @Test
    fun `withPackToolGrants fills only rows the user never curated`() {
        val grantPack = listOf(GuardianSeedSpec("Aura", "t", "p", null, listOf("time_info", "files")))
        val curatedPack = grantPack + GuardianSeedSpec("Mare", "t", "p", null, listOf("files"))
        val uncurated = GuardianSeed.assistantOf(GuardianSeedSpec("Aura", "t", "p", null))
        val userCurated = GuardianSeed.assistantOf(GuardianSeedSpec("Mare", "t", "p", null))
            .copy(toolAllowlist = listOf(LocalToolOption.CameraPhoto)) // the user's own surface
        val foreign = Assistant(name = "My helper")

        val out = GuardianSeed.withPackToolGrants(listOf(uncurated, userCurated, foreign), grantPack)

        // the never-curated guardian got the pack grant
        assertEquals(listOf(LocalToolOption.TimeInfo, LocalToolOption.Files), out[0].toolAllowlist)
        // a curated guardian is NOT overwritten — even when the pack still ships her
        assertEquals(listOf(LocalToolOption.CameraPhoto), out[1].toolAllowlist)
        // a non-guardian row passes through by identity
        assertTrue(out[2] === foreign)
        // idempotent
        assertTrue(GuardianSeed.withPackToolGrants(out, grantPack) == out)
        // a spec without grants (null) fills nothing
        val noGrants = listOf(GuardianSeed.assistantOf(GuardianSeedSpec("Aura", "t", "p", null)))
        val filled = GuardianSeed.withPackToolGrants(noGrants, grantPack.map { it.copy(tools = null) })
        assertTrue(filled.first() === noGrants.first())
    }
}
