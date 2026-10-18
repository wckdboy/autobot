package dev.wckdboy.autobot.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import dev.wckdboy.autobot.core.designsystem.largetext.LargeTextAudit
import dev.wckdboy.autobot.core.designsystem.largetext.LargeTextAudit.Themed
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings at the default and the largest system font, on a narrow and a common phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LargeTextTest {
    @get:Rule
    val compose = createComposeRule()

    private fun audit(width: Int, scale: Float) = LargeTextAudit.render(compose, "settings", width, scale, heightDp = 3200) {
        Themed {
            SettingsScreen(
                state = SettingsUiState(networkLabel = "Tor"),
                onBack = null, onOpenPrivacyCenter = {}, onThemeMode = {}, onAmoled = {},
                onDynamicColor = {}, onAppLock = {}, onLockTimeout = {}, onIncognitoByDefault = {},
                onOpenAbout = {},
                about = aboutRowTitle("1.7.1", 20),
            )
        }
    }

    @Test fun defaultTextFits() = assertEquals(emptyList<LargeTextAudit.Finding>(), audit(411, 1f))

    @Test fun largestTextFitsOnANarrowPhone() = assertEquals(emptyList<LargeTextAudit.Finding>(), audit(360, 2f))
}
