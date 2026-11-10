package me.rerere.localdream

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the transplanted engine contract. These are the segfault-shaped
 * invariants (clip naming, --cpu presence, ADSP paths) — the ones where a silent refactor
 * costs a field-debugging week, so they are nailed down without needing a device.
 */
class LocalDreamEngineLogicTest {

    private fun tempModelDir(files: List<String>): File {
        val dir = File.createTempFile("ldm", "").apply { delete() ; mkdirs() }
        dir.deleteOnExit()
        files.forEach { name ->
            val f = File(dir, name)
            f.parentFile.mkdirs()
            f.writeBytes(byteArrayOf(0))
        }
        return dir
    }

    @Test fun resolveModelDir_findsMarkerAtRoot() {
        val dir = tempModelDir(listOf("unet.bin"))
        assertEquals(dir, LocalDreamEngine.resolveModelDir(dir, isCpu = false))
    }

    @Test fun resolveModelDir_findsZipSubfolder() {
        val dir = tempModelDir(listOf("ChilloutMix/unet.mnn"))
        val found = LocalDreamEngine.resolveModelDir(dir, isCpu = true)
        assertNotNull(found)
        assertTrue(found!!.absolutePath.endsWith("ChilloutMix"))
    }

    @Test fun resolveModelDir_markerKindMustMatch() {
        val dir = tempModelDir(listOf("ChilloutMix/unet.mnn"))
        // An MNN pack is not a QNN pack; asking for the wrong marker must not find it.
        assertNull(LocalDreamEngine.resolveModelDir(dir, isCpu = false))
    }

    @Test fun resolveModelDir_stopsAtDepthLimit() {
        // searchDir(dir, d) inspects d-level children before recursing, so markers down to
        // a/b/c/d are found (OGAM's shipped behaviour) and a fifth level is where it stops.
        assertNotNull(LocalDreamEngine.resolveModelDir(tempModelDir(listOf("a/b/c/d/unet.bin")), isCpu = false))
        assertNull(LocalDreamEngine.resolveModelDir(tempModelDir(listOf("a/b/c/d/e/unet.bin")), isCpu = false))
    }

    @Test fun cpuCommand_keepsClipMnnInvariantAndSelectsMnn() {
        val dir = tempModelDir(listOf("clip_v2.mnn", "unet.mnn", "vae_decoder.mnn", "tokenizer.json"))
        val cmd = LocalDreamEngine.buildCommand(
            File("/nat", "libstable_diffusion_core.so"), dir, File("/rt"), isCpu = true,
        )
        assertTrue("--cpu must select the MNN backend", cmd.contains("--cpu"))
        val clipIdx = cmd.indexOf("--clip")
        assertTrue(clipIdx >= 0)
        assertTrue(
            "clip path must END in clip.mnn even when only clip_v2.mnn exists",
            cmd[clipIdx + 1].endsWith("clip.mnn"),
        )
        assertFalse("passing clip_v2.mnn directly segfaults the binary", cmd.any { it.endsWith("clip_v2.mnn") })
        assertFalse("no vae_encoder present, flag must be absent", cmd.contains("--vae_encoder"))
        assertTrue(cmd.contains("--text_embedding_size"))
        assertEquals("768", cmd[cmd.indexOf("--text_embedding_size") + 1])
    }

    @Test fun cpuCommand_addsVaeEncoderWhenPresent() {
        val dir = tempModelDir(listOf("clip.mnn", "unet.mnn", "vae_decoder.mnn", "vae_encoder.mnn", "tokenizer.json"))
        val cmd = LocalDreamEngine.buildCommand(File("/nat", "e.so"), dir, File("/rt"), isCpu = true)
        assertTrue(cmd.contains("--vae_encoder"))
    }

