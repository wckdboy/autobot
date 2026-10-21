package dev.wckdboy.autobot.feature.studio.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Studio's control panel leaves the canvas room in a short window (a phone in landscape). */
class ControlSheetHeightTest {
    @Test
    fun aTallWindowKeepsTheFullPanel() {
        assertEquals(300.dp, controlSheetHeight(300.dp, 1200.dp))
    }

    @Test
    fun aPhoneInLandscapeGetsAThirdOfTheWindow() {
        // 2400 × 1080 at 420 dpi on its side: 411 dp tall. The full 300 dp panel left the prompt one line high.
        assertEquals(411.dp * MAX_WINDOW_SHARE, controlSheetHeight(300.dp, 411.dp))
    }

    @Test
    fun anUnknownWindowKeepsTheMaximum() {
        assertEquals(300.dp, controlSheetHeight(300.dp, 0.dp))
    }
}

/** In a short window the control sheet starts folded, so the canvas and the run bar fit. */
class ControlsStartOpenTest {
    @Test
    fun foldedInLandscapeOpenOtherwise() {
        org.junit.Assert.assertFalse(controlsStartOpen(411.dp))
        org.junit.Assert.assertTrue(controlsStartOpen(915.dp))
        org.junit.Assert.assertTrue(controlsStartOpen(0.dp))
    }
}
