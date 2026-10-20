package dev.wckdboy.autobot.feature.settings.providers

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.data.model.ProviderRouting
import dev.wckdboy.autobot.core.designsystem.motion.InkMotion
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import java.lang.reflect.Proxy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The providers list says which provider is misconfigured, without opening it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProvidersListTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions = Proxy.newProxyInstance(ProvidersActions::class.java.classLoader, arrayOf(ProvidersActions::class.java)) { _, _, _ -> null } as ProvidersActions

    private fun row(id: String, name: String, hasKey: Boolean, problem: String? = null, models: String? = null) =
        ProviderRow(id, name, ProviderKind.OPENAI_COMPATIBLE, name, "https://api.$id.com/v1", "model", ProviderRouting.INHERIT, hasKey, models, requiresKey = true, problem = problem)

    @Test
    fun aRejectedKeyAndAMissingKeyShowOnTheirRows() {
        compose.setContent {
            AutobotTheme {
                CompositionLocalProvider(LocalInkMotion provides InkMotion(reduced = true)) {
                    ProvidersScreen(
                        ProvidersUiState(
                            providers = listOf(
                                row("deepseek", "DeepSeek", hasKey = true, problem = "Couldn't list models: The provider rejected the API key (HTTP 401)"),
                                row("openrouter", "OpenRouter", hasKey = false),
                                row("groq", "Groq", hasKey = true, models = "1 model · just now"),
                            ),
                        ),
                        actions,
                    )
                }
            }
        }
        compose.onNodeWithText("Couldn't list models: The provider rejected the API key (HTTP 401)").assertIsDisplayed()
        compose.onNodeWithText(" · no key").assertIsDisplayed()
        compose.onNodeWithText("1 model · just now").assertIsDisplayed()
    }
}
