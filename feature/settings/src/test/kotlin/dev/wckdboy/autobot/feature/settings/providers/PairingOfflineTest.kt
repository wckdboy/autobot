package dev.wckdboy.autobot.feature.settings.providers

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import dev.wckdboy.autobot.shared.invincible.NodePairingWords
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.wckdboy.autobot.core.designsystem.motion.InkMotion
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Pair a node" on a new install: offline mode (the default) would make pairing fail, so the sheet
 * says so and allows the network in one tap. (One sheet per JVM, see [ProviderEditorTest].)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2400dp-xhdpi")
class PairingOfflineTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = Proxy.newProxyInstance(ProvidersActions::class.java.classLoader, arrayOf(ProvidersActions::class.java)) { _, m, _ ->
        calls += m.name
        null
    } as ProvidersActions

    @Test
    fun offlineModeIsSaidWithTheWayOut() {
        compose.setContent {
            AutobotTheme {
                CompositionLocalProvider(LocalInkMotion provides InkMotion(reduced = true)) {
                    ProvidersScreen(ProvidersUiState(pairing = PairingDraft(deviceName = "Pixel"), offline = true), actions)
                }
            }
        }
        compose.onNodeWithText(OFFLINE_LINE).performScrollTo().assertIsDisplayed()
        // An empty code is not an error yet: the instructions above already say where it comes from.
        compose.onAllNodesWithText(NodePairingWords.HOW).assertCountEquals(1)
        compose.onNodeWithText("Allow network").performClick()
        assertEquals(listOf("allowNetwork"), calls.filter { it == "allowNetwork" })
    }
}
