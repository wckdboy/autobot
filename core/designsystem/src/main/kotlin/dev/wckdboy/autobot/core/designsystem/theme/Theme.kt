package dev.wckdboy.autobot.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Underground" palette: near-black ink surfaces separated by hairlines, bone text, one loud
 * signal colour (acid) and a small set of semantic accents. Accents are used sparingly — for
 * state, focus and telemetry — never as large fills.
 */
object AutobotColors {
    val Black = Color(0xFF000000)
    val Void = Color(0xFF050506)
    val Ink950 = Color(0xFF09090B)
    val Ink900 = Color(0xFF0E0E11)
    val Ink850 = Color(0xFF131317)
    val Ink800 = Color(0xFF19191E)
    val Ink700 = Color(0xFF212127)
    val Line = Color(0xFF2A2A31)
    val Ink500 = Color(0xFF45454F)
    val Ash400 = Color(0xFF8A8A95)
    val Ash200 = Color(0xFFC4C4CC)
    val Bone100 = Color(0xFFECECEF)
    val Bone50 = Color(0xFFF5F5F2)

    /** Primary signal: actions, focus, "live" state. */
    val Acid = Color(0xFFD2FF3C)
    val AcidDim = Color(0xFF1B2300)
    val AcidDeep = Color(0xFF4A6600)

    /** Ultraviolet: reasoning traces, NPU, agent activity. */
    val Uv = Color(0xFFA990FF)
    val UvDim = Color(0xFF241A4D)
    val UvDeep = Color(0xFF5B3FD0)

    val Cyan = Color(0xFF52E5F2)
    val Amber = Color(0xFFFFB23D)
    val Signal = Color(0xFFFF4170)

    // Semantic aliases used across features.
    val Green = Acid
    val Violet = Uv
    val Red = Signal
}

val GraphiteDarkColorScheme: ColorScheme = darkColorScheme(
    primary = AutobotColors.Acid,
    onPrimary = AutobotColors.AcidDim,
    primaryContainer = Color(0xFF2B3800),
    onPrimaryContainer = Color(0xFFE9FFA6),
    secondary = AutobotColors.Uv,
    onSecondary = AutobotColors.UvDim,
    secondaryContainer = Color(0xFF2E2266),
    onSecondaryContainer = Color(0xFFE5DDFF),
    tertiary = AutobotColors.Cyan,
    onTertiary = Color(0xFF00363C),
    tertiaryContainer = Color(0xFF00474F),
    onTertiaryContainer = Color(0xFFA5F3FB),
    error = AutobotColors.Signal,
    onError = Color(0xFF3F0012),
    errorContainer = Color(0xFF4A0F20),
    onErrorContainer = Color(0xFFFFD9DF),
    background = AutobotColors.Ink950,
    onBackground = AutobotColors.Bone100,
    surface = AutobotColors.Ink950,
    onSurface = AutobotColors.Bone100,
    surfaceVariant = AutobotColors.Ink700,
    onSurfaceVariant = AutobotColors.Ash400,
    surfaceContainerLowest = AutobotColors.Void,
    surfaceContainerLow = AutobotColors.Ink900,
    surfaceContainer = AutobotColors.Ink850,
    surfaceContainerHigh = AutobotColors.Ink800,
    surfaceContainerHighest = AutobotColors.Ink700,
    surfaceBright = AutobotColors.Ink700,
    surfaceDim = AutobotColors.Void,
    outline = AutobotColors.Ink500,
    outlineVariant = AutobotColors.Line,
    inverseSurface = AutobotColors.Bone100,
    inverseOnSurface = AutobotColors.Ink900,
    inversePrimary = AutobotColors.AcidDeep,
    scrim = AutobotColors.Black,
)

val GraphiteLightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF3B5200),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2FF3C),
    onPrimaryContainer = Color(0xFF141B00),
    secondary = AutobotColors.UvDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5DDFF),
    onSecondaryContainer = Color(0xFF1B0A5C),
    tertiary = Color(0xFF00696F),
    error = Color(0xFFC0123F),
    background = AutobotColors.Bone50,
    onBackground = AutobotColors.Ink900,
    surface = AutobotColors.Bone50,
    onSurface = AutobotColors.Ink900,
    surfaceVariant = Color(0xFFE6E6E2),
    onSurfaceVariant = Color(0xFF55555E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F0EC),
    surfaceContainer = Color(0xFFEAEAE6),
    surfaceContainerHigh = Color(0xFFE4E4E0),
    surfaceContainerHighest = Color(0xFFDDDDD8),
    outline = Color(0xFF9A9AA3),
    outlineVariant = Color(0xFFD0D0CB),
)

/** Pure-black variant for OLED panels: every background-ish surface becomes #000. */
fun ColorScheme.toAmoled(): ColorScheme = copy(
    background = AutobotColors.Black,
    surface = AutobotColors.Black,
    surfaceContainerLowest = AutobotColors.Black,
    surfaceContainerLow = Color(0xFF070708),
    surfaceContainer = Color(0xFF0C0C0E),
    surfaceDim = AutobotColors.Black,
)

/** Monospace family for code, telemetry and chrome labels; body copy uses the system sans. */
val CodeFontFamily: FontFamily = FontFamily.Monospace

private val TightLineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private val Base = Typography()

/**
 * Sans for reading, mono for machinery: every `label*` style (buttons, chips, tabs, captions)
 * is monospace with open tracking, so UI chrome reads like instrument labels.
 */
val AutobotTypography = Typography(
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.copy(fontSize = 15.sp, lineHeight = 23.sp, letterSpacing = 0.1.sp),
    bodyMedium = Base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = Base.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(
        fontFamily = CodeFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.4.sp,
        lineHeightStyle = TightLineHeight,
    ),
    labelMedium = TextStyle(
        fontFamily = CodeFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.6.sp,
        lineHeightStyle = TightLineHeight,
    ),
    labelSmall = TextStyle(
        fontFamily = CodeFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.8.sp,
        lineHeightStyle = TightLineHeight,
    ),
)

/** Hard edges: small radii everywhere so panels read as instruments, not cards. */
val AutobotShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

@Immutable
data class AutobotExtraStyles(
    val code: TextStyle,
    val codeBlock: TextStyle,
    /** Uppercase micro labels: section headers, field captions. */
    val micro: TextStyle,
    /** Numeric telemetry (seed, tok/s, steps): tabular mono. */
    val readout: TextStyle,
)

val DefaultExtraStyles = AutobotExtraStyles(
    code = TextStyle(fontFamily = CodeFontFamily, fontSize = 13.5.sp),
    codeBlock = TextStyle(fontFamily = CodeFontFamily, fontSize = 12.5.sp, lineHeight = 18.sp),
    micro = TextStyle(
        fontFamily = CodeFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 1.4.sp,
    ),
    readout = TextStyle(
        fontFamily = CodeFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = "tnum",
    ),
)

/**
 * App theme. Dark-first: [darkTheme] defaults to `true`.
 *
 * @param amoled pure black backgrounds (dark only).
 * @param dynamicColor Material You colors from the wallpaper instead of the underground scheme.
 */
@Composable
fun AutobotTheme(
    darkTheme: Boolean = true,
    amoled: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = remember(darkTheme, amoled, dynamicColor, context) {
        val base = when {
            dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
            dynamicColor -> dynamicLightColorScheme(context)
            darkTheme -> GraphiteDarkColorScheme
            else -> GraphiteLightColorScheme
        }
        if (darkTheme && amoled) base.toAmoled() else base
    }
    MaterialTheme(colorScheme = scheme, typography = AutobotTypography, shapes = AutobotShapes, content = content)
}

/** Extra text styles (monospace code, micro labels, readouts). */
object AutobotTheme {
    val styles: AutobotExtraStyles get() = DefaultExtraStyles
}
