package me.rerere.rikkahub.data.guardians

import me.rerere.rikkahub.data.agentdef.AgentDefinition
import me.rerere.rikkahub.data.agentdef.AgentNamespace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AURA-012 — the Nine.
 *
 * Two things are being tested and only one of them is my code. The other is the **shipped
 * asset**: it is read from disk, parsed by the strict parser, validated against the tool
 * serializer this build actually compiles in, and measured against the minified budget. That
 * matters because every failure mode of a persona pack is silent at runtime — a mistyped tool
 * name is dropped by `LenientLocalToolListSerializer` without a word, and an oversized prompt
 * costs context on every dispatch instead of failing.
 *
 * Nothing here touches Room: [FloorGuardians.plan] takes the existing rows as data, which is
 * why the install decision can be tested at all.
 */
class FloorGuardiansTest {

    private fun packFile(): File {
        val candidates = listOf(
            File("src/main/assets/guardians/floor-guardians.json"),
            File("app/src/main/assets/guardians/floor-guardians.json"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("could not find the bundled guardian pack, tried: $candidates")
    }

    private fun bundledPack(): GuardianPack = FloorGuardians.parse(packFile().readText())

    // ── the shipped pack ───────────────────────────────────────────────────────────────────

    @Test
    fun `the bundled pack validates clean`() {
        val problems = FloorGuardians.validate(bundledPack())
        assertEquals("bundled pack must install without complaints", emptyList<String>(), problems)
    }

    @Test
    fun `the bundled pack carries all nine guardians with unique names`() {
        val pack = bundledPack()
        assertEquals(9, pack.guardians.size)
        val names = pack.guardians.map { it.name.trim().lowercase() }
        assertEquals("names must be unique case-insensitively", names.size, names.toSet().size)
        assertEquals(
            listOf(
                "demiurge", "albedo", "shalltear", "cocytus", "sebas",
                "victim", "mare", "aura", "pandora's actor",
            ),
            names,
        )
    }

    @Test
    fun `every tool named by the bundled pack resolves on this build`() {
        val unresolved = buildList {
            for (spec in bundledPack().guardians) {
                for (tool in spec.tools.orEmpty()) {
                    if (!FloorGuardians.resolvesTool(tool)) add("${spec.name}: $tool")
                }
            }
        }
        assertEquals(emptyList<String>(), unresolved)
    }

    @Test
    fun `the bundled pack stays inside the minified budget`() {
        val pack = bundledPack()
        val total = pack.guardians.sumOf { it.prompt.length }
        assertTrue(
            "longest persona ${pack.guardians.maxOf { it.prompt.length }} must fit $FloorGuardians.MAX_PROMPT_CHARS",
            pack.guardians.all { it.prompt.length <= FloorGuardians.MAX_PROMPT_CHARS },
        )
        assertTrue("pack total $total must fit ${FloorGuardians.MAX_PACK_PROMPT_CHARS}", total <= FloorGuardians.MAX_PACK_PROMPT_CHARS)
        // The point of the whole exercise, stated as a number: the nine together must stay far
        // cheaper than the 31 KB persona that ate the tool list in AURA-004.
        assertTrue("pack total $total chars should stay well under a third of the AURA-004 incident", total < 10_000)
    }

    @Test
    fun `every guardian declares a namespace slug so its memory stays its own`() {
        for (spec in bundledPack().guardians) {
            val slug = spec.slug
            assertTrue(
                "${spec.name} must ship a valid slug (got '$slug')",
                slug != null && AgentNamespace.isValidSlug(slug),
            )
        }
    }

    // ── codec strictness ───────────────────────────────────────────────────────────────────

    @Test
    fun `a mistyped key is refused rather than silently ignored`() {
        val json = """
            {"version":1,"guardians":[{"name":"X","promt":"oops"}]}
        """.trimIndent()
        val thrown = runCatching { FloorGuardians.parse(json) }.exceptionOrNull()
        assertTrue("expected a serialization failure, got $thrown", thrown != null)
    }

    @Test
    fun `a real tool name resolves and an invented one does not`() {
        assertTrue(FloorGuardians.resolvesTool("browser"))
        assertFalse(FloorGuardians.resolvesTool("telekinesis"))
    }

    // ── validation rules ───────────────────────────────────────────────────────────────────

    private fun spec(
        name: String = "Tester",
        prompt: String = "do the thing",
        tools: List<String>? = null,
        slug: String? = null,
        description: String = "",
    ) = GuardianSpec(name = name, slug = slug, description = description, prompt = prompt, tools = tools)

    @Test
    fun `an empty pack is a problem`() {
        assertEquals(listOf("pack contains no guardians"), FloorGuardians.validate(GuardianPack()))
    }

    @Test
    fun `an unknown tool name is reported by name`() {
        val problems = FloorGuardians.validate(
            GuardianPack(guardians = listOf(spec(tools = listOf("browser", "telekinesis")))),
        )
        assertEquals(listOf("Tester: unknown tool 'telekinesis'"), problems)
    }

    @Test
    fun `duplicate names are caught case-insensitively`() {
        val problems = FloorGuardians.validate(
            GuardianPack(guardians = listOf(spec(name = "Aura"), spec(name = "aura "))),
        )
        assertTrue("expected a duplicate-name problem, got $problems", problems.any { it.contains("duplicates the name") })
    }

    @Test
    fun `colliding slugs are reported`() {
        val problems = FloorGuardians.validate(
            GuardianPack(guardians = listOf(spec(name = "A", slug = "deep-research"), spec(name = "B", slug = "Deep Research"))),
        )
        assertTrue("expected a slug collision, got $problems", problems.any { it.contains("collides with") })
    }

    @Test
    fun `an oversized prompt trips the minified budget`() {
        val problems = FloorGuardians.validate(
            GuardianPack(guardians = listOf(spec(prompt = "x".repeat(FloorGuardians.MAX_PROMPT_CHARS + 1)))),
        )
        assertTrue("expected a budget problem, got $problems", problems.any { it.contains("minified budget") })
    }

    @Test
    fun `a blank prompt is a problem`() {
        val problems = FloorGuardians.validate(GuardianPack(guardians = listOf(spec(prompt = "   "))))
        assertTrue("expected a blank-prompt problem, got $problems", problems.any { it.contains("prompt is blank") })
    }

    // ── install planning ───────────────────────────────────────────────────────────────────

    private fun row(id: String, name: String) = AgentDefinition(id = id, name = name)

    private val nine = GuardianPack(guardians = listOf(spec(name = "Demiurge"), spec(name = "Aura")))

    @Test
    fun `an empty library installs the whole pack`() {
        val plan = FloorGuardians.plan(emptyList(), nine)
        assertEquals(2, plan.insert.size)
        assertEquals(0, plan.update.size)
        assertEquals(0, plan.kept.size)
        assertEquals(2, plan.writes)
    }

    @Test
    fun `an existing name is kept, not duplicated, unless overwriting`() {
        val existing = listOf(row("id-1", "demiurge"))
        val keep = FloorGuardians.plan(existing, nine, overwrite = false)
        assertEquals(listOf("Aura"), keep.insert.map { it.name })
        assertEquals(1, keep.kept.size)
        assertEquals("Demiurge", keep.kept.first().name)
        assertEquals(0, keep.update.size)

        val overwrite = FloorGuardians.plan(existing, nine, overwrite = true)
        assertEquals(listOf("Aura"), overwrite.insert.map { it.name })
        assertEquals(1, overwrite.update.size)
        assertEquals("id-1", overwrite.update.first().first.id)
        assertEquals("Demiurge", overwrite.update.first().second.name)
        assertEquals(0, overwrite.kept.size)
    }

    @Test
    fun `installing twice writes nothing the second time`() {
        val installed = FloorGuardians.plan(emptyList(), nine).insert.map { row("id-${it.name}", it.name) }
        val second = FloorGuardians.plan(installed, nine, overwrite = false)
        assertEquals(0, second.writes)
        assertEquals(2, second.kept.size)
    }
}
