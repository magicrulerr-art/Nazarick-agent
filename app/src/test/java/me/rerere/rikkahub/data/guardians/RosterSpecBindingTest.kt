package me.rerere.rikkahub.data.guardians

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AURA-021 v1.0 (US-002, Albedo owed line 4c, review of 2026-11-14).
 *
 * The reviewed artifact is a markdown table in the floor record; the artifact the machine
 * enforces is [GuardianToolRoster.ROSTER]. Before this test nothing bound the two - all seven
 * existing roster tests assert invariants OF the Kotlin table, so a commit could rewrite a row
 * and stay green while the signed document kept saying something else. Albedo's phrase for it:
 * a signature would attach to "a document the build does not consult".
 *
 * Two assertions, both cheap:
 *  - the mirrored §1 table equals [GuardianToolRoster.ROSTER] row-for-row and cell-for-cell;
 *  - the mirror is byte-identical to the revision Albedo and Demiurge signed (sha256 pinned).
 *
 * CI runs only the scoped `:app` test filter (build.yml), so this class is named there as
 * well; a test nobody invokes binds nothing.
 */
class RosterSpecBindingTest {

    private fun specFile(): File {
        val candidates = listOf(
            File("src/test/resources/roster/spec-assistant-tool-allowlist-section1.md"),
            File("app/src/test/resources/roster/spec-assistant-tool-allowlist-section1.md"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("could not find the mirrored roster spec, tried: $candidates")
    }

    /** Rows of the §1 markdown table: assistant name -> granted ids, in document order. */
    private fun markdownRows(text: String): List<Pair<String, List<String>>> {
        val out = mutableListOf<Pair<String, List<String>>>()
        for (line in text.lineSequence()) {
            val row = line.trim()
            if (!row.startsWith("|")) continue
            val cells = row.trim('|').split('|').map { it.trim() }
            if (cells.size < 2) continue
            val name = cells.first()
            if (name == "Assistant" || name.isEmpty()) continue          // header
            if (name.all { it == '-' || it == ':' }) continue            // separator
            out += name to Regex("`([a-z0-9_]+)`").findAll(cells[1]).map { it.groupValues[1] }.toList()
        }
        return out
    }

    @Test
    fun theRatifiedMarkdownTableAndTheEnforcedRosterAreTheSameNineRows() {
        val ratified = markdownRows(specFile().readText())
        assertEquals(
            "the mirrored §1 table must carry exactly the nine reviewed rows",
            GuardianToolRoster.ROSTER.size, ratified.size,
        )
        for ((name, ids) in ratified) {
            val enforced = GuardianToolRoster.toolIdsFor(name)
                ?: error("row '$name' exists in the ratified spec but not in the enforced roster")
            assertEquals("row '$name' drifted between the signed spec and the enforced table", ids, enforced)
        }
    }

    @Test
    fun theMirrorIsTheRevisionTheReviewersSigned() {
        // Line endings are normalised before hashing so a checkout with core.autocrlf on a Windows
        // runner cannot break the pin by itself; content is what is pinned, not EOL style.
        val normalised = specFile().readBytes().let { bytes ->
            String(bytes, Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(normalised).joinToString("") { "%02x".format(it) }
        // Pin covers the mirror as committed: comment header + the §1 table slice, LF endings.
        // 91be6c24ab4a4eba2244c38e6aabe47ec97766f3fb0c9f5f068301f372ab455f
        // Upstream, for reference: the reviewed floor record is 28,579 bytes / sha256
        // 93e72a29537487732fa7fd85db2143222651d8fc2a0274c673ababc6dda266c8, at
        // NAZARICK\floors\sixth_Aura\spec-assistant-tool-allowlist.md. Re-reviewing the roster means
        // re-mirroring section 1 and moving this constant in the same commit - that is the binding.
        assertEquals(
            "mirrored roster revision changed without a re-review",
            "91be6c24ab4a4eba2244c38e6aabe47ec97766f3fb0c9f5f068301f372ab455f", digest,
        )
    }
}
