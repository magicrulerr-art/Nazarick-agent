package me.rerere.llamacpp

import java.io.File

/**
 * US-007: engine-pack fetch pipeline, transport-agnostic core.
 *
 * The production transport lives in :app (ktor + Range resume, the same client the model
 * downloader uses); this module owns the CONTRACT and the safety path, so the ordering the
 * AC names runs on the JVM with a fake transport:
 *   1. resumable fetch into `<root>/engine-packs/.staging/<id>-<version>.part`;
 *   2. unpack (zip layout is flat per pack v1) into the staging dir, with manifest.json and
 *      .sig written from the release assets we already hold;
 *   3. atomic rename into `engine-packs/<id>-<version>/`;
 *   4. the FULL registry gate (canonical bytes + Ed25519 + per-file size/sha) runs on the
 *      installed bytes; a decline rolls the directory back and restores any previous pack,
 *      so a bad download can never take the runtime away;
 *   5. same-or-lower version is refused before the transport is even touched: packs never
 *      silently downgrade.
 * No android.* imports: every outcome travels in [Outcome] and the CALLER logs.
 */
class EnginePackDownloader(
    private val root: File,
    /** Download url to bytes, resumably: called with the number of bytes already present in
     *  `partFile`; implementations must continue from that offset. Returns total size. */
    private val fetch: (url: String, partFile: File, resumeFrom: Long) -> Long,
    /** Unpack the fetched .part zip into `dest`. Production = ZipFile in :app; JVM tests
     *  inject a fake. */
    private val unpack: (partFile: File, dest: File) -> Unit,
) {
    sealed interface Outcome {
        data class Installed(val pack: EnginePackRegistry.EnginePack) : Outcome
        data class RefusedDowngrade(val installedVersion: Int, val candidateVersion: Int) : Outcome
        data class Failed(val stage: String, val detail: String) : Outcome
    }

    private fun packsDir() = File(root, "engine-packs")
    private fun stagingDir() = File(packsDir(), ".staging")

    /** Fetch + verify + install one pack whose signed manifest bytes we already hold (the
     *  release carries manifest.json and .sig as separate assets; see packtools). */
    fun installFrom(manifestBytes: ByteArray, sigBytes: ByteArray, zipUrl: String): Outcome {
        val manifest = try {
            EnginePackRegistry.parseManifest(manifestBytes.decodeToString())
        } catch (e: Throwable) {
            return Outcome.Failed("manifest", e.message ?: "unparseable")
        }
        val dirName = manifest.id + "-" + manifest.version
        val current = highestInstalled(manifest.id)
        if (current != null && current >= manifest.version) {
            return Outcome.RefusedDowngrade(current, manifest.version)
        }
        packsDir().mkdirs()
        stagingDir().mkdirs()
        val part = File(stagingDir(), dirName + ".part")
        val want = manifest.files.sumOf { it.second }
        val got = try {
            fetch(zipUrl, part, part.length())
        } catch (e: Throwable) {
            return Outcome.Failed("fetch", e.message ?: "transport error")
        }
        if (got != want) {
            // pack zip carries manifest+libs, so total > sum(libs) is normal; only a
            // truncated transfer (< sum) is provably wrong here. The per-file gate decides.
            if (got < want) return Outcome.Failed("fetch", "short transfer: got $got want $want")
        }
        val stageDir = File(stagingDir(), dirName)
        stageDir.deleteRecursively()
        stageDir.mkdirs()
        try {
            unpack(part, stageDir)
        } catch (e: Throwable) {
            return Outcome.Failed("unpack", e.message ?: "corrupt zip")
        }
        part.delete()
        File(stageDir, "manifest.json").writeBytes(manifestBytes)
        File(stageDir, ".sig").writeBytes(sigBytes)
        val target = File(packsDir(), dirName)
        val rotated = target.exists()
        if (rotated) {
            // keep the old pack until the NEW one has loaded (rollback material): move aside.
            val keep = File(packsDir(), dirName + ".prev")
            keep.deleteRecursively()
            if (!target.renameTo(keep)) return Outcome.Failed("rotate", "cannot move old pack aside")
        }
        if (!stageDir.renameTo(target)) return Outcome.Failed("stage", "atomic rename failed")
        return when (val res = EnginePackRegistry.loadPack(target)) {
            is EnginePackRegistry.Result.Accepted -> {
                File(packsDir(), dirName + ".prev").deleteRecursively()
                Outcome.Installed(res.pack)
            }
            is EnginePackRegistry.Result.Declined -> {
                // rollback: drop the failed install, restore the previous pack if moved aside
                val back = File(packsDir(), dirName + ".prev")
                target.deleteRecursively()
                if (rotated) back.renameTo(target)
                Outcome.Failed("verify", res.decline.reason.name)
            }
        }
    }

    /** Highest installed version for `id` (gate runs later; naming convention only). */
    fun highestInstalled(id: String): Int? =
        packsDir().listFiles { f -> f.isDirectory }?.mapNotNull { d ->
            val prefix = id + "-"
            if (d.name.startsWith(prefix) && !d.name.endsWith(".prev")) {
                d.name.substring(prefix.length).toIntOrNull()
            } else null
        }?.maxOrNull()
}
