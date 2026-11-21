package me.rerere.rikkahub.data.enginepack

import android.util.Log
import me.rerere.llamacpp.EnginePackDownloader
import me.rerere.llamacpp.EnginePackRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * US-007: production transport for the engine-pack pipeline. The ordering and safety rules
 * live in [EnginePackDownloader] (JVM-tested); this object supplies the two lambdas it asks
 * for — resumable HTTP and zip unpack — and pins the release the app trusts.
 *
 * Pinned tag is a compile-time constant on purpose: pack adoption is a code decision with a
 * diff, not a runtime redirect some future release page can quietly repoint. GitHub release
 * assets 302 to object storage, which HttpURLConnection follows for GET.
 */
object EnginePackReleases {
    const val PACK_RELEASE_TAG = "enginepack-nazarick-engine-llamacpp-1"
    private const val BASE = "https://github.com/magicrulerr-art/Nazarick-agent/releases/download/"
    private const val TAG = "nazarick-pack"

    fun fetchBytes(url: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code for $url")
            return conn.inputStream.use { input ->
                ByteArrayOutputStream().also { out -> input.copyTo(out) }.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Resumable fetch per the downloader contract: continue from [resumeFrom] bytes. */
    private fun fetchTo(url: String, partFile: File, resumeFrom: Long): Long {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 120_000
            if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        try {
            val code = conn.responseCode
            if (code != 200 && code != 206) error("HTTP $code for $url")
            if (code == 200 && resumeFrom > 0) {
                partFile.delete() // server ignored Range: restart honestly, never append twice
            }
            val append = code == 206
            FileOutputStream(partFile, append).use { out -> conn.inputStream.copyTo(out) }
            return partFile.length()
        } finally {
            conn.disconnect()
        }
    }

    private fun unpackZip(partFile: File, dest: File) {
        ZipFile(partFile).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                val out = File(dest, File(entry.name).name) // flat per pack v1 layout
                FileOutputStream(out).use { o -> zip.getInputStream(entry).copyTo(o) }
            }
        }
    }

    /** Full install from the pinned release. Call off the main thread: multi-hundred-MB IO. */
    fun installFromPinnedRelease(root: File): EnginePackDownloader.Outcome {
        val manifest = fetchBytes(BASE + PACK_RELEASE_TAG + "/manifest.json")
        val sig = fetchBytes(BASE + PACK_RELEASE_TAG + "/pack.sig")
        val parsed = try {
            EnginePackRegistry.parseManifest(manifest.decodeToString())
        } catch (e: Throwable) {
            return EnginePackDownloader.Outcome.Failed("manifest", e.message ?: "unparseable")
        }
        val zipUrl = BASE + PACK_RELEASE_TAG + "/" + parsed.id + "-" + parsed.version + ".zip"
        Log.i(TAG, "packinstall: start tag=$PACK_RELEASE_TAG id=${parsed.id} v=${parsed.version}")
        val downloader = EnginePackDownloader(root, ::fetchTo, ::unpackZip)
        val outcome = downloader.installFrom(manifest, sig, zipUrl)
        Log.i(TAG, "packinstall: outcome " + outcome::class.simpleName)
        return outcome
    }
}
