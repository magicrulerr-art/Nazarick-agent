package me.rerere.localdream

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.locallm.ModelInstall
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Gets a QNN/MNN model pack onto the phone: one ~1 GB zip in, one extracted directory out.
 *
 * Same two rules as `SdModelInstaller`, both re-learned the fleet way on the llama.cpp side:
 *  - **Download to `.part`, rename on success** — an interrupted gigabyte must not present
 *    itself as an installed model.
 *  - **Reject by exact byte count** — the catalog's size is test-asserted against the HF API;
 *    neither a short nor a long body is trustworthy.
 *
 * Extraction adds one rule of its own: **zip entries land under the target directory or not
 * at all** — the classic `../` traversal is refused per-entry, because these archives come
 * off a public CDN into the app's private storage where a stray write is invisible grief.
 * The archive's own internal folder (`ChilloutMix/…`) is kept and the engine's
 * `resolveModelDir` descends into it — no flattening, fewer surprises when the upstream
 * layout changes.
 */
class QnnModelInstaller(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true) // HuggingFace resolves through CDN redirects
        .build(),
) {

    /** `local-models/localdream/`, created on demand. */
    fun runtimeDir(): File =
        File(ModelInstall.localModelsDir(context), LocalDreamCatalog.RUNTIME_DIRNAME).apply { mkdirs() }

    fun dirFor(entry: QnnModelEntry): File = File(runtimeDir(), entry.id)

    /** Extracted directory containing the pack's marker file (at any sane depth). */
    fun isInstalled(entry: QnnModelEntry): Boolean =
        LocalDreamEngine.resolveModelDir(dirFor(entry), isCpu = entry.packKind == "mnn") != null

    fun installedEntries(): List<QnnModelEntry> = LocalDreamCatalog.models.filter { isInstalled(it) }

    /** Delete a pack's extracted directory and any leftover zip. Idempotent. */
    suspend fun delete(entry: QnnModelEntry): Boolean = withContext(Dispatchers.IO) {
        val dirOk = !dirFor(entry).exists() || dirFor(entry).deleteRecursively()
        val zipOk = !zipFile(entry).exists() || zipFile(entry).delete()
        dirOk && zipOk
    }

    private fun zipFile(entry: QnnModelEntry): File = File(runtimeDir(), entry.fileName)

    /**
     * Fetch and extract [entry]. Returns the directory holding the weights.
     *
     * @param onProgress bytes downloaded so far, total bytes (`-1` when the CDN withholds
     *   `Content-Length`; the catalog size is then the honest denominator).
     */
    suspend fun install(
        entry: QnnModelEntry,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val dir = runtimeDir()
        val target = dirFor(entry)
        if (isInstalled(entry)) return@withContext target

        val usable = dir.usableSpace
        // zip + extracted + a little air; refusing early beats dying at 97%.
        val needed = (entry.sizeBytes * 2.2).toLong()
        if (usable in 1 until needed) {
            throw IOException(
                "local-dream needs ~${needed / 1024 / 1024} MB free for ${entry.displayName} " +
                    "(zip + extracted); device reports ${usable / 1024 / 1024} MB",
            )
        }

        val request = Request.Builder().url(entry.url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("local-dream download failed: HTTP ${response.code} for ${entry.url}")
            }
            val body = response.body ?: throw IOException("local-dream download returned no body")
            val total = body.contentLength().takeIf { it > 0 } ?: entry.sizeBytes

            val staging = File(dir, entry.fileName + ".part")
            staging.delete()
            try {
                staging.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
                if (staging.length() != entry.sizeBytes) {
                    throw IOException(
                        "local-dream download came back ${staging.length()} bytes where the " +
                            "catalog records ${entry.sizeBytes} — delete the .part and retry",
                    )
                }
                val zip = File(dir, entry.fileName)
                zip.delete()
                if (!staging.renameTo(zip)) {
                    throw IOException("local-dream could not move ${staging.name} into place")
                }

                target.deleteRecursively()
                target.mkdirs()
                extract(zip, target)

                if (LocalDreamEngine.resolveModelDir(target, isCpu = entry.packKind == "mnn") == null) {
                    val marker = if (entry.packKind == "mnn") "unet.mnn" else "unet.bin"
                    throw IOException(
                        "extracted pack has no $marker under ${target.absolutePath} — " +
                            "the archive changed shape; this catalog entry needs an update",
                    )
                }
                zip.delete() // the extracted directory is the model now
                target
            } catch (e: Exception) {
                staging.delete()
                throw e
            }
        }
    }

    private fun extract(zip: File, target: File) {
        val canonicalTarget = target.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val next = zin.nextEntry ?: break
                val out = File(target, next.name)
                if (!out.canonicalFile.path.startsWith(canonicalTarget.path + File.separator)) {
                    throw IOException("zip entry escapes the model directory: ${next.name}")
                }
                if (next.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o -> zin.copyTo(o) }
                }
                zin.closeEntry()
            }
        }
    }
}