    @Test fun qnnCommand_wiresBackendLibrariesAndNoCpuFlag() {
        val dir = tempModelDir(listOf("clip.mnn", "unet.bin", "vae_decoder.bin", "tokenizer.json"))
        val cmd = LocalDreamEngine.buildCommand(
            File("/nat", "libstable_diffusion_core.so"), dir, File("/rt"), isCpu = false,
        )
        assertFalse(cmd.contains("--cpu"))
        assertEquals(File("/rt", "libQnnHtp.so").absolutePath, cmd[cmd.indexOf("--backend") + 1])
        assertEquals(File("/rt", "libQnnSystem.so").absolutePath, cmd[cmd.indexOf("--system_library") + 1])
        assertTrue("MNN clip present implies --use_cpu_clip", cmd.contains("--use_cpu_clip"))
    }

    @Test fun qnnCommand_fallsBackToClipBinWhenNoMnnClip() {
        val dir = tempModelDir(listOf("unet.bin", "vae_decoder.bin", "tokenizer.json"))
        val cmd = LocalDreamEngine.buildCommand(File("/nat", "e.so"), dir, File("/rt"), isCpu = false)
        assertTrue(cmd[cmd.indexOf("--clip") + 1].endsWith("clip.bin"))
        assertFalse(cmd.contains("--use_cpu_clip"))
    }

    @Test fun environment_carriesAdspAndTuning() {
        val env = LocalDreamEngine.buildEnvironment(File("/rt"))
        assertEquals("/rt", env["ADSP_LIBRARY_PATH"])
        assertEquals("/rt", env["DSP_LIBRARY_PATH"])
        assertEquals("/rt", env["LD_LIBRARY_PATH"]!!.split(":").first())
        assertEquals("WIDE", env["MNN_OPENCL_TUNING"])
    }

    @Test fun backendNormalisation_matrix() {
        assertEquals("mnn", LocalDreamEngine.normalizeBackend("CPU"))
        assertEquals("mnn", LocalDreamEngine.normalizeBackend("mnn"))
        assertEquals("qnn", LocalDreamEngine.normalizeBackend("NPU"))
        assertEquals("auto", LocalDreamEngine.normalizeBackend(null))
        assertEquals("auto", LocalDreamEngine.normalizeBackend("weird"))
    }

    @Test fun backendSelection_prefersQnnOnlyOnNpuCapableDevices() {
        val cpu = File("/m", "cpu")
        val qnn = File("/m", "qnn")
        assertEquals("qnn", LocalDreamEngine.pickBackend(cpu, qnn, npuSupported = true)!!.first)
        assertEquals("mnn", LocalDreamEngine.pickBackend(cpu, qnn, npuSupported = false)!!.first)
        assertEquals("qnn", LocalDreamEngine.pickBackend(null, qnn, npuSupported = false)!!.first)
        assertNull(LocalDreamEngine.pickBackend(null, null, npuSupported = true))
    }

    @Test fun npuSupport_isQualcommPrefix() {
        assertTrue(LocalDreamEngine.isNpuSupported("SM8650")) // the Fold's SM-F966B silicon
        assertFalse(LocalDreamEngine.isNpuSupported("Tensor G4"))
        assertFalse(LocalDreamEngine.isNpuSupported(""))
    }

    @Test fun generationJson_contractShapes() {
        val json = LocalDreamEngine.generationJson(
            LocalDreamRequest(prompt = "a \"quoted\" wizard", negativePrompt = "blurry", seed = 0),
        )
        assertTrue(json.contains("\"scheduler\":\"dpm\""))
        assertTrue(json.contains("\"prompt\":\"a \\\"quoted\\\" wizard\""))
        assertTrue(json.contains("\"negative_prompt\":\"blurry\""))
        assertTrue(json.contains("\"steps\":20"))
        assertTrue(json.contains("\"show_diffusion_process\":true"))
        // seed 0 must be replaced by a random one, never shipped as 0-by-default.
        val seedField = Regex("\"seed\":(-?\\d+)").find(json)!!.groupValues[1]
        assertNotZero(seedField.toLong())
        // explicit seed passes through
        val fixed = LocalDreamEngine.generationJson(LocalDreamRequest(prompt = "x", seed = 42))
        assertTrue(fixed.contains("\"seed\":42"))
    }

    private fun assertNotZero(v: Long) = assertTrue("seed must be randomised", v != 0L)

