package me.rerere.llamacpp

import android.util.Log
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Locale

/**
 * Engine pack v1 registry (US-006).
 *
 * Scans `<root>/engine-packs/<id>-<version>/` directories produced by the enginepack CLI
 * (tools\mob\enginepack), gates each on signature + manifest-canonical-bytes + per-file
 * size/sha256, and exposes only packs that passed. A declined pack is NEVER loadable:
 * scan returns it with a named reason and the caller falls back to the engine compiled
 * into the APK. No silent tolerance anywhere - a pack we cannot verify is a pack we refuse.
 *
 * Manifest parsing is hand-rolled against the pack-v1 schema on purpose: it keeps this
 * module dependency-free and makes [canonical] byte-identical to the Python tool's
 * json.dumps(sort_keys=True, separators=(",",":"), ensure_ascii=False) + LF (pinned by tests).
 */
object EnginePackRegistry {
    const val TAG = "nazarick-engine"

    /** Test seam + app wiring: 64-hex Ed25519 public key (raw, X25519-style seed form NOT used). */
    @Volatile
    /** Test/backdoor override: when non-null ONLY this key is trusted (unit tests use it).
     *  Production trust is EMBEDDED_KEYS below — keys live in code because trust cannot
     *  travel inside the signed artifact itself. */
    var pubKeyHex: String? = null

    /** Embedded trust list, named for logcat honesty. Production key (owner custody) lands
     *  here as a third entry; rotation = bump min_app_vc, per packtools README. */
    private val EMBEDDED_KEYS = listOf(
        "us005-test" to "18fc2b94bd2fda07c1654a9c609820b2b67da868e1def2b42cc773ea7cb644b4",
        "us009-test2" to "1c3b5360e36c2c1790684adce92d7b7daaaa1f76be4bfe4db76f4de215d12724",
    )

    /** App wiring (call once at startup, e.g. EngineBootstrap): remembers the dirs the
     *  pack lanes read. Both are plain fields so JVM tests can drive the registry
     *  without Robolectric. */
    @Volatile
    var appRootOverride: java.io.File? = null

    @Volatile
    var nativeLibDir: String? = null

    fun init(context: android.content.Context) {
        appRootOverride = context.filesDir.parentFile ?: context.filesDir
        nativeLibDir = context.applicationInfo.nativeLibraryDir
        appRootOverride?.let { root ->
            val (packs, declined) = scan(root)
            Log.i(TAG, "enginepack: init packs=" + packs.size + " declined=" + declined.size)
        }
    }

    fun appRoot(): java.io.File? = appRootOverride

    /** OpenCL lane gate: the stub must sit in this install's nativeLibraryDir, where
     *  AGP extracts jniLibs and the linker resolves it by soname. Unknown (no init,
     *  no seam) is a decline: we never offer a lane we cannot see. */
    fun openclStubPresent(): Boolean = try {
        val dir = nativeLibDir ?: return false
        java.io.File(dir, "libggml-opencl.so").isFile
    } catch (_: Throwable) {
        false
    }

    enum class Backend { CPU, OPENCL, NPU }

    data class EnginePack(
        val id: String,
        val version: Int,
        val abi: String,
        val backends: Set<Backend>,
        val minApi: Int,
        val enginePin: String,
        val dir: File,
        val manifestBytes: ByteArray,
    ) {
        fun libFor(backend: Backend): File? =
            if (backend in backends) File(dir, "libllama.so") else null
    }

    data class PackDecline(val dir: File, val reason: DeclineReason, val detail: String = "")

    enum class DeclineReason {
        NO_MANIFEST,
        NOT_CANONICAL,
        BAD_JSON,
        BAD_SIGNATURE,
        NO_PUBLIC_KEY,
        FILE_MISSING,
        FILE_SIZE,
        FILE_HASH,
    }

