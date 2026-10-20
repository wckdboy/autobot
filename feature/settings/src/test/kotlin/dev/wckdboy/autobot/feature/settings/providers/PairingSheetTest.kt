package dev.wckdboy.autobot.feature.settings.providers

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.wckdboy.autobot.core.designsystem.motion.InkMotion
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.shared.invincible.NodeErrors
import dev.wckdboy.autobot.shared.invincible.NodePairingWords
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pairing sheet on a short screen (about what is left above a keyboard): a failure is said in
 * words next to Pair, and Pair stays on screen without scrolling. (One sheet per JVM.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h560dp-xhdpi")
class PairingSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = Proxy.newProxyInstance(ProvidersActions::class.java.classLoader, arrayOf(ProvidersActions::class.java)) { _, m, _ ->
        calls += m.name
        null
    } as ProvidersActions

    @Test
    fun aRefusedCodeIsSaidNextToPair() {
        val code = "invincible://pair?v=0&k=uQyVeVbXMZDA2Q2gCJ5_VIwWoV2Ga6xxwpAhyZlk-zw&s=Lajqokzne3AL-IC8EXPKIxLzNCs-0ve5mzVGxy3VXT0&a=127.0.0.1:7802"
        compose.setContent {
            AutobotTheme {
                CompositionLocalProvider(LocalInkMotion provides InkMotion(reduced = true)) {
                    ProvidersScreen(ProvidersUiState(pairing = PairingDraft(code = code, deviceName = "Pixel", error = NodeErrors.CODE_REFUSED)), actions)
                }
            }
        }
        compose.onNodeWithText(NodePairingWords.TITLE).assertIsDisplayed()
        compose.onNodeWithText(NodeErrors.CODE_REFUSED).assertIsDisplayed()
        compose.onNode(hasText(NodePairingWords.PAIR) and hasClickAction()).assertIsDisplayed().performClick()
        assertEquals(listOf("pair"), calls.filter { it == "pair" })
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("dismissPairing"), calls.filter { it == "dismissPairing" })
    }
}
