package me.rerere.rikkahub.data.files

import me.rerere.rikkahub.Brand
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Coverage for the brand rewrite applied while seeding the bundled default skills. The
 * assets are shared by both shipping flavours, so the rewrite is what lets one tree seed
 * "RikkaHub" on pure and "Nazarick" on nazarick.
 */
class BrandedAssetCopyTest {

    private fun subst(src: String, brand: String) =
        substituteProductBrand(src.toByteArray(Charsets.UTF_8), brand).toString(Charsets.UTF_8)

    @Test fun `substitutes every occurrence`() {
        assertEquals("a Nazarick b Nazarick c", subst("a RikkaHub b RikkaHub c", "Nazarick"))
    }

    @Test fun `substitutes adjacent occurrences without dropping one`() {
        assertEquals("NazarickNazarick", subst("RikkaHubRikkaHub", "Nazarick"))
    }

    @Test fun `leaves content without the name byte-identical`() {
        val src = "nothing to see here".toByteArray(Charsets.UTF_8)
        assertSame(src, substituteProductBrand(src, "Nazarick"))
        assertArrayEquals(src, substituteProductBrand(src, "Nazarick"))
    }

    @Test fun `is the identity for the pure brand`() {
        val src = "the RikkaHub agent".toByteArray(Charsets.UTF_8)
        assertSame(src, substituteProductBrand(src, "RikkaHub"))
    }

    @Test fun `default brand rewrites the shipped name for the running flavour`() {
        assertEquals("the ${Brand.NAME} agent", subst("the RikkaHub agent", Brand.NAME))
    }

    @Test fun `does not touch the lowercase slug used in URLs`() {
        val src = "https://github.com/wuyhong715/rikkahub-agent-pure".toByteArray(Charsets.UTF_8)
        assertSame(src, substituteProductBrand(src, "Nazarick"))
    }

    @Test fun `keeps surrounding multi-byte utf8 intact`() {
        assertEquals("设置 → Nazarick ← 完", subst("设置 → RikkaHub ← 完", "Nazarick"))
    }

    @Test fun `handles the empty input`() {
        assertArrayEquals(ByteArray(0), substituteProductBrand(ByteArray(0), "Nazarick"))
    }

    @Test fun `rewrites a name that appears at both ends`() {
        assertEquals("Nazarick mid Nazarick", subst("RikkaHub mid RikkaHub", "Nazarick"))
    }
}