    /** Parse + gate one pack directory. Pure with respect to the filesystem it reads. */
    fun loadPack(dir: File): Result {
        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.isFile) return Result.Declined(PackDecline(dir, DeclineReason.NO_MANIFEST))
        val bytes = manifestFile.readBytes()
        val manifest = try {
            parseManifest(String(bytes, Charsets.UTF_8))
        } catch (e: Throwable) {
            return Result.Declined(PackDecline(dir, DeclineReason.BAD_JSON, e.message ?: ""))
        }
        // The signature is over the exact bytes on disk; require they are the canonical form
        // so "signed text != parsed text" games cannot open.
        val canon = try {
            canonical(manifest)
        } catch (e: IllegalArgumentException) {
            return Result.Declined(PackDecline(dir, DeclineReason.NOT_CANONICAL, e.message ?: ""))
        }
        if (!bytes.contentEquals(canon)) {
            return Result.Declined(PackDecline(dir, DeclineReason.NOT_CANONICAL))
        }
        val candidates = pubKeyHex?.let { listOf("override" to it) } ?: EMBEDDED_KEYS
        if (candidates.isEmpty()) return Result.Declined(PackDecline(dir, DeclineReason.NO_PUBLIC_KEY))
        val sig = File(dir, ".sig")
        if (!sig.isFile) return Result.Declined(PackDecline(dir, DeclineReason.BAD_SIGNATURE, "missing .sig"))
        val sigBytes = sig.readBytes()
        val signer = candidates.firstOrNull { verifyEd25519(it.second, bytes, sigBytes) }
        if (signer == null) {
            return Result.Declined(PackDecline(dir, DeclineReason.BAD_SIGNATURE))
        }
        Log.i(TAG, "enginepack: signed-by " + signer.first + " dir=" + dir.name)
        for ((path, size, sha) in manifest.files) {
            val f = File(dir, path)
            if (!f.isFile) return Result.Declined(PackDecline(dir, DeclineReason.FILE_MISSING, path))
            if (f.length() != size) return Result.Declined(PackDecline(dir, DeclineReason.FILE_SIZE, path))
            if (!sha256Hex(f).equals(sha, ignoreCase = true)) {
                return Result.Declined(PackDecline(dir, DeclineReason.FILE_HASH, path))
            }
        }
        return Result.Accepted(
            EnginePack(manifest.id, manifest.version, manifest.abi, manifest.backends.toSet(),
                manifest.minApi, manifest.enginePin, dir, bytes),
        )
    }

    sealed interface Result {
        data class Accepted(val pack: EnginePack) : Result
        data class Declined(val decline: PackDecline) : Result
    }

    /** Scan the standard layout; returns verified packs (highest version first per id). */
    fun scan(root: File): Pair<List<EnginePack>, List<PackDecline>> {
        val base = File(root, "engine-packs")
        if (!base.isDirectory) return emptyList<EnginePack>() to emptyList()
        val accepted = mutableListOf<EnginePack>()
        val declined = mutableListOf<PackDecline>()
        base.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
            when (val r = loadPack(dir)) {
                is Result.Accepted -> accepted += r.pack
                is Result.Declined -> declined += r.decline
            }
        }
        accepted.sortWith(compareBy<EnginePack> { it.id }.thenByDescending { it.version })
        return accepted to declined
    }

    /**
     * Backend selection: NPU only when a verified pack carries it (Phase-1 stance: absence is
     * a graceful decline, never a crash), else OpenCL when the device has the stub and a pack
     * (or the built-in engine) can serve it, else CPU. `pack` is the winning pack's label,
     * "builtin" when no pack provides the chosen backend.
     */
    fun selectBackend(
        packs: List<EnginePack>,
        openclStubPresent: Boolean,
        npuRequested: Boolean,
    ): Pair<Backend, String?> {
        if (npuRequested) packs.firstOrNull { Backend.NPU in it.backends }?.let {
            return Backend.NPU to it.id
        }
        val opencl = packs.firstOrNull { Backend.OPENCL in it.backends }
        if (opencl != null && openclStubPresent) return Backend.OPENCL to opencl.id
        val cpu = packs.firstOrNull { Backend.CPU in it.backends }
        if (cpu != null) return Backend.CPU to cpu.id
        return Backend.CPU to null
    }

    fun selectionLine(backend: Backend, packId: String?): String =
        "backend=" + backend.name.lowercase(Locale.US) +
            " pack=" + (packId ?: "builtin")

    /** No wired root (tests, early boot): honest builtin selection, single log line. */
    fun logSelection(
        root: java.io.File?,
        openclStubPresent: Boolean,
        npuRequested: Boolean,
        packsEnabled: Boolean = true,
    ): Pair<Backend, String?> {
        if (root == null || !packsEnabled) {
            if (!packsEnabled) Log.i(TAG, "enginepack: lane off (engine_pack=off), built-in engine only")
            val b = if (openclStubPresent) Backend.OPENCL else Backend.CPU
            Log.i(TAG, selectionLine(b, null))
            return b to null
        }
        return scanAndLog(root, openclStubPresent, npuRequested)
    }

    /** Scan + log in one honest line per model load; declines are logged by name. */
    private fun scanAndLog(root: File, openclStubPresent: Boolean, npuRequested: Boolean): Pair<Backend, String?> {
        val (packs, declined) = scan(root)
        declined.forEach {
            Log.w(TAG, "enginepack: decline id=" + it.dir.name +
                " reason=" + it.reason.name.lowercase(Locale.US) +
                if (it.detail.isNotEmpty()) " detail=" + it.detail else "")
        }
        val (backend, packId) = selectBackend(packs, openclStubPresent, npuRequested)
        Log.i(TAG, selectionLine(backend, packId))
        return backend to packId
    }

    // ---- canonical bytes ----

    data class ParsedManifest(
        val id: String,
        val version: Int,
        val abi: String,
        /** ORDER-PRESERVED: canonical bytes must reproduce the file byte-for-byte; Python
         *  json.dumps keeps list order, so re-sorting here would break real packs. */
        val backends: List<Backend>,
        val minApi: Int,
        val enginePin: String,
        val files: List<Triple<String, Long, String>>,
    )

    fun canonical(m: ParsedManifest): ByteArray {
        val sb = StringBuilder(256)
        sb.append('{')
        sb.append("\"abi\":\"").append(m.abi).append("\",")
        sb.append("\"backends\":[")
        sb.append(m.backends.joinToString(",") { "\"" + it.name.lowercase(Locale.US) + "\"" })
        sb.append("],\"engine_pin\":\"").append(m.enginePin).append("\",")
        sb.append("\"files\":[")
        sb.append(m.files.sortedBy { it.first }.joinToString(",") { (p, s, h) ->
            "{\"path\":\"" + p + "\",\"sha256\":\"" + h + "\",\"size\":" + s + "}"
        })
        sb.append("],\"id\":\"").append(m.id).append("\",")
        sb.append("\"min_api\":").append(m.minApi).append(',')
        sb.append("\"version\":").append(m.version)
        sb.append('}')
        sb.append('\n')
        val s = sb.toString()
        // pack v1 fields are ASCII-safe; reject anything that would need JSON escaping
        require(!s.any { it.code < 0x20 && it != '\n' || it == '\\' }) { "manifest needs escaping: v1 fields must be plain" }
        return s.toByteArray(Charsets.UTF_8)
    }

    // ---- minimal JSON (object of scalars/arrays only, pack-v1 schema) ----

    internal fun parseManifest(text: String): ParsedManifest {
        val obj = JsonObjectReader(text).readObject()
        val files = (obj["files"] as? List<*>).orEmpty().map { e ->
            val fo = e as Map<*, *>
            Triple(fo["path"] as String, (fo["size"] as Number).toLong(), fo["sha256"] as String)
        }
        val backends = (obj["backends"] as? List<*>).orEmpty().map {
            when ((it as String).lowercase(Locale.US)) {
                "cpu" -> Backend.CPU
                "opencl" -> Backend.OPENCL
                "npu" -> Backend.NPU
                else -> error("unknown backend: $it")
            }
        }
        return ParsedManifest(
            id = obj["id"] as String,
            version = (obj["version"] as Number).toInt(),
            abi = obj["abi"] as String,
            backends = backends,
            minApi = (obj["min_api"] as Number).toInt(),
            enginePin = obj["engine_pin"] as String,
            files = files,
        )
    }

    private class JsonObjectReader(private val s: String) {
        private var i = 0
        fun readObject(): Map<String, Any?> = readObjAt()

        private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun expect(c: Char) { skipWs(); require(i < s.length && s[i] == c) { "want $c at $i" }; i++ }

        private fun readObjAt(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                skipWs()
                val key = readString()
                expect(':')
                out[key] = readValue()
                skipWs()
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> error("bad char ${s[i]} at $i")
                }
            }
        }

        private fun readValue(): Any? {
            skipWs()
            return when (val c = s[i]) {
                '"' -> readString()
                '{' -> readObjAt()
                '[' -> {
                    expect('[')
                    val arr = mutableListOf<Any?>()
                    skipWs()
                    if (s[i] == ']') { i++; return arr }
                    while (true) {
                        arr += readValue()
                        skipWs()
                        when (s[i]) {
                            ',' -> i++
                            ']' -> { i++; return arr }
                            else -> error("bad char ${s[i]} at $i")
                        }
                    }
                }
                't' -> { require(s.startsWith("true", i)); i += 4; true }
                'f' -> { require(s.startsWith("false", i)); i += 5; false }
                'n' -> { require(s.startsWith("null", i)); i += 4; null }
                else -> {
                    require(c == '-' || c.isDigit()) { "bad value at $i" }
                    val start = i
                    if (s[i] == '-') i++
                    while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
                    s.substring(start, i).toDouble()
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    sb.append(when (s[i]) { '"' -> '"'; '\\' -> '\\'; 'n' -> '\n'; else -> error("esc ${s[i]}") })
                } else sb.append(s[i])
                i++
            }
            i++
            return sb.toString()
        }
    }

    // ---- crypto ----

    private fun sha256Hex(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Raw 32-byte Ed25519 public key -> X509 SubjectPublicKeyInfo wrapper (DER). */
    private const val ED25519_SPKI_PREFIX = "302a300506032b6570032100"

    internal fun verifyEd25519(pubHex: String, message: ByteArray, signature: ByteArray): Boolean = try {
        val der = hex(ED25519_SPKI_PREFIX + pubHex)
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(der))
        val sig = Signature.getInstance("Ed25519")
        sig.initVerify(key)
        sig.update(message)
        sig.verify(signature)
    } catch (t: Throwable) {
        Log.w(TAG, "enginepack: ed25519 verify error: ${t.javaClass.simpleName}: ${t.message}")
        false
    }

    internal fun hex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
