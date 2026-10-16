package dev.wckdboy.autobot.core.designsystem.theme

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * System bar icons follow the surface on screen. Surfaces overlap while one screen replaces
 * another (a navigation transition): the one that leaves last must not restore its stale value
 * over the one now showing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemBarsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun darkIcons(): Boolean {
        val window = compose.activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        assertEquals(controller.isAppearanceLightStatusBars, controller.isAppearanceLightNavigationBars)
        return controller.isAppearanceLightStatusBars
    }

    @Test
    fun theSurfaceShownLastWinsWhateverOrderTheOthersLeaveIn() {
        var lightApp by mutableStateOf(true)
        var paper by mutableStateOf(true)
        var darkroom by mutableStateOf(false)
        compose.setContent {
            SystemBarsBase(lightSurface = lightApp)
            if (paper) PaperTheme { Text("paper") }
            if (darkroom) DarkroomTheme { Text("darkroom") }
        }
        compose.waitForIdle()
        assertTrue("paper: dark icons", darkIcons())

        // Imagine (paper) → Studio's image room (darkroom): the darkroom enters while paper is still shown.
        darkroom = true
        compose.waitForIdle()
        assertFalse(darkIcons())
        paper = false
        compose.waitForIdle()
        assertFalse("paper leaving restored its stale appearance over the darkroom", darkIcons())

        // Back to the app's own (light) surface, then the app turns dark.
        darkroom = false
        compose.waitForIdle()
        assertTrue(darkIcons())
        lightApp = false
        compose.waitForIdle()
        assertFalse(darkIcons())

        // A theme change underneath a darkroom does not repaint over it.
        lightApp = true
        darkroom = true
        compose.waitForIdle()
        assertFalse(darkIcons())
    }

    @Test
    fun requestsStackWithTheNewestOnTop() {
        val applied = mutableListOf<Boolean>()
        val bars = SystemBarRequests { applied += it }
        bars.setBase(lightSurface = true)
        val a = Any()
        val b = Any()
        bars.push(a, lightSurface = true)
        bars.push(b, lightSurface = false)
        bars.pop(a)
        assertFalse(bars.lightSurface())
        bars.pop(b)
        assertTrue(bars.lightSurface())
        bars.setBase(lightSurface = false)
        assertEquals(listOf(true, true, false, false, true, false), applied)
    }
}
