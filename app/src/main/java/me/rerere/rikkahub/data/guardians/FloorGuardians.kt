package me.rerere.rikkahub.data.guardians

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.agentdef.AgentDefinition
import me.rerere.rikkahub.data.agentdef.AgentDefinitionDefaults
import me.rerere.rikkahub.data.agentdef.AgentDefinitionRepository
import me.rerere.rikkahub.data.agentdef.AgentNamespace
import me.rerere.rikkahub.data.ai.tools.LocalToolOption

/**
 * AURA-012 — the **Nine**: a bundled pack of minified floor-guardian personalities.
 *
 * The app already had an expert library ([AgentDefinition] + `subagent_dispatch`); what it did
 * not have was anything to put in it. A user starting from zero faced a blank form and had to
 * invent both the persona and the tool surface. This pack ships nine ready profiles, one per
 * duty the Great Tomb actually runs, so the library demonstrates itself on first open.
 *
 * **Why "minified" is a tested number and not an adjective.** The fleet learned on 2026-10-29
 * that a persona is not free: a 31 KB SOUL.md in the system prompt squeezed the tool list out
 * of the context window entirely (AURA-004). An expert's [AgentDefinition.systemPrompt] is
 * injected on every dispatch of that expert, so the nine carry a hard budget here — see
 * [MAX_PROMPT_CHARS] — enforced by unit test rather than by good intentions. The whole pack is
 * smaller than one third of that incident, and a single guardian costs roughly a screenful.
 *
 * Everything below the installer is pure: parsing, validation and the install plan run on a
 * plain JVM, which is what makes the shipped asset itself testable (see FloorGuardiansTest).
 */
@Serializable
data class GuardianSpec(
    /** Display name, and the string `subagent_dispatch` matches against — unique, case-insensitively. */
    val name: String,

    /** D9 private namespace segment (`agents/<slug>`), so each guardian keeps its own memory. */
    val slug: String? = null,

    /** One-line summary shown in the picker and read by the dispatcher when choosing whom to call. */
    val description: String = "",

    /** The persona. Budget-bound; see [FloorGuardians.MAX_PROMPT_CHARS]. */
    val prompt: String,

    /**
     * Explicit local-tool names, or null to inherit the parent assistant's surface.
     *
     * Kept as raw strings rather than [LocalToolOption] so a pack authored for a *newer* build
     * still parses; unknown names are reported by [FloorGuardians.validate] instead of being
     * dropped in silence by
     * [me.rerere.rikkahub.data.ai.tools.LenientLocalToolListSerializer] at storage time.
     */
    val tools: List<String>? = null,

    /**
     * Asset path to a canon portrait, resolved by Coil's asset fetcher at render time.
     *
     * Deliberately **not** a column on [AgentDefinition]: that table lives in its own database
     * file precisely so its Room identity hash stops moving, and a portrait is not worth a
     * migration. The pack owns its art, so art and persona install together and a hand-made
     * expert is unaffected. Null = the row renders its initial, as it did before art existed.
     */
    val portrait: String? = null,
)

@Serializable
data class GuardianPack(
    val version: Int = 1,
    val updated: String = "",
    val guardians: List<GuardianSpec> = emptyList(),
)

/** What the library already holds, decided before any write happens. */
data class GuardianInstallPlan(
    val insert: List<GuardianSpec> = emptyList(),
    /** Existing rows to overwrite, paired with the spec that replaces them. */
    val update: List<Pair<AgentDefinition, GuardianSpec>> = emptyList(),
    /** Name clashes left alone because [overwrite] was false — reported, never silently lost. */
    val kept: List<GuardianSpec> = emptyList(),
) {
    val writes: Int get() = insert.size + update.size
}

data class GuardianInstallResult(
    val inserted: Int = 0,
    val updated: Int = 0,
    val kept: Int = 0,
    val problems: List<String> = emptyList(),
) {
    val ok: Boolean get() = problems.isEmpty()
}

object FloorGuardians {

    /** Bundled with the APK, so pack format and parser version by construction. */
    const val ASSET_PATH = "guardians/floor-guardians.json"

