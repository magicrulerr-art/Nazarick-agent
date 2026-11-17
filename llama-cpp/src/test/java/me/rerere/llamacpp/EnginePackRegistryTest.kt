package me.rerere.llamacpp

import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * US-006: engine-pack registry gates. Two evidence layers:
 *  - REAL bytes: manifest.json + pack.sig under test resources were produced by the Python
 *    enginepack CLI against the shipped v1.0 APK libs (cross-tool canonical-bytes proof).
 *  - synthetic fixture: tiny fake libs signed in-process, for the tamper matrix.
 */
class EnginePackRegistryTest {
    private fun resource(name: String): File =
        File(javaClass.classLoader.getResource("packv1/$name")!!.file)

    private fun signHex(kp: java.security.KeyPair, data: ByteArray): ByteArray {
        val s = Signature.getInstance("Ed25519")
        s.initSign(kp.private)
        s.update(data)
        return s.sign()
    }

    private fun pubHexOf(pub: java.security.PublicKey): String =
        pub.encoded.takeLast(32).joinToString("") { "%02x".format(it) }

    private fun syntheticPack(root: File, files: Map<String, ByteArray>, kp: java.security.KeyPair): File {
        val dir = File(root, "engine-packs/demo-engine-1")
        dir.mkdirs()
        files.forEach { (n, b) -> File(dir, n).writeBytes(b) }
        val sha = { b: ByteArray ->
            java.security.MessageDigest.getInstance("SHA-256").digest(b)
                .joinToString("") { "%02x".format(it) }
        }
        val manifest = EnginePackRegistry.ParsedManifest(
            id = "demo-engine", version = 1, abi = "arm64-v8a",
            backends = listOf(EnginePackRegistry.Backend.CPU, EnginePackRegistry.Backend.OPENCL),
            minApi = 26, enginePin = "abc",
            files = files.map { (n, b) -> Triple(n, b.size.toLong(), sha(b)) },
        )
        val bytes = EnginePackRegistry.canonical(manifest)
        File(dir, "manifest.json").writeBytes(bytes)
        File(dir, ".sig").writeBytes(signHex(kp, bytes))
        return dir
    }

    @Test
    fun `python-produced manifest bytes are byte-identical to canonical`() {
        val onDisk = resource("manifest.json").readBytes()
        val parsed = EnginePackRegistry.parseManifest(onDisk.decodeToString())
        assertEquals(String(onDisk), String(EnginePackRegistry.canonical(parsed)))
    }

