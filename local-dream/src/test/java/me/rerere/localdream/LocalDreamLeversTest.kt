package me.rerere.localdream

import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.ui.ImageAspectRatio
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The v0.8 sampling levers (owner request 2026-11-10): every lever is a pure mapping
 * tested here — fallbacks (v0.7 byte-identical), clamps (engine envelope), the seed<=0
 * random convention, and the closed backend vocabulary.
 */
class LocalDreamLeversTest {

    private val entry = QnnModelEntry(
        id = "test-model",
        displayName = "Test",
        fileName = "test.zip",
        url = "https://example.invalid/test.zip",
        sizeBytes = 1L,
        packKind = "qnn",
        defaultSteps = 20,
        defaultCfg = 7.5,
    )

    private fun p(
        steps: Int? = null,
        cfg: Double? = null,
        seed: Long? = null,
        width: Int? = null,
        height: Int? = null,
        upscale: Int? = null,
        backend: String? = null,
        aspect: ImageAspectRatio = ImageAspectRatio.SQUARE,
    ) = ImageGenerationParams(
        model = me.rerere.ai.provider.Model(
            modelId = "test-model",
            displayName = "Test",
            type = me.rerere.ai.provider.ModelType.IMAGE,
        ),
        prompt = "a skull",
        aspectRatio = aspect,
        steps = steps,
        guidanceScale = cfg,
        seed = seed,
        width = width,
        height = height,
        upscaleFactor = upscale,
        engineBackend = backend,
    )

    @Test
    fun `no levers reproduce the v0_7 behaviour exactly`() {
        val spec = effectiveSpec(p(), entry)
        assertEquals(20, spec.steps)
        assertEquals(7.5, spec.cfg, 0.0)
        assertEquals(0, spec.seed)
        assertEquals(512, spec.width)
        assertEquals(512, spec.height)
        assertEquals(1, spec.upscale)
        assertEquals("auto", spec.backend)
    }

    @Test
    fun `aspect fallback survives as long as width and height stay unset`() {
        val portrait = effectiveSpec(p(aspect = ImageAspectRatio.PORTRAIT), entry)
        assertEquals(384, portrait.width)
        assertEquals(512, portrait.height)
        // one explicit side beats the aspect only for that side
        val mixed = effectiveSpec(p(aspect = ImageAspectRatio.PORTRAIT, width = 512), entry)
        assertEquals(512, mixed.width)
        assertEquals(512, mixed.height)
    }

    @Test
    fun `levers pass through when inside the engine envelope`() {
        val spec = effectiveSpec(
            p(steps = 8, cfg = 1.0, seed = 12345, width = 384, height = 384, upscale = 2, backend = "qnn"),
            entry,
        )
        assertEquals(8, spec.steps)
        assertEquals(1.0, spec.cfg, 0.0)
        assertEquals(12345, spec.seed)
        assertEquals(384, spec.width)
        assertEquals(384, spec.height)
        assertEquals(2, spec.upscale)
        assertEquals("qnn", spec.backend)
    }

    @Test
    fun `out-of-envelope levers clamp instead of failing`() {
        val spec = effectiveSpec(p(steps = 0, cfg = 99.0, seed = -7, upscale = 100, backend = "teleport"), entry)
        assertEquals(1, spec.steps)
        assertEquals(30.0, spec.cfg, 0.0)
        assertEquals(0, spec.seed) // -7 <= 0 → engine's random convention
        assertEquals(4, spec.upscale)
        assertEquals("auto", spec.backend) // unknown vocabulary degrades to auto, never garbage
    }

    @Test
    fun `seed zero keeps the engine random convention and huge seeds clamp to Int`() {
        assertEquals(0, effectiveSpec(p(seed = 0), entry).seed)
        assertEquals(0, effectiveSpec(p(seed = -1), entry).seed)
        assertEquals(
            Int.MAX_VALUE,
            effectiveSpec(p(seed = Long.MAX_VALUE), entry).seed,
        )
    }

    @Test
    fun `backend vocabulary is closed and case-insensitive`() {
        assertEquals("QNN".lowercase(), effectiveSpec(p(backend = "QNN"), entry).backend)
        assertEquals("mnn", effectiveSpec(p(backend = "Mnn"), entry).backend)
        assertEquals("auto", effectiveSpec(p(backend = ""), entry).backend)
    }

    @Test
    fun `upscale factor one and below is a no-op passthrough`() {
        // upscalePng itself needs Android Bitmaps, but the <=1 guard must not touch the bytes.
        val png = byteArrayOf(1, 2, 3)
        assertEquals(png, upscalePng(png, 1))
        assertEquals(png, upscalePng(png, 0))
    }
}