    /** Hard ceiling on one persona. Measured headroom over the shipped pack (longest 704). */
    const val MAX_PROMPT_CHARS = 720

    /** Ceiling on all nine together — the number that actually lands in a dispatch chain. */
    const val MAX_PACK_PROMPT_CHARS = 6_000

    /**
     * Strict on purpose: an unknown key means the asset and this parser disagree, and a persona
     * silently missing its prompt is worse than a pack that refuses to install. The asset ships
     * inside the APK, so strictness costs nothing across versions.
     */
    val json = Json { ignoreUnknownKeys = false }

    /** @throws SerializationException when the asset does not match [GuardianPack]. */
    fun parse(text: String): GuardianPack = json.decodeFromString(GuardianPack.serializer(), text)

    /**
     * slug -> bundled portrait path.
     *
     * The library renders [AgentDefinition] rows, which come from Room and carry no art; this is
     * how a row finds its face. The pack remains the single declaration of who the Nine are, so
     * adding a tenth guardian means adding one JSON entry and one JPEG — no schema, no UI edit.
     */
    fun portraitsOf(pack: GuardianPack): Map<String, String> =
        pack.guardians.mapNotNull { g ->
            val slug = g.slug ?: return@mapNotNull null
            val portrait = g.portrait ?: return@mapNotNull null
            slug to portrait
        }.toMap()

    /**
     * Resolves a tool name through the very serializer that stores it — the sealed-class
     * discriminator is `"type"`, so `{"type":"browser"}` is exactly what a row will hold.
     *
     * Probing beats enumerating `LocalToolOption.serializer().descriptor`: every option is a
     * `data object` today, and this stays correct if one grows required parameters tomorrow.
     *
     * @throws SerializationException for a name this build does not define.
     */
    fun decodeTool(name: String): LocalToolOption =
        json.decodeFromString(LocalToolOption.serializer(), """{"type":"$name"}""")

    /** True when [name] decodes into a real tool on *this* build. */
    fun resolvesTool(name: String): Boolean = try {
        decodeTool(name)
        true
    } catch (_: SerializationException) {
        false
    }

    /**
     * Every rule the pack must satisfy, as human-readable problems. Empty means installable.
     *
     * Validated as a whole rather than row-by-row while writing, so a pack with one bad name
     * installs nothing instead of leaving half the Tomb on the floor.
     */
    fun validate(pack: GuardianPack): List<String> {
        val problems = mutableListOf<String>()
        if (pack.guardians.isEmpty()) {
            problems += "pack contains no guardians"
            return problems
        }
        if (pack.guardians.size > AgentDefinitionDefaults.QUERY_LIMIT) {
            problems += "pack has ${pack.guardians.size} guardians, over the library list limit"
        }

        val byName = mutableMapOf<String, String>()
        val bySlug = mutableMapOf<String, String>()
        var promptTotal = 0

        for ((index, spec) in pack.guardians.withIndex()) {
            val label = spec.name.ifBlank { "#${index + 1}" }
            val trimmed = spec.name.trim()
            if (trimmed.isEmpty()) problems += "$label: name is blank"
            if (trimmed.length > AgentDefinitionDefaults.MAX_NAME_LENGTH) {
                problems += "$label: name is ${trimmed.length} chars, over ${AgentDefinitionDefaults.MAX_NAME_LENGTH}"
            }
            if (spec.description.length > AgentDefinitionDefaults.MAX_DESCRIPTION_LENGTH) {
                problems += "$label: description is ${spec.description.length} chars, over ${AgentDefinitionDefaults.MAX_DESCRIPTION_LENGTH}"
            }
            if (spec.prompt.isBlank()) problems += "$label: prompt is blank"
            if (spec.prompt.length > MAX_PROMPT_CHARS) {
                problems += "$label: prompt is ${spec.prompt.length} chars, over the $MAX_PROMPT_CHARS minified budget"
            }
            promptTotal += spec.prompt.length

            if (trimmed.isNotEmpty()) {
                val key = trimmed.lowercase()
                val clash = byName.put(key, label)
                if (clash != null) problems += "$label: duplicates the name of $clash"
            }
            if (spec.slug != null) {
                val normalized = AgentNamespace.normalizeSlug(spec.slug)
                if (normalized == null) {
                    problems += "$label: slug '${spec.slug}' normalises to nothing usable"
                } else {
                    val clash = bySlug.put(normalized, label)
                    if (clash != null) problems += "$label: slug '$normalized' collides with $clash"
                }
            }
            for (tool in spec.tools.orEmpty()) {
                if (!resolvesTool(tool)) problems += "$label: unknown tool '$tool'"
            }
        }

        if (promptTotal > MAX_PACK_PROMPT_CHARS) {
            problems += "pack prompts total $promptTotal chars, over the $MAX_PACK_PROMPT_CHARS budget"
        }
        return problems
    }

