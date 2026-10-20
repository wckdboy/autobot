package dev.wckdboy.autobot.feature.home

import androidx.compose.ui.test.junit4.createComposeRule
import dev.wckdboy.autobot.core.data.model.Provider
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.designsystem.component.PrivacyStatus
import dev.wckdboy.autobot.core.designsystem.component.Route
import dev.wckdboy.autobot.core.diffusion.BackendKind
import dev.wckdboy.autobot.core.diffusion.DiffusionBackend
import dev.wckdboy.autobot.core.models.AccountState
import dev.wckdboy.autobot.core.designsystem.largetext.LargeTextAudit
import dev.wckdboy.autobot.core.designsystem.largetext.LargeTextAudit.Themed
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Run hub, Remote tab and welcome at the default and the largest system font, on a narrow and a common phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LargeTextTest {
    @get:Rule
    val compose = createComposeRule()

    private val state = RunUiState(
        status = "ready",
        live = 2,
        chat = RunTarget("local", "qwen3-4b", "Qwen3 4B", Route.LOCAL_NPU_CPU),
        agent = RunTarget("anth", "claude-opus-5-5", "Claude Opus 5.5", Route.CLOUD_API),
        code = RunTarget("pc", "qwen3-coder", "Qwen3 Coder 30B", Route.PC_LAN),
        imageLabel = "SD 1.5 · npu",
        imageRoute = Route.LOCAL_NPU,
        recent = listOf(
            RecentRun("r1", "Plan a weekend in Lisbon", "chat · 12 msg", Route.LOCAL_GPU, 1_792_000_000_000L, conversationId = "c1"),
            RecentRun("r2", "Fix the flaky upload test", "agent · 4 steps", Route.CLOUD_API, 1_791_990_000_000L, conversationId = "c2"),
        ),
    )

    private fun audit(width: Int, scale: Float) = LargeTextAudit.render(compose, "run", width, scale) {
        Themed { RunScreen(state, LargeTextAudit.uncalled(), {}, {}, {}, {}, {}, {}) }
    }

    @Test fun defaultTextFits() = assertEquals(emptyList<LargeTextAudit.Finding>(), audit(411, 1f))

    @Test fun largestTextFitsOnANarrowPhone() = assertEquals(emptyList<LargeTextAudit.Finding>(), audit(360, 2f))

    @Test fun largestTextFits() = assertEquals(emptyList<LargeTextAudit.Finding>(), audit(411, 2f))

    /** Run with the paired node's "While you were away" card under the status line. */
    private fun withDigest(width: Int, scale: Float, dark: Boolean) = LargeTextAudit.render(compose, "run-digest-${if (dark) "dark" else "light"}", width, scale) {
        val digest = dev.wckdboy.autobot.feature.home.node.NodeDigestCardState("n1", "studio-mac", dev.wckdboy.autobot.shared.invincible.NodeDigests.sample(1_000_000))
        Themed(dark) { RunScreen(state, LargeTextAudit.uncalled(), {}, {}, {}, {}, {}, {}, digest = digest) }
    }

    @Test fun withTheNodeDigestOnInk() = assertEquals(emptyList<LargeTextAudit.Finding>(), withDigest(411, 1f, dark = true))

    @Test fun withTheNodeDigestOnPaper() = assertEquals(emptyList<LargeTextAudit.Finding>(), withDigest(411, 1f, dark = false))

    @Test fun withTheNodeDigestAtTheLargestText() = assertEquals(emptyList<LargeTextAudit.Finding>(), withDigest(360, 2f, dark = true))

    private val remote = RemoteUiState(
        providers = listOf(
            Provider("p1", ProviderKind.ANTHROPIC, "Anthropic", "https://api.anthropic.com/v1", "claude-opus-5-5"),
            Provider("p2", ProviderKind.OLLAMA, "Ollama on the desk PC", "http://192.168.1.20:11434", "qwen3-coder:30b"),
        ),
        backends = listOf(DiffusionBackend("b1", BackendKind.SD_API, "Forge on the desk PC", "http://192.168.1.20:7860")),
        accounts = AccountState(huggingFaceUser = "wckdboy"),
        network = PrivacyStatus.TOR,
    )

    private fun auditRemote(width: Int, scale: Float) = LargeTextAudit.render(compose, "remote", width, scale) {
        Themed { RemoteScreen(remote, {}, {}, {}, {}) }
    }

    // The welcome does not scroll by itself: rendered at a real phone's height.
    private fun auditWelcome(width: Int, scale: Float) = LargeTextAudit.render(compose, "welcome", width, scale, heightDp = 780) {
        Themed { WelcomeScreen({}) }
    }

    @Test fun remoteDefaultTextFits() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditRemote(411, 1f))

    @Test fun remoteLargestTextFitsOnANarrowPhone() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditRemote(360, 2f))

    @Test fun welcomeDefaultTextFits() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditWelcome(411, 1f))

    @Test fun welcomeLargestTextFitsOnANarrowPhone() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditWelcome(360, 2f))
}
