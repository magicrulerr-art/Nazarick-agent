package me.rerere.rikkahub.ui.pages.imggen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v0.8 lever parsing (owner screenshot 2026-11-10): blank = default, seed <= 0 = random,
 * out-of-range steps/CFG rejected per-field so a typo never silently rides along.
 */
class ImgGenLeversParseTest {

    @Test
    fun `all blank parses to all defaults`() {
        val (problem, parsed) = parseImgGenLevers("", "", "")
        assertNull(problem)
        assertNull(parsed.steps)
        assertNull(parsed.guidanceScale)
        assertNull(parsed.seed)
    }

    @Test
    fun `valid values pass through`() {
        val (problem, parsed) = parseImgGenLevers("8", "1", "12345")
        assertNull(problem)
        assertEquals(8, parsed.steps)
        assertEquals(1.0, parsed.guidanceScale!!, 0.0)
        assertEquals(12345L, parsed.seed)
    }

    @Test
    fun `seed minus one means random`() {
        val (problem, parsed) = parseImgGenLevers("", "", "-1")
        assertNull(problem)
        assertNull(parsed.seed) // <= 0 folds to null = engine-random
    }

    @Test
    fun `non-numeric input names the offending lever`() {
        assertEquals(ImgGenLeverProblem.STEPS, parseImgGenLevers("lots", "", "").first)
        assertEquals(ImgGenLeverProblem.CFG, parseImgGenLevers("8", "high", "").first)
        assertEquals(ImgGenLeverProblem.SEED, parseImgGenLevers("8", "1", "abc").first)
    }

    @Test
    fun `out-of-range numbers are problems not coercions`() {
        assertEquals(ImgGenLeverProblem.STEPS, parseImgGenLevers("0", "", "").first)
        assertEquals(ImgGenLeverProblem.STEPS, parseImgGenLevers("151", "", "").first)
        assertEquals(ImgGenLeverProblem.CFG, parseImgGenLevers("", "30.5", "").first)
        // boundaries are inclusive
        assertNull(parseImgGenLevers("1", "0", "").first)
        assertNull(parseImgGenLevers("150", "30", "").first)
    }

    @Test
    fun `whitespace is tolerated and treated as blank`() {
        val (problem, parsed) = parseImgGenLevers("  ", " 7.5 ", " 42 ")
        assertNull(problem)
        assertNull(parsed.steps)
        assertEquals(7.5, parsed.guidanceScale!!, 0.0)
        assertEquals(42L, parsed.seed)
    }
}
