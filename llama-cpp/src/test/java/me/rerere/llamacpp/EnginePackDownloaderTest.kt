package me.rerere.llamacpp

import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * US-007: pack fetch pipeline on a fake transport. Registry gates themselves are covered
 * by [EnginePackRegistryTest]; this suite proves the PIPELINE ordering that the AC names:
 * resume-aware fetch -> unpack -> size/sha verify (post-install gate) -> atomic rename ->
 * refuse-downgrade -> rollback to the previous pack when the gate declines. Real Ed25519
 * on the JVM, no device, no Robolectric.
 */
class EnginePackDownloaderTest {
    private fun sha(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private class Fixture(content: ByteArray) {
        private fun pubHex(pub: java.security.PublicKey): String =
            pub.encoded.takeLast(32).joinToString("") { "%02x".format(it) }
        val manifest: EnginePackRegistry.ParsedManifest
        val manifestBytes: ByteArray
        val sig: ByteArray
        private val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        fun sign(b: ByteArray): ByteArray {
            val s = Signature.getInstance("Ed25519")
            s.initSign(kp.private)
            s.update(b)
            return s.sign()
        }

        init {
            val m = EnginePackRegistry.ParsedManifest(
                id = "dl-engine", version = 1, abi = "arm64-v8a",
                backends = listOf(EnginePackRegistry.Backend.CPU, EnginePackRegistry.Backend.OPENCL),
                minApi = 26, enginePin = "0123456789abcdef",
                files = listOf(Triple("libllama.so", content.size.toLong(), sha(content))),
            )
            val bytes = EnginePackRegistry.canonical(m)
            val s = Signature.getInstance("Ed25519")
            s.initSign(kp.private)
            s.update(bytes)
            EnginePackRegistry.pubKeyHex = pubHex(kp.public)
            manifest = m
            manifestBytes = bytes
            sig = s.sign()
        }
    }

    private fun unpackFake(content: ByteArray): (File, File) -> Unit = { _, dest ->
        File(dest, "libllama.so").writeBytes(content)
    }

    @Test
    fun `install happy path lands a scannable pack`() {
        val fx = Fixture("ELF fake".toByteArray())
        val root = kotlin.io.path.createTempDirectory("dl-happy").toFile()
        try {
            val dl = EnginePackDownloader(root, { _, part, resume ->
                val total = fx.manifestBytes.size.toLong() + 40
                part.writeBytes(ByteArray(total.toInt()))
                total
            }, unpackFake("ELF fake".toByteArray()))
            val res = dl.installFrom(fx.manifestBytes, fx.sig, "https://example.invalid/p.zip")
            assertIs<EnginePackDownloader.Outcome.Installed>(res)
            assertEquals(1, res.pack.version)
            val (packs, declined) = EnginePackRegistry.scan(root)
            assertEquals(1, packs.size, "declined=" + declined.map { it.reason })
            assertEquals(File(root, "engine-packs/dl-engine-1"), packs[0].dir)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `fetch receives resume offset from existing part bytes`() {
        val fx = Fixture("payload!".toByteArray())
        val root = kotlin.io.path.createTempDirectory("dl-resume").toFile()
        try {
            val part0 = File(root, "engine-packs/.staging/dl-engine-1.part")
            part0.parentFile!!.mkdirs()
            part0.writeBytes(ByteArray(3))
            val seen = mutableListOf<Long>()
            val dl = EnginePackDownloader(root, { _, part, resume ->
                seen += resume
                val total = 3L + 9
                part.writeBytes(ByteArray(total.toInt()))
                total
            }, unpackFake("payload!".toByteArray()))
            val res = dl.installFrom(fx.manifestBytes, fx.sig, "u")
            assertIs<EnginePackDownloader.Outcome.Installed>(res)
            assertEquals(listOf(3L), seen)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `same or lower version is refused without touching transport`() {
        val fx = Fixture("same bytes".toByteArray())
        val root = kotlin.io.path.createTempDirectory("dl-dg").toFile()
        try {
            var fetches = 0
            val dl = EnginePackDownloader(root, { _, part, resume ->
                fetches++
                val total = 100L
                part.writeBytes(ByteArray(total.toInt()))
                total
            }, unpackFake("same bytes".toByteArray()))
            assertIs<EnginePackDownloader.Outcome.Installed>(dl.installFrom(fx.manifestBytes, fx.sig, "u"))
            val again = dl.installFrom(fx.manifestBytes, fx.sig, "u")
            assertIs<EnginePackDownloader.Outcome.RefusedDowngrade>(again)
            assertEquals(1, fetches, "refusal must not re-download")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `gate decline after install rolls back and keeps serving old pack`() {
        val fxOld = Fixture("OLD content".toByteArray())
        val root = kotlin.io.path.createTempDirectory("dl-rb").toFile()
        try {
            val dlOld = EnginePackDownloader(root, { _, part, _ ->
                part.writeBytes(ByteArray(64)); 64L
            }, unpackFake("OLD content".toByteArray()))
            assertIs<EnginePackDownloader.Outcome.Installed>(dlOld.installFrom(fxOld.manifestBytes, fxOld.sig, "u"))

            // new candidate version 2 whose unpacked bytes do not match its manifest sha
            val bytes2 = EnginePackRegistry.canonical(
                fxOld.manifest.copy(version = 2,
                    files = listOf(Triple("libllama.so", 11L, sha("NEW content".toByteArray())))),
            )
            val sig2 = fxOld.sign(bytes2)
            val dlNew = EnginePackDownloader(root, { _, part, _ ->
                part.writeBytes(ByteArray(64)); 64L
            }, unpackFake("XXXXXXXtent".toByteArray())) // same length, wrong bytes -> FILE_HASH
            val res = dlNew.installFrom(bytes2, sig2, "u")
            assertIs<EnginePackDownloader.Outcome.Failed>(res)
            assertEquals("verify", res.stage)
            assertEquals("FILE_HASH", res.detail)
            val (packs, declined) = EnginePackRegistry.scan(root)
            assertEquals(0, declined, "leftovers must be gone: " + declined.map { it.packDir.name + ":" + it.reason })
            assertEquals(1, packs.size)
            assertEquals(1, packs[0].version, "old pack must still serve")
        } finally {
            root.deleteRecursively()
        }
    }
}
