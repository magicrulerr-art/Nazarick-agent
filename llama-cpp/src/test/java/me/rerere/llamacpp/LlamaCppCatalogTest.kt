package me.rerere.llamacpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integrity checks for the curated catalog. These are cheap invariants that would otherwise
 * only surface as a failed multi-gigabyte download or a broken-looking model picker.
 */
class LlamaCppCatalogTest {

    @Test
    fun `every entry points at a gguf file`() {
        LlamaCppCatalog.ENTRIES.forEach { entry ->
            assertTrue(
                "${entry.displayName}: file \"${entry.file}\" is not a .gguf file",
                entry.file.endsWith(".gguf"),
            )
        }
    }

    @Test
    fun `every entry has a non-zero size`() {
        LlamaCppCatalog.ENTRIES.forEach { entry ->
            assertTrue(
                "${entry.displayName} has a non-positive sizeBytes",
                entry.sizeBytes > 0L,
            )
        }
    }

    @Test
    fun `display names are unique`() {
        val names = LlamaCppCatalog.ENTRIES.map { it.displayName }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `download urls are https resolve urls ending in gguf`() {
        LlamaCppCatalog.ENTRIES.forEach { entry ->
            val url = entry.resolveUrl()
            assertTrue(url, url.startsWith("https://huggingface.co/"))
            assertTrue(url, "/resolve/main/" in url)
            assertTrue(url, url.endsWith(".gguf"))
        }
    }

    /**
     * v0.6 owner ruling: "use lfm3 as the recommended top option, the 1.7b for daily and
     * the 2vl for vision". Order IS the recommendation in this picker — pinned so a future
     * reshuffle has to be a decision, not an accident.
     */
    @Test
    fun `lfm3 leads the picker`() {
        assertEquals(
            listOf("LFM3 1.7B", "LFM3 VL 2B"),
            LlamaCppCatalog.ENTRIES.take(2).map { it.displayName },
        )
        val daily = LlamaCppCatalog.ENTRIES.first()
        assertEquals("LiquidAI/LFM3-1.7B-GGUF", daily.repo)
        assertEquals(1099627808L, daily.sizeBytes)
        assertTrue(LlamaCppCatalog.ENTRIES[1].tags.contains("vision"))
    }
}