    /**
     * Decides the writes for [pack] against [existing] without touching a database.
     *
     * Matching is on the trimmed, case-folded name because that is how dispatch resolves an
     * `agent` argument — a row named "demiurge" already *is* the Demiurge for every caller, so
     * installing a second one would make the name ambiguous and dispatch would refuse it.
     */
    fun plan(
        existing: List<AgentDefinition>,
        pack: GuardianPack,
        overwrite: Boolean = false,
    ): GuardianInstallPlan {
        val taken = existing.associateBy { it.name.trim().lowercase() }
        var plan = GuardianInstallPlan()
        for (spec in pack.guardians) {
            val match = taken[spec.name.trim().lowercase()]
            plan = when {
                match == null -> plan.copy(insert = plan.insert + spec)
                overwrite -> plan.copy(update = plan.update + (match to spec))
                else -> plan.copy(kept = plan.kept + spec)
            }
        }
        return plan
    }
}

/**
 * Writes a pack into the expert library. Deliberately thin: the decisions live in
 * [FloorGuardians.validate] / [FloorGuardians.plan], both pure, so this is the only part that
 * needs the repository — and the only part that cannot be tested without one.
 */
class FloorGuardianInstaller(private val repo: AgentDefinitionRepository) {

    suspend fun install(text: String, overwrite: Boolean = false): GuardianInstallResult {
        val pack = try {
            FloorGuardians.parse(text)
        } catch (e: SerializationException) {
            return GuardianInstallResult(problems = listOf("pack unreadable: ${e.message}"))
        }
        val problems = FloorGuardians.validate(pack)
        if (problems.isNotEmpty()) {
            return GuardianInstallResult(problems = problems)
        }

        val plan = FloorGuardians.plan(repo.refresh(), pack, overwrite)

        // Resolve every tool name BEFORE the first write. Validation already proved they all
        // decode, so this is belt-and-braces against a race between the two probes — and being
        // total is worth three lines: a pack that cannot be written is refused whole, never
        // half-installed with the Tomb missing its middle floors.
        val tools: Map<String, LocalToolOption> = try {
            buildMap {
                for (spec in pack.guardians) {
                    for (name in spec.tools.orEmpty()) {
                        if (!containsKey(name)) put(name, FloorGuardians.decodeTool(name))
                    }
                }
            }
        } catch (e: SerializationException) {
            return GuardianInstallResult(problems = listOf("tool no longer resolvable: ${e.message}"))
        }

        var inserted = 0
        var updated = 0
        for (spec in plan.insert) {
            repo.create(
                name = spec.name.trim(),
                description = spec.description,
                systemPrompt = spec.prompt,
                localTools = spec.tools?.map { tools.getValue(it) },
                slug = spec.slug,
            )
            inserted++
        }
        for ((existing, spec) in plan.update) {
            repo.update(
                existing.copy(
                    name = spec.name.trim(),
                    description = spec.description,
                    systemPrompt = spec.prompt,
                    localTools = spec.tools?.map { tools.getValue(it) },
                    slug = spec.slug,
                ),
            )
            updated++
        }
        return GuardianInstallResult(
            inserted = inserted,
            updated = updated,
            kept = plan.kept.size,
        )
    }
}
