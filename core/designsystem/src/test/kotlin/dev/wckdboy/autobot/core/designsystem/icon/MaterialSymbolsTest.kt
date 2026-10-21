package dev.wckdboy.autobot.core.designsystem.icon

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Each symbol is drawn inside its viewport and fills most of it: a path copied with the wrong
 * viewport (a 24-unit drawable read as 960) would render as a speck, an invisible icon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaterialSymbolsTest {
    @Test
    fun everySymbolFillsItsViewport() {
        val symbols = listOf(MaterialSymbols.Image, MaterialSymbols.Forum, MaterialSymbols.AutoFixHigh, MaterialSymbols.Code, MaterialSymbols.Stacks)
        symbols.forEach { symbol -> assertFills(symbol) }
    }

    private fun assertFills(symbol: ImageVector) {
        val path = symbol.root.filterIsInstance<VectorPath>().single().pathData.toPath()
        val bounds = path.getBounds()
        val side = symbol.viewportWidth
        assertTrue("${symbol.name} inside its viewport: $bounds", bounds.left >= 0f && bounds.top >= 0f && bounds.right <= side && bounds.bottom <= side)
        assertTrue("${symbol.name} fills its viewport: $bounds", bounds.width >= side * 0.6f && bounds.height >= side * 0.5f)
    }
}