    @Test fun sseParsing_progressAndComplete() {
        val p = LocalDreamEngine.parseSseData(
            """{"type":"progress","step":4,"total_steps":20,"image":"aGVsbG8="}""",
        )
        assertTrue(p is SseEvent.Progress)
        assertEquals(4, (p as SseEvent.Progress).step)

        val c = LocalDreamEngine.parseSseData(
            """{"type":"complete","image":"aGk=","width":512,"height":512,"seed":7,"generation_time_ms":9000}""",
        ) as? SseEvent.Complete
        assertNotNull(c)
        assertEquals(512, c!!.width)
        assertEquals(9000L, c.elapsedMs)
    }

    @Test fun sseParsing_junkIsNull() {
        assertNull(LocalDreamEngine.parseSseData("not json"))
        assertNull(LocalDreamEngine.parseSseData("""{"type":"other"}"""))
        assertNull(LocalDreamEngine.parseSseData("""{"type":"progress","step":1}""")) // total_steps missing
    }

    @Test fun bundledHtpFor_namesKnownSocsAndStaysSilentOnUnknown() {
        // Run 142: SM8850 (Z Fold 8 Ultra, Snapdragon 8 Elite Gen 5) needs v81 — mapped here
        // and vendored in assets/qnnlibs since 2026-11-09 (off-grid ships the same V81 trio).
        assertEquals("v73", LocalDreamEngine.bundledHtpFor("SM8550"))
        assertEquals("v73", LocalDreamEngine.bundledHtpFor("SM8650P"))
        assertEquals("v79", LocalDreamEngine.bundledHtpFor("SM8750"))
        assertEquals("v81", LocalDreamEngine.bundledHtpFor("SM8850-1-AD"))
        assertEquals("v81", LocalDreamEngine.bundledHtpFor("SM8845"))
        assertNull(LocalDreamEngine.bundledHtpFor("Tensor G5"))
        assertNull(LocalDreamEngine.bundledHtpFor("")) // pre-API-31 sentinel: unknown
    }

    @Test fun hasVendoredRuntime_coversVendoredFamiliesAndSkipsTheRest() {
        assertTrue(LocalDreamEngine.hasVendoredRuntime("SM8750"))   // v79 vendored
        assertTrue(LocalDreamEngine.hasVendoredRuntime("SM8850"))   // v81 vendored 2026-11-09 (Fold 8 Ultra)
        assertTrue(LocalDreamEngine.hasVendoredRuntime("SM9999"))   // unknown -> optimistic, keep old behaviour
        // The fast-fail logic itself stays nailed even while every mapped chip is covered:
        // with v81 pulled from the set, SM8850 must skip the doomed 120 s attempt again.
        assertFalse(LocalDreamEngine.hasVendoredRuntime("SM8850", vendored = setOf("v73", "v79")))
        assertTrue(LocalDreamEngine.VENDOR_HTP_ARCHES.containsAll(setOf("v73", "v79", "v81")))
    }

    @Test fun rgbToPng_roundTripsDimensionsAndRejectsShortRasters() {
        // 2x1 RGB red|green
        val rgb = byteArrayOf(-1, 0, 0, 0, -1, 0)
        val base64 = java.util.Base64.getEncoder().encodeToString(rgb)
        val png = LocalDreamEngine.rgbBase64ToPng(base64, 2, 1)
        // PNG signature
        assertEquals(0x89.toByte(), png[0])
        assertEquals('P'.code.toByte(), png[1])
        // IHDR width/height big-endian at fixed offsets. .toInt() is load-bearing:
        // assertEquals(Integer, Byte) compares boxed types and never matches (run 133).
        assertEquals(0, png[16].toInt())
        assertEquals(2, png[19].toInt())
        assertEquals(1, png[23].toInt())
        runCatching { LocalDreamEngine.rgbBase64ToPng(base64, 4, 1) }
            .onFailure { assertTrue(it is IllegalArgumentException) }
            .onSuccess { assertFalse("short raster must not pass", true) }
    }
}
