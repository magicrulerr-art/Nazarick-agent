package me.rerere.sdcpp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import me.rerere.locallm.ModelInstall
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Gets a diffusion GGUF onto the phone and keeps it there.
 *
 * Deliberately not the `local-llm` download machinery: that one is keyed on `LocalRuntime`,
 * whose `runtimeForExtension("gguf")` already belongs to llama.cpp and cannot mean two things.
 * sd.cpp shares the *directory* convention (`local-models/sdcpp/`, beside `llamacpp/`) and
 * nothing else, which is a cheaper contract than teaching an enum that two engines read one
 * file extension.
 *
 * Two rules worth stating, both learned the fleet way on the llama.cpp side of this repo:
 *
 *  - **Download to `.part`, rename on success.** An interrupted 1.5 GB transfer otherwise
 *    presents itself as an installed model, and sd.cpp's loader fails somewhere in the middle
 *    of it with an error that points at the model rather than at the network.
 *  - **Reject a truncated body by length, not by magic bytes.** GGUF's header is only
 *    `GGUF` + version + a tensor count; every SD1.5 quantisation shares it, so the header says
 *    nothing about completeness. `Content-Length` does, when the server sends one.
 */
class SdModelInstaller(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)      // a phone on LTE stalls mid-transfer; this is a download
        .followRedirects(true)                   // HuggingFace resolves through CDN redirects
        .build(),
) {

    /** `local-models/sdcpp/`, created on demand. */
    fun runtimeDir(): File =
        File(ModelInstall.localModelsDir(context), SdCppCatalog.RUNTIME_DIRNAME).apply { mkdirs() }

    fun fileFor(entry: SdModelEntry): File = File(runtimeDir(), entry.fileName)

    /** Present and whole enough to load. Size alone is the check [expectedBytes] allows. */
    fun isInstalled(entry: SdModelEntry): Boolean {
        val f = fileFor(entry)
        return f.isFile && f.length() > 0
    }

    fun installedEntries(): List<SdModelEntry> = SdCppCatalog.models.filter { isInstalled(it) }

    /**
     * Catalog entries found by *scanning* the runtime directory rather than by probing the
     * catalog's own file names — a GGUF the user pushed in with adb counts, as does one the
     * download completed but whose registration write never landed (AURA-014, bug #1).
     * Zero-length and `.part` files are skipped: they are interrupted transfers, not models.
     */
    fun libraryEntries(): List<SdModelEntry> =
        runtimeDir()
            .listFiles { f -> f.isFile && f.length() > 0 && f.name.endsWith(".gguf", ignoreCase = true) }
            ?.mapNotNull { SdCppCatalog.entryFor(it.name) }
            ?.sortedBy { it.id }
            ?: emptyList()

    /** Delete a model's weights. The caller owns removing it from the provider's model list. */
    suspend fun delete(entry: SdModelEntry): Boolean = withContext(Dispatchers.IO) {
        // No bare `return` in here: withContext's block is crossinline, which forbids a
        // non-local return (CI: "'return' is prohibited here"). The last expression is the
        // result; a missing file counts as deleted, which is what the caller's idempotency needs.
        val file = fileFor(entry)
        !file.exists() || file.delete()
    }

    /**
     * Fetch [entry] into the runtime directory.
     *
     * @param onProgress bytes so far, total bytes (`-1` when the server withheld
     *   `Content-Length`, in which case [SdModelEntry.sizeBytes] is the honest denominator).
     * @throws IOException on transport failure, a non-2xx response, or a body shorter than the
     *   catalog's recorded size — the last being the one that would otherwise ship a
     *   half-file that sd.cpp then misreads as a corrupt model.
     */
    suspend fun install(
        entry: SdModelEntry,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val dir = runtimeDir()
        val target = File(dir, entry.fileName)
        if (target.isFile && target.length() == entry.sizeBytes) return@withContext target

        val request = Request.Builder().url(entry.url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("sd.cpp download failed: HTTP ${response.code} for ${entry.url}")
            }
            val body = response.body ?: throw IOException("sd.cpp download returned no body")
            // Trust the catalog's recorded size over Content-Length when the two disagree:
            // the catalog number is asserted by a test against the HF API, the header is
            // whatever a CDN in front of it decided to say today.
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
                val written = staging.length()
                if (entry.sizeBytes > 0 && written != entry.sizeBytes) {
                    // One branch for both directions: "more than the catalog" is as much a
                    // lie as "less", and neither should be handed to the loader.
                    throw IOException(
                        "sd.cpp download came back $written bytes where the catalog records " +
                            "${entry.sizeBytes} — delete the .part and retry",
                    )
                }
                if (!staging.renameTo(target)) {
                    throw IOException("sd.cpp could not move ${staging.name} into place")
                }
                target
            } catch (e: Exception) {
                // Leave nothing half-written behind; the next attempt starts clean.
                staging.delete()
                throw e
            }
        }
    }

    /**
     * Register a GGUF the user copied onto the device themselves (adb, Files app, a shared
     * link). Returns null when the name is not in the catalog — sd.cpp can run other files in
     * principle, but the app cannot size or parameterise one it has no entry for.
     */
    fun adopt(fileName: String): SdModelEntry? {
        val entry = SdCppCatalog.entryFor(fileName) ?: return null
        return if (isInstalled(entry)) entry else null
    }
}
