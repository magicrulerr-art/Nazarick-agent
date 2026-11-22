package me.rerere.rikkahub.data.guardians

import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANTS_IDS
import me.rerere.rikkahub.data.datastore.DEFAULT_AUTO_ENABLED_SKILLS
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import kotlin.uuid.Uuid

/**
 * AURA-014 — the Floor Guardians as chat assistants, seeded once at first boot.
 *
 * AURA-012 shipped the Nine as a *manual* install card on the sub-agents page; the
 * owner opened a fresh v0.03 install, saw two unnamed defaults and asked where the
 * guardians were. The pack stays where it is (sub-agent form, Telegram-bot flavour);
 * this adds the chat form: one [Assistant] per guardian, appearing in the assistant
 * list the way the two defaults do.
 *
 * Why not just append them to `DEFAULT_ASSISTANTS`: the read path re-adds every
 * missing DEFAULT assistant on *every* load, so deleting Shalltear would be
 * impossible. The repo's answer to "bundle content once, then respect the user" is
 * the version marker (`DEFAULT_AUTO_ENABLED_SKILLS`, `agentDefaultsVersion`), so
 * this follows it: [guardiansSeedVersion] records that seeding happened; deletions
 * stick until [SEED_VERSION] itself is bumped, which re-adds missing guardians as
 * the deliberate "the pack changed, take another copy" signal.
 *
 * The pack arrives in Ainz's image: the sub-agent pack lists nine and Ainz fronts
 * them. v0.6 retired the two donor defaults (owner ruling — see
 * RETIRED_DEFAULT_ASSISTANT_IDS in PreferencesStore), so the seeded Nine ARE the roster
 * and the tomb master keeps the mascot duties; the fallback assistant id now points at
 * Albedo, derived from this same slug scheme.
 */
data class GuardianSeedSpec(
    val name: String,
    val description: String,
    val prompt: String,
    /** Asset path inside the APK, e.g. "guardians/portraits/albedo.jpg". */
    val portrait: String?,
    /**
     * The pack's tool grants (raw [LocalToolOption] ids, same vocabulary as the sub-agent pack).
     * Null = no allowlist (catalogue-wide); a list — even empty — becomes
     * [Assistant.toolAllowlist] via [packToolOptions], which is the v0.8 curation ruling:
     * each guardian gets ONLY the tools its job needs, in the chat form as much as dispatched.
     */
    val tools: List<String>? = null,
)

data class GuardianSeedPlan(
    val inserts: List<Assistant> = emptyList(),
    /** Guardian assistants already present, by name — nothing to do for them. */
    val kept: Int = 0,
    /** Collides with an existing non-guardian assistant name: skipped, never renamed. */
    val skippedNames: List<String> = emptyList(),
) {
    val changed: Boolean get() = inserts.isNotEmpty()
}

/** Pure part: pack + current assistants -> the assistants to add. No Android, no IO. */
object GuardianSeed {

    /** Bump when the bundled pack changes and installs should receive it again.
     *  v2 = v0.7 pack: descriptions rewritten user-facing; the bump also routes existing
     *  installs through [withPackDescriptions] to fill the blank v1 rows.
     *  v3 = v0.8 curation: every guardian carries an explicit tool allowlist (owner ruling
     *  2026-11-10); the bump routes existing rows through [withPackToolGrants], which fills
     *  the grant only where the user never set one.
     *  v4 = AURA-021 v1.0 contract LC-2: the Nine carry the ratified ID-level roster
     *  (GuardianToolRoster) over the group grants, and the bump routes existing rows through
     *  [withPackToolIds]. Group grants alone were not a ceiling - skill and MCP tools are
     *  appended after the resolver and never consult one. */
    const val SEED_VERSION: Int = 4

