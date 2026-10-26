package dev.kernelis.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Extra colours that Material's [androidx.compose.material3.ColorScheme] has no slot
 * for (semantic statuses, chart series). Provided through a CompositionLocal so screens
 * do not reach into the palette object directly.
 */
public data class KernelisColors(
    val success: androidx.compose.ui.graphics.Color,
    val warning: androidx.compose.ui.graphics.Color,
    val danger: androidx.compose.ui.graphics.Color,
    val dangerSoft: androidx.compose.ui.graphics.Color,
    val info: androidx.compose.ui.graphics.Color,
    val accent: androidx.compose.ui.graphics.Color,
    val accentSoft: androidx.compose.ui.graphics.Color,
    val textTertiary: androidx.compose.ui.graphics.Color,
    val surfaceRaised: androidx.compose.ui.graphics.Color,
    val surfaceHigh: androidx.compose.ui.graphics.Color,
    val series: List<androidx.compose.ui.graphics.Color>,
)

public val LocalKernelisColors: androidx.compose.runtime.ProvidableCompositionLocal<KernelisColors> =
    staticCompositionLocalOf {
        error("KernelisColors not provided; wrap content in KernelisTheme")
    }

/** Shared dimension scale — keeps spacing consistent across screens. */
public object Spacing {
    public val xs = 4.dp
    public val sm = 8.dp
    public val md = 12.dp
    public val lg = 16.dp
    public val xl = 24.dp
    public val xxl = 32.dp
}

private val mono = FontFamily.Monospace

private val kernelisTypography: Typography
    get() = Typography().let { base ->
        base.copy(
            // Numbers and status labels use a monospace family so digits do not jitter
            // as live values update — important for a monitoring dashboard.
            displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp),
            headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Medium),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
            labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
            labelSmall = base.labelSmall.copy(
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
                fontFamily = mono,
            ),
            bodySmall = base.bodySmall.copy(lineHeight = 18.sp),
        )
    }

/** Monospaced style used by the console, diagnostics and numeric readouts. */
public val NumericTextStyle: TextStyle = TextStyle(
    fontFamily = mono,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    fontWeight = FontWeight.Normal,
    letterSpacing = 0.sp,
)

@Composable
public fun KernelisTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // KERNELIS is a dark-first product: the dark scheme is the only scheme, because a
    // kernel monitor is usually read at night on an OLED panel. A light scheme is not
    // shipped rather than shipped half-tuned.
    val scheme = darkColorScheme(
        primary = KernelisPalette.Accent,
        onPrimary = KernelisPalette.Ink,
        primaryContainer = KernelisPalette.AccentDim,
        onPrimaryContainer = KernelisPalette.TextPrimary,
        secondary = KernelisPalette.Series2,
        onSecondary = KernelisPalette.Ink,
        background = KernelisPalette.Ink,
        onBackground = KernelisPalette.TextPrimary,
        surface = KernelisPalette.Surface,
        onSurface = KernelisPalette.TextPrimary,
        surfaceVariant = KernelisPalette.SurfaceRaised,
        onSurfaceVariant = KernelisPalette.TextSecondary,
        surfaceContainer = KernelisPalette.SurfaceRaised,
        surfaceContainerHigh = KernelisPalette.SurfaceHigh,
        surfaceContainerLow = KernelisPalette.Surface,
        outline = KernelisPalette.Outline,
        outlineVariant = KernelisPalette.OutlineStrong,
        error = KernelisPalette.Danger,
        onError = KernelisPalette.Ink,
        errorContainer = KernelisPalette.DangerSoft,
        onErrorContainer = KernelisPalette.Danger,
    )

    val extra = KernelisColors(
        success = KernelisPalette.Success,
        warning = KernelisPalette.Warning,
        danger = KernelisPalette.Danger,
        dangerSoft = KernelisPalette.DangerSoft,
        info = KernelisPalette.Info,
        accent = KernelisPalette.Accent,
        accentSoft = KernelisPalette.AccentSoft,
        textTertiary = KernelisPalette.TextTertiary,
        surfaceRaised = KernelisPalette.SurfaceRaised,
        surfaceHigh = KernelisPalette.SurfaceHigh,
        series = listOf(
            KernelisPalette.Series1,
            KernelisPalette.Series2,
            KernelisPalette.Series3,
            KernelisPalette.Series4,
            KernelisPalette.Series5,
            KernelisPalette.Series6,
        ),
    )

    CompositionLocalProvider(LocalKernelisColors provides extra) {
        MaterialTheme(
            colorScheme = scheme,
            typography = kernelisTypography,
            content = content,
        )
    }
}
