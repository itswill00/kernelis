package dev.kernelis.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * KERNELIS palette.
 *
 * Deliberately restrained: a near-black neutral ramp plus a single teal accent used
 * for interactive affordances. Discipline colours are semantic only — they never
 * decorate, they only communicate risk or health. This keeps the dashboard readable
 * when it is dense with data.
 */
internal object KernelisPalette {
    // Neutrals: slightly warm so pure-black OLED panels do not band.
    val Ink = Color(0xFF0A0C0D)
    val Surface = Color(0xFF111417)
    val SurfaceRaised = Color(0xFF171B1F)
    val SurfaceHigh = Color(0xFF1E2429)
    val Outline = Color(0xFF2A3138)
    val OutlineStrong = Color(0xFF3A444D)

    // Text ramp, tuned for WCAG AA on Surface.
    val TextPrimary = Color(0xFFE8EDF1)
    val TextSecondary = Color(0xFF9AA7B2)
    val TextTertiary = Color(0xFF6B7883)

    // Accent: single hue, three steps. Used sparingly.
    val Accent = Color(0xFF35D0BA)
    val AccentDim = Color(0xFF1E8C7D)
    val AccentSoft = Color(0x2635D0BA)

    // Semantic.
    val Success = Color(0xFF4ADE80)
    val Warning = Color(0xFFFBBF24)
    val Danger = Color(0xFFF87171)
    val DangerSoft = Color(0x26F87171)
    val Info = Color(0xFF60A5FA)

    // Chart series — low saturation so multiple lines stay legible.
    val Series1 = Color(0xFF35D0BA)
    val Series2 = Color(0xFF60A5FA)
    val Series3 = Color(0xFFA78BFA)
    val Series4 = Color(0xFFFBBF24)
    val Series5 = Color(0xFFF472B6)
    val Series6 = Color(0xFF94A3B8)
}
