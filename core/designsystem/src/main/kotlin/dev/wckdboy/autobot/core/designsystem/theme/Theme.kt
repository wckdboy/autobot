package dev.wckdboy.autobot.core.designsystem.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.wckdboy.autobot.core.designsystem.R

/**
 * Riso palette: one signal red printed on ink (dark) or paper (light), with warm greys. Red is
 * the brand and "live / on this phone" colour, not an error colour by itself.
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
    val PaperMuted = Color(0xFF6F6A63)
    val PaperInk = Color(0xFF141414)

    /** The signal colour. */
    val Red = Color(0xFFE8401C)
    val RedDeep = Color(0xFFB8300F)
    val RedDim = Color(0xFF3A140A)

    val Amber = Color(0xFFE3A33B)
    val Cyan = Color(0xFF9DB4B0)
    val Error = Color(0xFFFF6A47)

    // Semantic aliases used across features.
    val Acid = Red
    val Green = Red
    val Signal = Error
    val Uv = Ash200
    val Violet = Ash200
}

val GraphiteDarkColorScheme: ColorScheme = darkColorScheme(
    primary = AutobotColors.Red,
    onPrimary = AutobotColors.Bone100,
    primaryContainer = AutobotColors.RedDim,
    onPrimaryContainer = Color(0xFFFFD9CC),
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
    inversePrimary = AutobotColors.RedDeep,
    scrim = AutobotColors.Black,
)

val GraphiteLightColorScheme: ColorScheme = lightColorScheme(
    primary = AutobotColors.Red,
    onPrimary = AutobotColors.Bone50,
    primaryContainer = Color(0xFFFFDCD0),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFF3A3632),
    onSecondary = AutobotColors.Bone50,
    secondaryContainer = Color(0xFFDDD6CB),
    onSecondaryContainer = AutobotColors.PaperInk,
    tertiary = Color(0xFF8A5A00),
    error = Color(0xFFC0280E),
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

private fun display(size: Int, line: Int, weight: Int, tracking: Double = -0.5) = TextStyle(
    fontFamily = DisplayFontFamily,
    fontWeight = FontWeight(weight),
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

/** Display for headlines, sans for reading, mono for every label. */
val AutobotTypography = Typography(
    displayLarge = display(72, 70, 300, -1.0),
    displayMedium = display(58, 58, 300, -0.8),
    displaySmall = display(44, 46, 300, -0.5),
    headlineLarge = display(36, 40, 300),
    headlineMedium = display(30, 34, 400),
    headlineSmall = display(25, 29, 400, -0.2),
    titleLarge = display(22, 26, 500, 0.4),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = 0.sp),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.Medium),
    bodyLarge = Base.bodyLarge.copy(fontSize = 15.sp, lineHeight = 23.sp, letterSpacing = 0.1.sp),
    bodyMedium = Base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = Base.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = label(12.5, 16, 1.2, FontWeight.SemiBold),
    labelMedium = label(11.0, 14, 0.9),
    labelSmall = label(10.0, 13, 1.0),
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
    /** Uppercase micro labels: `// SECTION`, field captions. */
    val micro: TextStyle,
    /** Small numeric telemetry (tok/s, sizes): tabular mono. */
    val readout: TextStyle,
    /** Big numbers (STEPS 4, SEED 4182): condensed display. */
    val number: TextStyle,
)

val DefaultExtraStyles = AutobotExtraStyles(
    code = TextStyle(fontFamily = CodeFontFamily, fontSize = 13.sp, lineHeight = 18.sp),
    codeBlock = TextStyle(fontFamily = CodeFontFamily, fontSize = 12.sp, lineHeight = 17.sp),
    micro = TextStyle(
        fontFamily = CodeFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.7.sp,
    ),
    readout = TextStyle(
        fontFamily = CodeFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.5.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = "tnum",
    ),
    number = display(30, 32, 400, 0.0),
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
    MaterialTheme(colorScheme = scheme, typography = AutobotTypography, shapes = AutobotShapes, content = content)
}

/**
 * Paper (light) surface regardless of the app theme — used by the image studio. Switches the
 * status bar to dark icons while shown, and restores the previous appearance afterwards.
 */
@Composable
fun PaperTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(view) {
            val window = view.context.findActivity()?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            val previous = controller?.isAppearanceLightStatusBars
            controller?.isAppearanceLightStatusBars = true
            onDispose { if (controller != null && previous != null) controller.isAppearanceLightStatusBars = previous }
        }
    }
    MaterialTheme(colorScheme = GraphiteLightColorScheme, typography = AutobotTypography, shapes = AutobotShapes, content = content)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Extra text styles (mono code, micro labels, readouts, display numbers). */
object AutobotTheme {
    val styles: AutobotExtraStyles get() = DefaultExtraStyles
}
