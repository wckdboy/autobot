package dev.wckdboy.autobot.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `shared/design/tokens.json` is the design source of truth for both apps (iOS generates its
 * tokens from it). This keeps the hand-written Compose theme identical to it.
 */
class TokensParityTest {
    private val tokens: JsonObject = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "shared/design/tokens.json") }
        .first { it.isFile }
        .let { Json.parseToJsonElement(it.readText()).jsonObject }

    private fun hex(color: Color) = "#%06X".format(color.toArgb() and 0xFFFFFF)

    private fun group(name: String) = tokens.getValue(name).jsonObject

    private val palette: Map<String, Color> = with(AutobotColors) {
        mapOf(
            "black" to Black, "ink950" to Ink950, "ink900" to Ink900, "ink850" to Ink850, "ink800" to Ink800,
            "ink700" to Ink700, "line" to Line, "ink500" to Ink500, "ash400" to Ash400, "ash200" to Ash200,
            "bone100" to Bone100, "bone50" to Bone50, "paper" to Paper, "paperLine" to PaperLine,
            "paperMuted" to PaperMuted, "paperInk" to PaperInk, "red" to Red, "redDeep" to RedDeep,
            "redDim" to RedDim, "amber" to Amber, "cyan" to Cyan, "error" to Error,
            "verdigris" to Verdigris, "verdigrisLight" to VerdigrisLight,
            "lilac" to Lilac, "lilacDeep" to LilacDeep, "lilacDim" to LilacDim,
        )
    }

    private fun roles(s: ColorScheme): Map<String, Color> = mapOf(
        "primary" to s.primary, "onPrimary" to s.onPrimary, "primaryContainer" to s.primaryContainer,
        "onPrimaryContainer" to s.onPrimaryContainer, "secondary" to s.secondary, "onSecondary" to s.onSecondary,
        "secondaryContainer" to s.secondaryContainer, "onSecondaryContainer" to s.onSecondaryContainer,
        "tertiary" to s.tertiary, "onTertiary" to s.onTertiary, "tertiaryContainer" to s.tertiaryContainer,
        "onTertiaryContainer" to s.onTertiaryContainer, "error" to s.error, "onError" to s.onError,
        "errorContainer" to s.errorContainer, "onErrorContainer" to s.onErrorContainer, "background" to s.background,
        "onBackground" to s.onBackground, "surface" to s.surface, "onSurface" to s.onSurface,
        "surfaceVariant" to s.surfaceVariant, "onSurfaceVariant" to s.onSurfaceVariant,
        "surfaceContainerLowest" to s.surfaceContainerLowest, "surfaceContainerLow" to s.surfaceContainerLow,
        "surfaceContainer" to s.surfaceContainer, "surfaceContainerHigh" to s.surfaceContainerHigh,
        "surfaceContainerHighest" to s.surfaceContainerHighest, "surfaceBright" to s.surfaceBright,
        "surfaceDim" to s.surfaceDim, "surfaceTint" to s.surfaceTint, "outline" to s.outline,
        "outlineVariant" to s.outlineVariant, "inverseSurface" to s.inverseSurface,
        "inverseOnSurface" to s.inverseOnSurface, "inversePrimary" to s.inversePrimary, "scrim" to s.scrim,
    )

    private fun assertScheme(name: String, scheme: ColorScheme) {
        val actual = roles(scheme)
        group(name).forEach { (role, value) ->
            val color = actual[role] ?: error("unknown colour role '$role' in tokens.json $name")
            assertEquals("$name.$role", value.jsonPrimitive.content.uppercase(), hex(color))
        }
    }

    @Test
    fun paletteMatches() {
        group("palette").forEach { (name, value) ->
            assertEquals("palette.$name", value.jsonPrimitive.content.uppercase(), hex(palette.getValue(name)))
        }
        assertEquals(palette.keys, group("palette").keys)
    }

    @Test
    fun colorSchemesMatch() {
        assertScheme("dark", GraphiteDarkColorScheme)
        assertScheme("light", GraphiteLightColorScheme)
        assertScheme("amoled", GraphiteDarkColorScheme.toAmoled())
    }

    @Test
    fun inksMatch() {
        for ((name, inks) in listOf("dark" to DarkInks, "light" to LightInks)) {
            val actual = mapOf("amber" to inks.amber, "cyan" to inks.cyan, "uv" to inks.uv, "signal" to inks.signal)
            val expected = group("inks").getValue(name).jsonObject
            assertEquals("inks.$name keys", expected.keys, actual.keys)
            expected.forEach { (k, v) -> assertEquals("inks.$name.$k", v.jsonPrimitive.content.uppercase(), hex(actual.getValue(k))) }
        }
    }

    @Test
    fun typeScaleMatches() {
        val styles: Map<String, TextStyle> = with(AutobotTypography) {
            mapOf(
                "displayLarge" to displayLarge, "displayMedium" to displayMedium, "displaySmall" to displaySmall,
                "headlineLarge" to headlineLarge, "headlineMedium" to headlineMedium, "headlineSmall" to headlineSmall,
                "titleLarge" to titleLarge, "titleMedium" to titleMedium, "titleSmall" to titleSmall,
                "bodyLarge" to bodyLarge, "bodyMedium" to bodyMedium, "bodySmall" to bodySmall,
                "labelLarge" to labelLarge, "labelMedium" to labelMedium, "labelSmall" to labelSmall,
            )
        }
        val mismatches = styles.flatMap { (name, style) ->
            val t = group("type").getValue(name).jsonObject
            listOf(
                Triple("size", t.getValue("size").jsonPrimitive.double, style.fontSize.value.toDouble()),
                Triple("lineHeight", t.getValue("lineHeight").jsonPrimitive.double, style.lineHeight.value.toDouble()),
                Triple("letterSpacing", t.getValue("letterSpacing").jsonPrimitive.double, style.letterSpacing.value.toDouble()),
                Triple("weight", t.getValue("weight").jsonPrimitive.int.toDouble(), (style.fontWeight?.weight ?: 400).toDouble()),
            ).filter { (_, expected, actual) -> kotlin.math.abs(expected - actual) > 0.01 }
                .map { (field, expected, actual) -> "type.$name.$field: tokens.json $expected, Theme.kt ${"%.2f".format(actual)}" }
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    @Test
    fun radiiMatch() {
        val shapes = with(AutobotShapes) { mapOf("extraSmall" to extraSmall, "small" to small, "medium" to medium, "large" to large, "extraLarge" to extraLarge) }
        shapes.forEach { (name, shape) ->
            val px = shape.topStart.toPx(Size(1000f, 1000f), Density(1f))
            assertEquals("radius.$name", group("radius").getValue(name).jsonPrimitive.double, px.toDouble(), 0.01)
        }
    }

    @Test
    fun motionMatches() {
        val motion = group("motion")
        assertEquals("motion token names", motion.keys, Motion.all.keys)
        motion.forEach { (name, value) ->
            assertEquals("motion.$name", value.jsonPrimitive.double, Motion.all.getValue(name).toDouble(), 0.0001)
        }
    }
}
