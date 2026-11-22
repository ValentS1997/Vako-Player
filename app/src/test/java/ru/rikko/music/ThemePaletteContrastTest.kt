package ru.rikko.music

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class ThemePaletteContrastTest {
    @Test
    fun presetTextAndPrimaryControlsMeetNormalTextContrast() {
        for (base in ThemePalette.bases.indices) {
            for (accent in ThemePalette.accents.indices) {
                val scheme = ThemePalette.resolve(base, accent, false, "", "", "", "").scheme
                val case = "base=$base accent=$accent"
                assertTrue("primary control $case", contrast(scheme.primary, scheme.onPrimary) >= 4.5f)
                assertTrue("gloss highlight $case", contrast(lerp(scheme.primary, Color.White, .03f), scheme.onPrimary) >= 4.5f)
                assertTrue("body text $case", contrast(scheme.surface, scheme.onSurface) >= 4.5f)
                assertTrue("muted text $case", contrast(scheme.surfaceVariant, scheme.onSurfaceVariant) >= 4.5f)
            }
        }
    }

    @Test
    fun customColorsRemainReadableIncludingConflictingSurfaces() {
        val cases = listOf(
            listOf("#000000", "#FFFFFF", "#000000", "#000000"),
            listOf("#FFFFFF", "#000000", "#FFFFFF", "#FFFFFF"),
            listOf("#333333", "#333333", "#333333", "#333333"),
            listOf("#123456", "#AABBCC", "#445566", "#778899"),
            listOf("invalid", "#FFFFFF", "#FFFFFF", "#12X456"),
        )
        for (case in cases) {
            val palette = ThemePalette.resolve(2, 0, true, case[0], case[1], case[2], case[3])
            assertTrue("background $case", contrast(palette.background, palette.text) >= 4.5f)
            assertTrue("surface $case", contrast(palette.surface, palette.text) >= 4.5f)
            assertTrue("muted $case", contrast(palette.scheme.surfaceVariant,
                palette.scheme.onSurfaceVariant) >= 4.5f)
            assertTrue("button $case", contrast(palette.accent, palette.scheme.onPrimary) >= 4.5f)
            assertTrue("accent on background $case", contrast(palette.accent, palette.background) >= 3f)
            assertTrue("accent on surface $case", contrast(palette.accent, palette.surface) >= 3f)
            assertNotNull("warning $case", palette.warning)
        }
    }

    @Test
    fun validCustomPaletteNeedsNoWarningAndInvalidHexIsRejected() {
        val palette = ThemePalette.resolve(2, 0, true,
            "#101314", "#1C2021", "#F0F3F1", "#8FCF52")
        assertNull(palette.warning)
        assertNull(ThemePalette.parseHex("#XYZXYZ"))
        assertNull(ThemePalette.parseHex("#12345"))
        assertNull(ThemePalette.parseHex("#1234567"))
    }

    @Test
    fun variedCustomColorsKeepResetAndControlsVisible() {
        val random = Random(25)
        repeat(100) {
            fun color() = "#%06X".format(random.nextInt(0x1000000))
            val palette = ThemePalette.resolve(0, 0, true, color(), color(), color(), color())
            assertTrue(contrast(palette.background, palette.text) >= 4.5f)
            assertTrue(contrast(palette.surface, palette.text) >= 4.5f)
            assertTrue(contrast(palette.accent, palette.background) >= 3f)
            assertTrue(contrast(palette.accent, palette.surface) >= 3f)
        }
    }

    private fun contrast(first: Color, second: Color): Float {
        val a = first.luminance()
        val b = second.luminance()
        return (max(a, b) + .05f) / (min(a, b) + .05f)
    }
}