    /**
     * Pack tool ids -> this build's [LocalToolOption]s, unknown ids dropped.
     *
     * Forward compatibility, mirroring [LenientLocalToolListSerializer] at storage time: a pack
     * authored for a newer build must not brick the seed on an older one. Null stays null —
     * "no allowlist, catalogue-wide" is a real choice, distinct from the empty list's
     * "owns an empty surface".
     */
    fun packToolOptions(names: List<String>?): List<LocalToolOption>? = names?.mapNotNull { name ->
        if (FloorGuardians.resolvesTool(name)) FloorGuardians.decodeTool(name) else null
    }

    /** Slug-derived stable ids: the same guardian gets the same Uuid on every device,
     *  so a re-seed after a version bump matches the old row by name and a test can
     *  pin the list without random ids. */
    fun assistantId(slug: String): Uuid {
        val bytes = java.util.UUID.nameUUIDFromBytes("nazarick-assistant:$slug".toByteArray())
        return Uuid.parse(bytes.toString())
    }

    fun assistantId(spec: GuardianSeedSpec): Uuid = assistantId(spec.name.lowercase())

    /** The portrait asset, addressed the way Coil addresses APK assets (the same
     *  scheme `AIIcon` uses for the bundled icons). */
    fun avatarOf(portrait: String?): Avatar =
        if (portrait == null) Avatar.Dummy
        else Avatar.Image("file:///android_asset/$portrait")

    fun assistantOf(spec: GuardianSeedSpec): Assistant {
        val allow = packToolOptions(spec.tools)
        return Assistant(
            id = assistantId(spec.name.lowercase()),
            name = spec.name.trim(),
            avatar = avatarOf(spec.portrait),
            useAssistantAvatar = spec.portrait != null,
            systemPrompt = spec.prompt,
            // The pack's "what to expect" line, shown under the name in the assistant lists
            // (owner request v0.7). Was silently dropped before the field existed.
            description = spec.description,
            // Same kit as the defaults: the guardian persona rides on the agent skills,
            // it does not replace them.
            enabledSkills = setOf("agent-core") + DEFAULT_AUTO_ENABLED_SKILLS,
            // v0.8 curation: the pack's grants are the hard ceiling of this assistant's
            // catalogue (see Assistant.toolAllowlist). localTools rides along so the GATED
            // groups the pack grants (Termux, Telegram, …) count as "user switched on" —
            // the gate still refuses them until the external prerequisite exists; non-gated
            // entries in there are inert by LocalToolGates' redefinition.
            localTools = allow ?: listOf(LocalToolOption.TimeInfo),
            toolAllowlist = allow,
            // LC-2: the id-level ceiling, the same table on every device. Group grants decide
            // which local groups exist; this decides which tools leave the harness.
            allowedToolNames = GuardianToolRoster.toolIdsFor(spec.name),
        )
    }

    /**
     * Refresh [current] rows that belong to the pack (id-derived match) with the pack's
     * descriptions. Pure, and deliberately description-only: prompts and every other field
     * are the user's to edit, the subtitle is pack content. v1 seeds shipped blank rows —
     * the SEED_VERSION 2 bump routes existing installs through here.
     */
    fun withPackDescriptions(current: List<Assistant>, pack: List<GuardianSeedSpec>): List<Assistant> {
        val byId = pack.associateBy { assistantId(it) }
        return current.map { a ->
            val spec = byId[a.id]
            if (spec != null && a.description != spec.description) a.copy(description = spec.description) else a
        }
    }

    /**
     * Fill [Assistant.toolAllowlist] on pack-owned rows that have none (v0.8 upgrade path,
     * companion to the SEED_VERSION 3 bump). Deliberately allowlist-only and null-only: prompts,
     * skills and every other field stay the user's; a guardian whose surface the user already
     * curated (allowlist set, including an owned-empty one) is never touched. [withPackDescriptions]
     * owns the description, this owns the grant — one field per function so the two refreshes
     * cannot argue over a row.
     */
    fun withPackToolGrants(current: List<Assistant>, pack: List<GuardianSeedSpec>): List<Assistant> {
        val byId = pack.associateBy { assistantId(it) }
        return current.map { a ->
            val spec = byId[a.id] ?: return@map a
            if (a.toolAllowlist != null) return@map a
            val allow = packToolOptions(spec.tools) ?: return@map a
            a.copy(toolAllowlist = allow, localTools = allow)
        }
    }

