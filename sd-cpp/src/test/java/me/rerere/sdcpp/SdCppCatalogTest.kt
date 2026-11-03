package me.rerere.sdcpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog and the geometry, both of which ship numbers a user's download and patience
 * depend on. Nothing here needs a device: the engine itself is thin enough that the parts
 * worth proving are the parts that are pure.
 */
class SdCppCatalogTest {

    @Test
    fun `catalog ids and file names are unique`() {
        assertEquals(
            SdCppCatalog.models.size,
            SdCppCatalog.models.map { it.id }.toSet().size,
        )
        assertEquals(
            SdCppCatalog.models.size,
            SdCppCatalog.models.map { it.fileName }.toSet().size,
        )
    }

    @Test
    fun `every entry is a direct huggingface resolve url ending in its own file name`() {
        for (entry in SdCppCatalog.models) {
            assertTrue("${entry.id} must be https", entry.url.startsWith("https://"))
            assertTrue("${entry.id} must resolve directly", entry.url.contains("/resolve/"))
            assertTrue(
                "${entry.id} url must end with its fileName",
                entry.url.endsWith(entry.fileName),
            )
        }
    }

    @Test
    fun `sizes are plausible model weights not placeholders`() {
        for (entry in SdCppCatalog.models) {
            assertTrue("${entry.id} size looks wrong: ${entry.sizeBytes}", entry.sizeBytes > 100_000_000L)
            assertTrue("${entry.id} is not a phone model: ${entry.sizeBytes}", entry.sizeBytes < 12_000_000_000L)
        }
    }

    @Test
    fun `lookup by id and by file name agree`() {
        for (entry in SdCppCatalog.models) {
            assertEquals(entry, SdCppCatalog.entryById(entry.id))
            assertEquals(entry, SdCppCatalog.entryFor(entry.fileName))
        }
        assertEquals(null, SdCppCatalog.entryFor("nope.gguf"))
    }

    @Test
    fun `the first catalog entry is the default`() {
        assertNotNull(SdCppCatalog.models.first())
        assertEquals("sd15-q4_0", SdCppCatalog.models.first().id)
    }
}

class SdRenderPlanTest {

    @Test
    fun `every suggested size sits on the 64px grid`() {
        for (base in listOf(256, 512, 768, 1024)) {
            for (pair in listOf(
                SdRenderPlan.square(base),
                SdRenderPlan.portrait(base),
                SdRenderPlan.landscape(base),
            )) {
                assertEquals("$base square w", 0, pair.first % 64)
                assertEquals("$base square h", 0, pair.second % 64)
                assertTrue("$base $pair must stay positive", pair.first > 0 && pair.second > 0)
            }
        }
    }

    @Test
    fun `portrait is tall and landscape is wide`() {
        val (pw, ph) = SdRenderPlan.portrait(512)
        val (lw, lh) = SdRenderPlan.landscape(512)
        assertTrue("portrait $pw x $ph", pw < ph)
        assertTrue("landscape $lw x $lh", lw > lh)
        assertEquals(pw, lh)
        assertEquals(ph, lw)
    }

    @Test
    fun `an unsnaped base rounds down to the grid rather than up`() {
        assertEquals(512, SdRenderPlan.snap(550))
        assertEquals(64, SdRenderPlan.snap(64))
        assertEquals(64, SdRenderPlan.snap(1), "never smaller than one cell")
    }

    @Test
    fun `eta is unknown before the first measured step`() {
        assertEquals(null, SdRenderPlan.estimateSeconds(20, null))
        assertEquals(null, SdRenderPlan.estimateSeconds(20, 0L))
        assertEquals(30.0, SdRenderPlan.estimateSeconds(20, 1500L)!!, 0.001)
    }
}
