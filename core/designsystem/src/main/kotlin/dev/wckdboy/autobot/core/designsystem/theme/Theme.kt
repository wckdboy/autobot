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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wckdboy.autobot.core.designsystem.R
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkTickers
import dev.wckdboy.autobot.core.designsystem.motion.rememberInkTickers
import dev.wckdboy.autobot.core.designsystem.motion.rememberInkMotion

/**
 * The palette: warm greys on ink (dark) or paper (light), and one signal, Autobot's lilac
 * ([AutobotColors.Lilac]), used sparingly. Red stays in the palette for errors and older art.
 */
object AutobotColors {
    val Black = Color(0xFF000000)
    val Ink950 = Color(0xFF0E0E0E)
    val Ink900 = Color(0xFF141414)
    val Ink850 = Color(0xFF1A1A1A)
    val Ink800 = Color(0xFF212121)
    val Ink700 = Color(0xFF292929)
    val Line = Color(0xFF2F2F2E)
    val Ink500 = Color(0xFF4B4945)
    val Ash400 = Color(0xFF8E8A84)
    val Ash200 = Color(0xFFC9C3BA)
    val Bone100 = Color(0xFFF1ECE4)
    val Bone50 = Color(0xFFF6F2EC)

    val Paper = Color(0xFFEFEAE2)
    val PaperLine = Color(0xFFCFC8BC)
    val PaperMuted = Color(0xFF615C56)
    val PaperInk = Color(0xFF141414)

    /** The signal colour. */
    val Red = Color(0xFFE8401C)
    val RedDeep = Color(0xFFB8300F)
    val RedDim = Color(0xFF3A140A)

    /** Autobot's signal: lilac on ink, a deeper lilac on paper (6:1), and a dim fill. */
    /** The mark: Autobot's pixel a and its icon, never text. */
    val Purple = Color(0xFF7C5CFF)
    val Lilac = Color(0xFF9373FF)
    val LilacDeep = Color(0xFF5A36E6)
    val LilacDim = Color(0xFF241A4D)

    /** Invincible's signal (the node), on paper and on ink: screens about the node mark it in this. */
    val Orange = Color(0xFFFF8A1E)
    val OrangeLight = Color(0xFFFF8A1E)

    val Amber = Color(0xFFE3A33B)
    val Cyan = Color(0xFF9DB4B0)
    val Error = Color(0xFFFF6A47)

    // Semantic aliases used across features.
    val Acid = Lilac
    val Green = Lilac
    val Signal = Error
    val Uv = Ash200
    val Violet = Ash200
}

val GraphiteDarkColorScheme: ColorScheme = darkColorScheme(
    // Lilac reads on ink (5:1); ink on lilac blocks (5:1).
    primary = AutobotColors.Lilac,
    onPrimary = AutobotColors.Ink950,
    primaryContainer = AutobotColors.LilacDim,
    onPrimaryContainer = Color(0xFFE4DEFF),
    secondary = AutobotColors.Ash200,
    onSecondary = AutobotColors.Ink950,
    secondaryContainer = AutobotColors.Ink700,
    onSecondaryContainer = AutobotColors.Bone100,
    tertiary = AutobotColors.Amber,
    onTertiary = AutobotColors.Ink950,
    error = AutobotColors.Error,
    onError = AutobotColors.Ink950,
    errorContainer = Color(0xFF3D130B),
    onErrorContainer = Color(0xFFFFD9CF),
    background = AutobotColors.Ink950,
    onBackground = AutobotColors.Bone100,
    surface = AutobotColors.Ink950,
    onSurface = AutobotColors.Bone100,
    surfaceVariant = AutobotColors.Ink700,
    onSurfaceVariant = AutobotColors.Ash400,
    surfaceContainerLowest = AutobotColors.Black,
    surfaceContainerLow = AutobotColors.Ink900,
    surfaceContainer = AutobotColors.Ink850,
    surfaceContainerHigh = AutobotColors.Ink800,
    surfaceContainerHighest = AutobotColors.Ink700,
    surfaceBright = AutobotColors.Ink700,
    surfaceDim = AutobotColors.Black,
    outline = AutobotColors.Ink500,
    outlineVariant = AutobotColors.Line,
    inverseSurface = AutobotColors.Bone100,
    inverseOnSurface = AutobotColors.Ink900,
    inversePrimary = AutobotColors.LilacDeep,
    scrim = AutobotColors.Black,
)

