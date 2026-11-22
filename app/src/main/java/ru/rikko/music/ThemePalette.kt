package ru.rikko.music

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

data class ThemeChoice(val name: String, val dark: Color, val light: Color)

data class ThemeColors(
    val background: Color,
    val surface: Color,
    val text: Color,
    val accent: Color,
    val light: Boolean,
    val scheme: ColorScheme,
    val warning: String? = null,
)

object ThemePalette {
    val bases = listOf("Белая", "Серая", "Чёрная")
    val accents = listOf(
        ThemeChoice("Зелёный", Color(0xFF8FCF52), Color(0xFF357A29)),
        ThemeChoice("Красный", Color(0xFFF07872), Color(0xFFB43432)),
        ThemeChoice("Оранжевый", Color(0xFFFFB65D), Color(0xFFA6570B)),
        ThemeChoice("Розовый", Color(0xFFF28DBA), Color(0xFFA92B68)),
        ThemeChoice("Синий", Color(0xFF8BA9FF), Color(0xFF3157B7)),
        ThemeChoice("Голубой", Color(0xFF76C9F5), Color(0xFF136F9F)),
        ThemeChoice("Бирюзовый", Color(0xFF63D5C2), Color(0xFF087C70)),
        ThemeChoice("Фиолетовый", Color(0xFFC5A1F6), Color(0xFF7140AD)),
    )

    fun parseHex(value: String): Color? {
        val normalized = value.trim().removePrefix("#")
        if (!normalized.matches(Regex("[0-9a-fA-F]{6}"))) return null
        return Color(0xFF000000L or normalized.toLong(16))
    }

    fun resolve(baseIndex: Int, accentIndex: Int, custom: Boolean,
                customBackground: String, customSurface: String, customText: String, customAccent: String): ThemeColors {
        val base = baseIndex.coerceIn(0, bases.lastIndex)
        val presetBackground = when (base) {
            0 -> Color(0xFFF7F9F7)
            1 -> Color(0xFF303435)
            else -> Color(0xFF101314)
        }
        val presetSurface = when (base) {
            0 -> Color(0xFFFFFFFF)
            1 -> Color(0xFF3B4041)
            else -> Color(0xFF1C2021)
        }
        val presetText = if (base == 0) Color(0xFF1D2924) else Color(0xFFF0F3F1)
        val accentChoice = accents[accentIndex.coerceIn(0, accents.lastIndex)]
        val presetAccent = if (base == 0) accentChoice.light else accentChoice.dark
        val background = if (custom) parseHex(customBackground) ?: presetBackground else presetBackground
        val requestedSurface = if (custom) parseHex(customSurface) ?: presetSurface else presetSurface
        val requestedText = if (custom) parseHex(customText) ?: presetText else presetText
        val requestedAccent = if (custom) parseHex(customAccent) ?: presetAccent else presetAccent
        // A single foreground cannot always contrast with both user-selected surfaces.
        // In that case keep the background and use it for panels too, so Reset stays readable.
        val readableText = listOf(requestedText, Color.Black, Color.White).firstOrNull {
            contrast(it, background) >= 4.5f && contrast(it, requestedSurface) >= 4.5f
        }
        val text = readableText ?: listOf(Color.Black, Color.White).maxBy { contrast(it, background) }
        val surface = if (contrast(text, requestedSurface) >= 4.5f) requestedSurface else background
        // Icons and selected controls need at least 3:1 against adjacent surfaces.
        val accentCandidates = listOf(requestedAccent, presetAccent, Color.Black, Color.White) +
            (0..255 step 8).map { channel -> Color(0xFF000000L or (channel * 0x010101L)) }
        val accent = accentCandidates.firstOrNull {
            contrast(it, background) >= 3f && contrast(it, surface) >= 3f
        } ?: accentCandidates.maxBy { min(contrast(it, background), contrast(it, surface)) }
        val invalid = custom && listOf(customBackground, customSurface, customText, customAccent)
            .any { parseHex(it) == null }
        val adjusted = custom && (text != requestedText || surface != requestedSurface || accent != requestedAccent)
        val warning = if (invalid || adjusted)
            "Неверный HEX или низкий контраст. Цвета на экране скорректированы; исходные значения сохранены."
        else null
        val light = background.luminance() > 0.48f
        val suggestedVariant = mix(surface, text, if (light) .06f else .10f)
        val variant = if (contrast(text, suggestedVariant) >= 4.5f) suggestedVariant else surface
        val muted = readableMuted(text, background, surface, variant, light)
        val suggestedAccentContainer = mix(background, accent, if (light) .15f else .21f)
        val accentContainer = if (contrast(text, suggestedAccentContainer) >= 4.5f)
            suggestedAccentContainer else surface
        val onAccent = listOf(Color.Black, Color.White).maxBy { contrast(it, accent) }
        val outline = mix(text, background, .58f)
        val scheme = if (light) lightColorScheme(
            primary = accent, onPrimary = onAccent, primaryContainer = accentContainer,
            onPrimaryContainer = text, secondary = accent, onSecondary = onAccent,
            secondaryContainer = accentContainer, onSecondaryContainer = text,
            tertiary = accent, onTertiary = onAccent,
            background = background, onBackground = text,
            surface = surface, onSurface = text, surfaceVariant = variant,
            onSurfaceVariant = muted, outline = outline,
            error = Color(0xFFB3261E), onError = Color.White,
        ) else darkColorScheme(
            primary = accent, onPrimary = onAccent, primaryContainer = accentContainer,
            onPrimaryContainer = text, secondary = accent, onSecondary = onAccent,
            secondaryContainer = accentContainer, onSecondaryContainer = text,
            tertiary = accent, onTertiary = onAccent,
            background = background, onBackground = text,
            surface = surface, onSurface = text, surfaceVariant = variant,
            onSurfaceVariant = muted, outline = outline,
            error = Color(0xFFFF8B84), onError = Color(0xFF3B0907),
        )
        return ThemeColors(background, surface, text, accent, light, scheme, warning)
    }

    private fun mix(first: Color, second: Color, secondFraction: Float): Color {
        val a = 1f - secondFraction
        return Color(
            first.red * a + second.red * secondFraction,
            first.green * a + second.green * secondFraction,
            first.blue * a + second.blue * secondFraction,
        )
    }

    private fun readableMuted(text: Color, background: Color, surface: Color,
                             variant: Color, light: Boolean): Color {
        val preferred = if (light) .38f else .40f
        fun readable(fraction: Float): Boolean {
            val candidate = mix(text, background, fraction)
            return contrast(candidate, surface) >= 4.5f && contrast(candidate, variant) >= 4.5f
        }
        if (readable(preferred)) return mix(text, background, preferred)
        if (!readable(0f)) return text // A custom text color may itself lack contrast.
        var low = 0f
        var high = preferred
        repeat(12) {
            val middle = (low + high) / 2f
            if (readable(middle)) low = middle else high = middle
        }
        return mix(text, background, low)
    }

    internal fun contrast(first: Color, second: Color): Float {
        val a = first.luminance()
        val b = second.luminance()
        return (max(a, b) + .05f) / (min(a, b) + .05f)
    }
}
