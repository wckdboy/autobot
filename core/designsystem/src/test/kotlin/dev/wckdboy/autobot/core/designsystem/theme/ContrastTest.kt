package dev.wckdboy.autobot.core.designsystem.theme

import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WCAG 2.2 contrast for every text and glyph colour pair the two apps draw, read from
 * `shared/design/tokens.json` (so it holds for iOS too, which generates its colours from the same
 * file). Body text needs 4.5:1 (AA 1.4.3), large text and icons 3:1 (AA 1.4.11).
 *
 * Grounds are where text sits: the screen, the panels (`surfaceContainer`), notices and sheets
 * (`surfaceContainerHigh`). The vivid palette accents (`palette.amber`, `cyan`, `ash200`, `error`)
 * are fills and halftone dots only; words and icons in an accent use `inks`.
 */
class ContrastTest {
    private val tokens: JsonObject = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "shared/design/tokens.json") }
        .first { it.isFile }
        .let { Json.parseToJsonElement(it.readText()).jsonObject }

    private fun group(vararg path: String): Map<String, String> =
        path.fold(tokens) { o, k -> o.getValue(k).jsonObject }.mapValues { it.value.jsonPrimitive.content }

    private fun scheme(name: String): Map<String, String> = when (name) {
        "light" -> group("lightMaterialDefaults") + group("light")
        "amoled" -> group("dark") + group("amoled")
        else -> group(name)
    }

    private fun inks(name: String) = group("inks", if (name == "light") "light" else "dark").mapKeys { "inks.${it.key}" }

    private data class Pair(val theme: String, val fg: String, val bg: String, val fgHex: String, val bgHex: String, val min: Double) {
        val ratio = contrast(fgHex, bgHex)
        override fun toString() = "%-6s %-22s on %-24s %s/%s %5.2f (min %.1f)".format(theme, fg, bg, fgHex, bgHex, ratio, min)
    }

    private fun pairs(): List<Pair> = listOf("dark", "amoled", "light").flatMap { theme ->
        val s = scheme(theme) + inks(theme)
        val grounds = listOf("background", "surface", "surfaceContainerLowest", "surfaceContainerLow", "surfaceContainer", "surfaceContainerHigh")
        val text = listOf("onSurface", "onBackground", "onSurfaceVariant", "primary", "secondary", "tertiary", "error") + inks(theme).keys
        val onPairs = listOf(
            "onPrimary" to "primary", "onPrimaryContainer" to "primaryContainer", "onSecondary" to "secondary",
            "onSecondaryContainer" to "secondaryContainer", "onTertiary" to "tertiary", "onError" to "error",
            "onErrorContainer" to "errorContainer", "inverseOnSurface" to "inverseSurface",
        )
        fun p(fg: String, bg: String, min: Double) = Pair(theme, fg, bg, s.getValue(fg), s.getValue(bg), min)
        text.flatMap { fg -> grounds.map { bg -> p(fg, bg, 4.5) } } +
            // Code blocks: body text on the highest container; icons and large text there need 3:1.
            p("onSurface", "surfaceContainerHighest", 4.5) +
            (text - "onSurface").map { p(it, "surfaceContainerHighest", 3.0) } +
            onPairs.map { (fg, bg) -> p(fg, bg, 4.5) }
    } + listOf(
        // The cream PaperButton (APPROVE on the red approval card).
        Pair("any", "paperInk", "bone100", group("palette").getValue("paperInk"), group("palette").getValue("bone100"), 4.5),
    )

    @Test
    fun everyTextPairMeetsAa() {
        val all = pairs()
        println(all.joinToString("\n"))
        assertEquals("pairs under WCAG AA", emptyList<String>(), all.filter { it.ratio < it.min }.map { it.toString() })
    }

    @Test
    fun filledTagPicksTheReadableInk() {
        // Filled tags put ink or bone text on the accent; whichever they pick must read.
        val palette = group("palette")
        for (fill in listOf("red", "amber", "cyan", "ash200", "error")) {
            val bg = palette.getValue(fill)
            val best = max(contrast(palette.getValue("paperInk"), bg), contrast(palette.getValue("bone100"), bg))
            assert(best >= 4.5) { "no readable ink on a filled $fill tag ($best)" }
        }
    }

    @Test
    fun luminanceMatchesWcag() {
        assertEquals(21.0, contrast("#000000", "#FFFFFF"), 0.01)
        assertEquals(1.0, contrast("#777777", "#777777"), 0.0001)
    }

    companion object {
        private fun channel(c: Int): Double = (c / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }

        fun luminance(hex: String): Double {
            val v = hex.removePrefix("#").toInt(16)
            return 0.2126 * channel(v shr 16 and 0xFF) + 0.7152 * channel(v shr 8 and 0xFF) + 0.0722 * channel(v and 0xFF)
        }

        fun contrast(a: String, b: String): Double {
            val la = luminance(a)
            val lb = luminance(b)
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }
    }
}
