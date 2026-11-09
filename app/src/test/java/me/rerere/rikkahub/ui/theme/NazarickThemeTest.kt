package me.rerere.rikkahub.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The house palette is the default now (v0.6): position 0 of PresetThemes IS the factory
 * default (PreferencesStore reads PresetThemes[0].id for an unset themeId), and unknown
 * ids must fall back to it, not to the donor's sakura.
 */
class NazarickThemeTest {

    @Test
    fun `nazarick is the first preset and resolves by id`() {
        assertEquals("nazarick", PresetThemes.first().id)
        assertNotNull(findThemeById("nazarick", emptyList()))
    }

    @Test
    fun `unknown theme ids fall back to nazarick`() {
        assertEquals("nazarick", findPresetTheme("who_knows").id)
    }

    @Test
    fun `preset ids are unique`() {
        val ids = PresetThemes.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `nazarick schemes differ and keep the house red error`() {
        val preset = findPresetTheme("nazarick")
        val light = preset.getColorScheme(dark = false)
        val dark = preset.getColorScheme(dark = true)
        // light != dark on the base plate
        assert(light.background != dark.background)
        // error reads the same house red family in both halves
        assertEquals(androidx.compose.ui.graphics.Color(0xFFDC2626), light.error)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFEF4444), dark.error)
    }
}