val GraphiteLightColorScheme: ColorScheme = lightColorScheme(
    // Deep lilac on paper: 6:1 on paper, 4.5:1 and up on every panel.
    primary = AutobotColors.LilacDeep,
    onPrimary = AutobotColors.Bone50,
    primaryContainer = Color(0xFFE4DEFF),
    onPrimaryContainer = Color(0xFF1B1240),
    secondary = Color(0xFF3A3632),
    onSecondary = AutobotColors.Bone50,
    secondaryContainer = Color(0xFFDDD6CB),
    onSecondaryContainer = AutobotColors.PaperInk,
    tertiary = Color(0xFF7E5512),
    error = Color(0xFFB3250D),
    background = AutobotColors.Paper,
    onBackground = AutobotColors.PaperInk,
    surface = AutobotColors.Paper,
    onSurface = AutobotColors.PaperInk,
    surfaceVariant = Color(0xFFE2DBD0),
    onSurfaceVariant = AutobotColors.PaperMuted,
    surfaceContainerLowest = Color(0xFFF7F3ED),
    surfaceContainerLow = Color(0xFFE9E3DA),
    surfaceContainer = Color(0xFFE3DCD2),
    surfaceContainerHigh = Color(0xFFDCD5CA),
    surfaceContainerHighest = Color(0xFFD4CCBF),
    outline = Color(0xFFA8A096),
    outlineVariant = AutobotColors.PaperLine,
    inverseSurface = AutobotColors.Ink900,
    inverseOnSurface = AutobotColors.Bone100,
)

/** Pure-black variant for OLED panels: every background-ish surface becomes #000. */
fun ColorScheme.toAmoled(): ColorScheme = copy(
    background = AutobotColors.Black,
    surface = AutobotColors.Black,
    surfaceContainerLowest = AutobotColors.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF111111),
    surfaceDim = AutobotColors.Black,
)

private fun antonio(weight: Int) = Font(
    R.font.antonio,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private fun mono(weight: Int) = Font(
    R.font.jetbrains_mono,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Tall condensed display face (Antonio, OFL) for titles and big numbers. */
val DisplayFontFamily: FontFamily = FontFamily(antonio(200), antonio(300), antonio(400), antonio(500), antonio(600))

/** Monospace (JetBrains Mono, OFL) for labels, telemetry and code. */
val CodeFontFamily: FontFamily = FontFamily(mono(400), mono(500), mono(600))

private val Tight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

private val Base = Typography()

private fun heading(size: Int, line: Int, tracking: Double) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
    lineHeightStyle = Tight,
)

private fun label(size: Double, line: Int, tracking: Double, weight: FontWeight = FontWeight.Medium) = TextStyle(
    fontFamily = CodeFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
    lineHeightStyle = Tight,
)

/**
 * Headings in the system sans (semibold, tight), sans for reading, mono for every label. The
 * condensed display face is kept for the few places that print a single large word.
 */
val AutobotTypography = Typography(
    displayLarge = heading(44, 50, -0.8),
    displayMedium = heading(36, 42, -0.6),
    displaySmall = heading(30, 36, -0.4),
    headlineLarge = heading(26, 32, -0.3),
    headlineMedium = heading(22, 28, -0.2),
    headlineSmall = heading(19, 24, -0.1),
    titleLarge = heading(18, 24, 0.0),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = 0.sp),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.Medium),
    bodyLarge = Base.bodyLarge.copy(fontSize = 15.sp, lineHeight = 23.sp, letterSpacing = 0.1.sp),
    bodyMedium = Base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = Base.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = label(13.0, 16, 0.0, FontWeight.SemiBold),
    labelMedium = label(12.0, 16, 0.0),
    labelSmall = label(11.0, 14, 0.0),
)

/** Hard edges: small radii so cards read as printed blocks. */
val AutobotShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(10.dp),
)

