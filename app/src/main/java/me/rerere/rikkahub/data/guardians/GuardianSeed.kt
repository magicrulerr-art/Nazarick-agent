package me.rerere.rikkahub.data.guardians

import kotlinx.coroutines.flow.first
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
 * them. The two default assistants already *are* Ainz (unnamed, locale renders the
 * name), so the tenth card would be a duplicate — the seed takes the Nine and lets
 * the defaults keep the master of the tomb.
 */
data class GuardianSeedSpec(
    val name: String,
    val description: String,
    val prompt: String,
    /** Asset path inside the APK, e.g. "guardians/portraits/albedo.jpg". */
    val portrait: String?,
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

    /** Bump only when the bundled pack changes and installs should receive it again. */
    const val SEED_VERSION: Int = 1

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

    fun assistantOf(spec: GuardianSeedSpec): Assistant = Assistant(
        id = assistantId(spec.name.lowercase()),
        name = spec.name.trim(),
        avatar = avatarOf(spec.portrait),
        useAssistantAvatar = spec.portrait != null,
        systemPrompt = spec.prompt,
        // Same kit as the defaults: the guardian persona rides on the agent skills,
        // it does not replace them.
        enabledSkills = setOf("agent-core") + DEFAULT_AUTO_ENABLED_SKILLS,
    )

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
        ?\.guardians
        ?.map { GuardianSeedSpec(it.name, it.description, it.prompt, it.portrait) }
        ?: return false
    val plan = GuardianSeed.plan(current.assistants, pack)
    store.update { settings ->
        if (settings.guardiansSeedVersion >= version) settings
        else settings.copy(
            assistants = settings.assistants + plan.inserts,
            guardiansSeedVersion = version,
        )
    }
    return plan.inserts.isNotEmpty()
}