    /**
     * Puts [Assistant.allowedToolNames] on the pack's own rows (LC-2 upgrade path, companion of
     * the SEED_VERSION 4 bump). Pack rows are overwritten rather than filled-if-null because the
     * roster is a reviewed constant, not a default the pack guessed at; a guardian the user renamed
     * out of the pack stops being a floor guard and keeps whatever surface they chose.
     */
    fun withPackToolIds(current: List<Assistant>, pack: List<GuardianSeedSpec>): List<Assistant> {
        val byId = pack.associateBy { assistantId(it) }
        return current.map { a ->
            val spec = byId[a.id] ?: return@map a
            val ids = GuardianToolRoster.toolIdsFor(spec.name) ?: return@map a
            if (a.allowedToolNames == ids) a else a.copy(allowedToolNames = ids)
        }
    }

    /**
     * Guardians missing from [current]. Matching is by trimmed name, case-insensitive —
     * the same rule [FloorGuardians.plan] uses for the sub-agent install, so both halves
     * of the pack agree on what "already exists" means. A guardian whose name collides
     * with a *default* assistant is skipped rather than duplicating a card the user made.
     */
    fun plan(current: List<Assistant>, pack: List<GuardianSeedSpec>): GuardianSeedPlan {
        val existingNames = current.map { it.name.trim().lowercase() }.toSet()
        val inserts = mutableListOf<Assistant>()
        val skipped = mutableListOf<String>()
        var kept = 0
        for (spec in pack) {
            val name = spec.name.trim()
            if (name.isEmpty()) continue
            if (name.lowercase() in existingNames) {
                kept++
                continue
            }
            if (DEFAULT_ASSISTANTS_IDS.any { it == assistantId(spec) }) {
                // ids are slug-derived and defaults are random — unreachable today,
                // but a future hand-picked id must not silently overwrite a user card.
                skipped += name
                continue
            }
            inserts += assistantOf(spec)
        }
        return GuardianSeedPlan(inserts = inserts, kept = kept, skippedNames = skipped)
    }
}

/**
 * Impure part: read the pack text, plan against current settings, write once.
 *
 * The version is re-checked INSIDE [SettingsStore.update] — the store serializes
 * `update` transactions against itself, so two racing boot paths cannot both seed,
 * and a seed landing beside e.g. a theme change replays cleanly instead of clobbering.
 * `NonCancellable` belongs to the caller: boot must bound the asset read, not the
 * write. A failed seed costs nothing — the marker stays unwritten and the next
 * launch tries again.
 */
suspend fun seedGuardianAssistants(
    store: me.rerere.rikkahub.data.datastore.SettingsStore,
    assetText: String,
    version: Int = GuardianSeed.SEED_VERSION,
): Boolean {
    val current = store.settingsFlow.first()
    if (current.guardiansSeedVersion >= version) return false
    val pack = runCatching { FloorGuardians.parse(assetText) }
        .getOrNull()
        ?.guardians
        ?.map { GuardianSeedSpec(it.name, it.description, it.prompt, it.portrait, it.tools) }
        ?: return false
    val plan = GuardianSeed.plan(current.assistants, pack)
    store.update { settings ->
        if (settings.guardiansSeedVersion >= version) settings
        else settings.copy(
            assistants = GuardianSeed.withPackToolIds(
                GuardianSeed.withPackToolGrants(
                    GuardianSeed.withPackDescriptions(settings.assistants, pack),
                    pack,
                ),
                pack,
            ) + plan.inserts,
            guardiansSeedVersion = version,
        )
    }
    return plan.inserts.isNotEmpty()
}
