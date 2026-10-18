package dev.wckdboy.autobot.feature.models

import androidx.compose.ui.test.junit4.createComposeRule
import dev.wckdboy.autobot.core.designsystem.largetext.LargeTextAudit
import dev.wckdboy.autobot.core.designsystem.largetext.LargeTextAudit.Themed
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The models screen's tabs at the default and the largest system font, on a narrow and a common phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LargeTextTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions: ModelsActions = Proxy.newProxyInstance(ModelsActions::class.java.classLoader, arrayOf(ModelsActions::class.java)) { _, m, _ ->
        if (m.returnType == List::class.java) emptyList<Any>() else null
    } as ModelsActions

    private fun audit(name: String, state: ModelsUiState, width: Int, scale: Float) = LargeTextAudit.render(compose, name, width, scale) {
        Themed { ModelsScreen(state, actions, onOpenAccounts = {}, onOpenCompute = {}) }
    }

    @Test fun defaultTextFits() = assertEquals(emptyList<LargeTextAudit.Finding>(), audit("models", ModelsUiState(), 411, 1f))

    @Test fun largestTextFitsOnANarrowPhone() = assertEquals(emptyList<LargeTextAudit.Finding>(), audit("models", ModelsUiState(), 360, 2f))

    @Test fun hubLargestTextFitsOnANarrowPhone() =
        assertEquals(emptyList<LargeTextAudit.Finding>(), audit("models-hf", ModelsUiState(tab = ModelsTab.HUGGING_FACE, offline = true), 360, 2f))
}