@Immutable
data class AutobotExtraStyles(
    val code: TextStyle,
    val codeBlock: TextStyle,
    /** Section labels, captions and meta: sentence-case mono, untracked (`Recent runs`, `Steps`). */
    val micro: TextStyle,
    /** Button and action labels: sentence-case mono, untracked ("Generate", "New"). */
    val action: TextStyle,
    /** Bottom tab labels: small sentence-case mono, untracked. */
    val tab: TextStyle,
    /** Small numeric telemetry (tok/s, sizes): tabular mono. */
    val readout: TextStyle,
    /** Big numbers (STEPS 4, SEED 4182): tabular mono. */
    val number: TextStyle,
)

val DefaultExtraStyles = AutobotExtraStyles(
    code = TextStyle(fontFamily = CodeFontFamily, fontSize = 13.sp, lineHeight = 18.sp),
    codeBlock = TextStyle(fontFamily = CodeFontFamily, fontSize = 12.sp, lineHeight = 17.sp),
    micro = TextStyle(
        fontFamily = CodeFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
    action = TextStyle(
        fontFamily = CodeFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = Tight,
    ),
    tab = TextStyle(
        fontFamily = CodeFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = Tight,
    ),
    readout = TextStyle(
        fontFamily = CodeFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.5.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = "tnum",
    ),
    number = TextStyle(
        fontFamily = CodeFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        fontFeatureSettings = "tnum",
        lineHeightStyle = Tight,
    ),
)

/**
 * App theme. Dark (ink) by default; light is "paper".
 *
 * @param amoled pure black backgrounds (dark only).
 * @param dynamicColor Material You colors from the wallpaper instead of the riso palette.
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
    MaterialTheme(colorScheme = scheme, typography = AutobotTypography, shapes = AutobotShapes) {
        CompositionLocalProvider(LocalInkMotion provides rememberInkMotion()) {
            // One frame loop per motion tier for the whole window (nested themes reuse the outer one).
            CompositionLocalProvider(LocalInkTickers provides (LocalInkTickers.current ?: rememberInkTickers()), content = content)
        }
    }
}

/**
 * Paper (light) surface regardless of the app theme (Imagine, the Studio library): dark system
 * bar icons while shown.
 */
@Composable
fun PaperTheme(content: @Composable () -> Unit) {
    SystemBarsFor(lightSurface = true)
    MaterialTheme(colorScheme = GraphiteLightColorScheme, typography = AutobotTypography, shapes = AutobotShapes, content = content)
}

/**
 * The darkroom: ink (dark) surface regardless of the app theme — the Studio's image and video
 * rooms, where the picture is the only bright thing on screen. Light system bar icons while shown.
 */
@Composable
fun DarkroomTheme(content: @Composable () -> Unit) {
    SystemBarsFor(lightSurface = false)
    MaterialTheme(colorScheme = GraphiteDarkColorScheme, typography = AutobotTypography, shapes = AutobotShapes, content = content)
}



/**
 * The accent colours as text and glyphs. The palette accents ([AutobotColors.Amber], Cyan, Uv,
 * Signal) are for fills and halftone dots; on paper they are under 2:1, so a word or an icon in an
 * accent takes the ink for the ground it sits on (4.5:1 on that theme's background and panels).
 * Red text is the scheme's `primary`.
 */
@Immutable
data class AutobotInks(val amber: Color, val cyan: Color, val uv: Color, val signal: Color)

val DarkInks = AutobotInks(amber = Color(0xFFE3A33B), cyan = Color(0xFF9DB4B0), uv = Color(0xFFC9C3BA), signal = Color(0xFFFF6A47))
val LightInks = AutobotInks(amber = Color(0xFF7E5512), cyan = Color(0xFF4B615D), uv = Color(0xFF655C4F), signal = Color(0xFFB3250D))

/** Extra text styles (mono code, micro labels, readouts, display numbers). */
object AutobotTheme {
    val styles: AutobotExtraStyles get() = DefaultExtraStyles

    /** Invincible's orange for the current ground: the mark on anything about the node. */
    val node: Color
        @Composable @ReadOnlyComposable
        get() = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) AutobotColors.OrangeLight else AutobotColors.Orange

    /** Accent inks for the current ground (follows nested Paper and Darkroom themes). */
    val inks: AutobotInks
        @Composable @ReadOnlyComposable
        get() = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) DarkInks else LightInks
}
