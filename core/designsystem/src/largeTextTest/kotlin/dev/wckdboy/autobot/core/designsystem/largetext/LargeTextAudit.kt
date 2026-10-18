package dev.wckdboy.autobot.core.designsystem.largetext

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.motion.InkMotion
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import java.io.File
import org.robolectric.RuntimeEnvironment

/**
 * Renders a screen at a phone width and a system font scale (Android's own non-linear scaling,
 * as a user who set "largest" text sees it) and lists the text that does not fit: text cut by its
 * height, lines ended with an ellipsis, and text clipped by a parent. Each render is also kept as a
 * PNG under the module's `build/large-text/` for review.
 *
 * Shared by the feature modules' `LargeTextTest`s (added to their test sources in Gradle).
 */
object LargeTextAudit {
    data class Finding(val text: String, val problem: String) {
        override fun toString() = "\"$text\": $problem"
    }

    /**
     * An instance of [T] made without its constructor: a screen that takes its view model only for
     * the callbacks of its buttons renders with this, as nothing calls it until a tap.
     */
    inline fun <reified T> uncalled(): T {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, T::class.java) as T
    }

    /** Sets the window to [widthDp] × [heightDp] at xxhdpi with [fontScale]; call before setContent. */
    fun configure(widthDp: Int, fontScale: Float, heightDp: Int = 1600) {
        RuntimeEnvironment.setQualifiers("w${widthDp}dp-h${heightDp}dp-xxhdpi")
        RuntimeEnvironment.setFontScale(fontScale)
    }

    /** The app theme with still ink (no animation frames to wait for). */
    @Composable
    fun Themed(dark: Boolean = true, content: @Composable () -> Unit) {
        AutobotTheme(darkTheme = dark) { CompositionLocalProvider(LocalInkMotion provides InkMotion(reduced = true), content = content) }
    }

    /**
     * Renders [content] and returns what does not fit. [heightDp] is tall by default so a whole
     * list shows in one PNG; a screen that does not scroll is rendered at a real phone's height.
     */
    fun render(compose: ComposeContentTestRule, name: String, widthDp: Int, fontScale: Float, heightDp: Int = 1600, content: @Composable () -> Unit): List<Finding> {
        configure(widthDp, fontScale, heightDp)
        compose.setContent { content() }
        compose.waitForIdle()
        save(compose, "$name-w$widthDp-x${"%.1f".format(fontScale)}")
        return findings(compose)
    }

    fun save(compose: ComposeContentTestRule, name: String) {
        val dir = File(System.getProperty("autobot.largeTextDir") ?: "build/large-text").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Text that overflows, is ellipsized, is clipped by an ancestor inside the screen, or overlaps other text. */
    fun findings(compose: ComposeContentTestRule): List<Finding> {
        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val screen = root.boundsInRoot
        val out = mutableListOf<Finding>()
        val shownText = mutableListOf<Pair<String, SemanticsNode>>()
        fun visit(node: SemanticsNode, scrollers: List<Rect>) {
            val scrolls = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null ||
                node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null
            val nextScrollers = if (scrolls) scrollers + node.boundsInRoot else scrollers
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            if (!text.isNullOrBlank()) {
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                layouts.firstOrNull()?.let { l ->
                    val ellipsized = (0 until l.lineCount).any { l.isLineEllipsized(it) }
                    when {
                        ellipsized -> out += Finding(text, "ellipsized")
                        l.multiParagraph.didExceedMaxLines -> out += Finding(text, "lines cut off")
                        l.multiParagraph.height > l.size.height + 1f -> out += Finding(text, "cut by its height")
                    }
                    // A box too narrow for one word wraps it mid-word ("INCOGN / ITO").
                    val laid = l.layoutInput.text.text
                    for (line in 0 until l.lineCount - 1) {
                        val end = l.getLineEnd(line)
                        if (end in 1 until laid.length && laid[end - 1].isLetterOrDigit() && laid[end].isLetterOrDigit()) {
                            out += Finding(text, "a word broken across lines")
                            break
                        }
                    }
                }
                val full = Rect(node.positionInRoot, node.size.let { androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) })
                val shown = node.boundsInRoot
                val slack = with(compose.density) { 1.dp.toPx() }
                val onScreen = full.left >= screen.left - slack && full.right <= screen.right + slack &&
                    full.top >= screen.top - slack && full.bottom <= screen.bottom + slack
                val clipped = shown.width < full.width - slack || shown.height < full.height - slack
                // A scrolling list legitimately cuts the row at its edge.
                val atScrollEdge = nextScrollers.any { s ->
                    kotlin.math.abs(shown.top - s.top) < slack || kotlin.math.abs(shown.bottom - s.bottom) < slack ||
                        kotlin.math.abs(shown.left - s.left) < slack || kotlin.math.abs(shown.right - s.right) < slack
                }
                if (onScreen && clipped && !atScrollEdge && !shown.isEmpty) out += Finding(text, "clipped by a parent (${shown.size} of ${full.size})")
                if (!shown.isEmpty) shownText += text to node
            }
            node.children.forEach { visit(it, nextScrollers) }
        }
        visit(root, emptyList())
        // Two texts drawn over each other (neither inside the other), by more than a few dp each way.
        val overlap = with(compose.density) { 3.dp.toPx() }
        fun SemanticsNode.isAncestorOf(other: SemanticsNode): Boolean = generateSequence(other.parent) { it.parent }.any { it.id == id }
        for (i in shownText.indices) for (j in i + 1 until shownText.size) {
            val (ta, a) = shownText[i]
            val (tb, b) = shownText[j]
            val x = a.boundsInRoot.intersect(b.boundsInRoot)
            if (x.width > overlap && x.height > overlap && !a.isAncestorOf(b) && !b.isAncestorOf(a)) out += Finding(ta, "overlaps \"$tb\"")
        }
        return (out + targets(compose)).distinct()
    }

    /**
     * What TalkBack and a finger need from every control: something to read out (a text or a
     * content description) and a touch area of at least 48 × 48 dp (Compose's own expansion of a
     * small target counts).
     */
    fun targets(compose: ComposeContentTestRule): List<Finding> {
        val root = compose.onRoot(useUnmergedTree = false).fetchSemanticsNode()
        val screen = root.boundsInRoot
        val min = with(compose.density) { 48.dp.toPx() - 1f }
        val out = mutableListOf<Finding>()
        fun visit(node: SemanticsNode) {
            val c = node.config
            if (c.getOrNull(SemanticsActions.OnClick) != null && c.getOrNull(SemanticsProperties.Disabled) == null) {
                val label = c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
                    ?: c.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
                    ?: c.getOrNull(SemanticsProperties.EditableText)?.text
                val shown = node.boundsInRoot
                // Only controls wholly on screen: a row cut by the window's edge is scrolled to first.
                val whole = !shown.isEmpty && shown.left >= screen.left && shown.right <= screen.right && shown.top >= screen.top && shown.bottom <= screen.bottom &&
                    shown.width >= node.size.width - 1f && shown.height >= node.size.height - 1f
                val inside = node.positionInRoot.x >= screen.left && node.positionInRoot.x < screen.right &&
                    node.positionInRoot.y >= screen.top && node.positionInRoot.y < screen.bottom
                if (inside && (node.size.width == 0 || node.size.height == 0)) {
                    out += Finding(label ?: "control #${node.id}", "squeezed to nothing")
                } else if (label.isNullOrBlank()) {
                    out += Finding(c.getOrNull(SemanticsProperties.TestTag) ?: "control #${node.id}", "a control with nothing to read out")
                } else if (whole) {
                    val touch = node.touchBoundsInRoot
                    if (touch.width < min || touch.height < min) {
                        val dp = with(compose.density) { "${touch.width.toDp().value.toInt()}×${touch.height.toDp().value.toInt()} dp" }
                        out += Finding(label, "touch target $dp")
                    }
                }
            }
            node.children.forEach(::visit)
        }
        visit(root)
        return out
    }
}