    @Test
    fun `real pack signature verifies and tamper rejects`() {
        EnginePackRegistry.pubKeyHex = TEST_PUB_HEX
        val dir = kotlin.io.path.createTempDirectory("packv1-real").toFile()
        try {
            val pack = File(dir, "engine-packs/nazarick-engine-llamacpp-1").apply { mkdirs() }
            resource("manifest.json").copyTo(File(pack, "manifest.json"), overwrite = true)
            resource("pack.sig").copyTo(File(pack, ".sig"), overwrite = true)
            // files listed in the manifest are absent -> signature passes, file gate declines
            val declined = EnginePackRegistry.loadPack(pack)
            assertIs<EnginePackRegistry.Result.Declined>(declined)
            assertEquals(EnginePackRegistry.DeclineReason.FILE_MISSING, declined.decline.reason)
            // canonical byte gate: flip one char in manifest text, sig must now reject
            val raw = resource("manifest.json").readBytes()
            val flipped = raw.copyOf().also { it[1] = (it[1].toInt() xor 1).toByte() }
            File(pack, "manifest.json").writeBytes(flipped)
            val r2 = EnginePackRegistry.loadPack(pack)
            assertIs<EnginePackRegistry.Result.Declined>(r2)
            assertTrue(
                r2.decline.reason in setOf(
                    EnginePackRegistry.DeclineReason.NOT_CANONICAL,
                    EnginePackRegistry.DeclineReason.BAD_JSON,
                    EnginePackRegistry.DeclineReason.BAD_SIGNATURE,
                ), "got " + r2.decline.reason,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `synthetic pack with matching key is accepted`() {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        EnginePackRegistry.pubKeyHex = pubHexOf(kp.public)
        val root = kotlin.io.path.createTempDirectory("packv1-demo").toFile()
        try {
            val libs = mapOf("libllama.so" to "ELF fake one".toByteArray(), "libggml.so" to "ELF fake two".toByteArray())
            val dir = syntheticPack(root, libs, kp)
            val res = EnginePackRegistry.loadPack(dir)
            assertIs<EnginePackRegistry.Result.Accepted>(res)
            assertEquals(1, res.pack.version)
            assertTrue(EnginePackRegistry.Backend.OPENCL in res.pack.backends)
            val (packs, declined) = EnginePackRegistry.scan(root)
            assertEquals(1, packs.size); assertEquals(0, declined.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `tampered file content declines via sha gate`() {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        EnginePackRegistry.pubKeyHex = pubHexOf(kp.public)
        val root = kotlin.io.path.createTempDirectory("packv1-tamper").toFile()
        try {
            val dir = syntheticPack(root, mapOf("libllama.so" to "same-size payload".toByteArray()), kp)
            File(dir, "libllama.so").writeBytes("same-size payloade".toByteArray()) // same length, new bytes
            val res = EnginePackRegistry.loadPack(dir)
            assertIs<EnginePackRegistry.Result.Declined>(res)
            assertEquals(EnginePackRegistry.DeclineReason.FILE_HASH, res.decline.reason)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `wrong key declines`() {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val root = kotlin.io.path.createTempDirectory("packv1-wrongkey").toFile()
        try {
            syntheticPack(root, mapOf("libllama.so" to "x".toByteArray()), kp)
            EnginePackRegistry.pubKeyHex = pubHexOf(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public)
            val (packs, declined) = EnginePackRegistry.scan(root)
            assertEquals(0, packs.size)
            assertEquals(EnginePackRegistry.DeclineReason.BAD_SIGNATURE, declined.single().reason)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `selection: npu decline, opencl and builtin fallback, honest lines`() {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        EnginePackRegistry.pubKeyHex = pubHexOf(kp.public)
        val root = kotlin.io.path.createTempDirectory("packv1-select").toFile()
        try {
            // no packs at all -> builtin cpu, never npu/crash
            val (b0, p0) = EnginePackRegistry.selectBackend(emptyList(), openclStubPresent = true, npuRequested = true)
            assertEquals(EnginePackRegistry.Backend.CPU, b0); assertNull(p0)
            assertEquals("enginepack: backend=cpu pack=builtin", EnginePackRegistry.selectionLine(b0, p0))
            // cpu+opencl pack, stub present, npu requested -> opencl (npu absent declines gracefully)
            val dir = syntheticPack(root, mapOf("libllama.so" to "x".toByteArray()), kp)
            val (packs, _) = EnginePackRegistry.scan(root)
            val (b1, p1) = EnginePackRegistry.selectBackend(packs, openclStubPresent = true, npuRequested = true)
            assertEquals(EnginePackRegistry.Backend.OPENCL, b1); assertEquals("demo-engine", p1)
            // stub missing -> cpu lane from the same pack
            val (b2, p2) = EnginePackRegistry.selectBackend(packs, openclStubPresent = false, npuRequested = false)
            assertEquals(EnginePackRegistry.Backend.CPU, b2); assertEquals("demo-engine", p2)
            assertEquals("enginepack: backend=opencl pack=demo-engine", EnginePackRegistry.selectionLine(EnginePackRegistry.Backend.OPENCL, "demo-engine"))
        } finally {
            root.deleteRecursively()
        }
    }

    companion object {
        /** public half of tools\mob\enginepack\keys\test_pub.hex (test key only). */
        const val TEST_PUB_HEX = "18fc2b94bd2fda07c1654a9c609820b2b67da868e1def2b42cc773ea7cb644b4"
    }
}
