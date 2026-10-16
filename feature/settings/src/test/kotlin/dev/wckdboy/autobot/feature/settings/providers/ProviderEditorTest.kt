package dev.wckdboy.autobot.feature.settings.providers

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import dev.wckdboy.autobot.core.designsystem.motion.InkMotion
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.providers.remote.ProviderPresets
import java.lang.reflect.Proxy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The add-provider sheet shows why a provider cannot work before it is saved. One sheet per JVM
 * (the module forks per class): Robolectric never settles a second dialog window in one JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2400dp-xhdpi")
class ProviderEditorTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions = Proxy.newProxyInstance(ProvidersActions::class.java.classLoader, arrayOf(ProvidersActions::class.java)) { _, _, _ -> null } as ProvidersActions

    @Test
    fun validationErrorsAreShownWithTheirFix() {
        val draft = ProviderDraft.fromPreset(ProviderPresets.byId(ProviderPresets.DEEPSEEK)!!).copy(
            baseUrl = "http://192.168.1.20:8080/v1",
            defaultModel = "",
            apiKeyInput = "sk-abc 123",
        )
        compose.setContent {
            AutobotTheme {
                CompositionLocalProvider(LocalInkMotion provides InkMotion(reduced = true)) {
                    ProvidersScreen(ProvidersUiState(editor = draft, error = "Could not save: disk I/O error"), actions)
                }
            }
        }
        // Cleartext to a LAN host: under the URL, and again as the reason Save is off.
        compose.onAllNodes(hasText("Use https — plain http only works for servers on this phone", substring = true))[0].performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("The API key contains a space — paste only the key").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Could not save: disk I/O error").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Save", ignoreCase = true) and hasClickAction()).performScrollTo().assertIsNotEnabled()
    }
}
