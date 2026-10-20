package dev.wckdboy.autobot.feature.home.node

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.wckdboy.autobot.core.designsystem.largetext.LargeTextAudit
import dev.wckdboy.autobot.core.designsystem.largetext.LargeTextAudit.Themed
import dev.wckdboy.autobot.shared.invincible.NodeDigest
import dev.wckdboy.autobot.shared.invincible.NodeDigests
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The "While you were away" card: its lines, its actions, its words for a screen reader, at large text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NodeDigestCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val sample = NodeDigestCardState("n1", "studio-mac", NodeDigests.sample(now = 1_000_000))

    @Test
    fun showsTheLinesAndWhatNeedsYouFirst() {
        val clicks = mutableListOf<String>()
        compose.setContent {
            Themed { NodeDigestCard(sample, { clicks += "needs" }, { clicks += "details" }, { clicks += "dismiss" }) }
        }
        compose.onNodeWithText(useUnmergedTree = true, text = "12 requests, all on your node · 18.2k tokens").assertIsDisplayed()
        compose.onNodeWithText(useUnmergedTree = true, text = "3 actions: 2 by the rules, 1 approved by you").assertIsDisplayed()
        compose.onNodeWithText(useUnmergedTree = true, text = "Morning brief ran").assertIsDisplayed()
        compose.onNodeWithText("2 questions waiting").performClick()
        compose.onNodeWithContentDescription("Details: the node's activity").performClick()
        compose.onNodeWithContentDescription("Dismiss: mark as seen").performClick()
        assertEquals(listOf("needs", "details", "dismiss"), clicks)
        // Read without glyphs.
        compose.onNodeWithContentDescription("All 12 receipts verified", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aReceiptThatFailedToVerifyIsShownFirst() {
        val failed = sample.copy(digest = NodeDigests.sample(1_000_000, failedReceipt = true))
        compose.setContent { Themed { NodeDigestCard(failed, {}, {}, {}) } }
        compose.onNodeWithText(useUnmergedTree = true, text = "1 receipt failed to verify ✗").assertIsDisplayed()
    }

    @Test
    fun withNothingWaitingThereIsNoPrimaryAction() {
        val quiet = sample.copy(digest = NodeDigest(0, requests = 1, onNode = 1))
        compose.setContent { Themed { NodeDigestCard(quiet, {}, {}, {}) } }
        compose.onNodeWithTag("node-digest").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasText("waiting", substring = true)).fetchSemanticsNodes().size)
    }

    private fun audit(width: Int, scale: Float, dark: Boolean = true) = LargeTextAudit.render(compose, "node-digest-${if (dark) "dark" else "light"}", width, scale) {
        Themed(dark) { NodeDigestCard(sample.copy(digest = NodeDigests.sample(1_000_000, failedReceipt = true)), {}, {}, {}) }
    }

    @Test fun defaultTextFitsOnInk() = assertEquals(emptyList<LargeTextAudit.Finding>(), audit(411, 1f))

    @Test fun defaultTextFitsOnPaper() = assertEquals(emptyList<LargeTextAudit.Finding>(), audit(411, 1f, dark = false))

    @Test fun largestTextFitsOnANarrowPhone() = assertEquals(emptyList<LargeTextAudit.Finding>(), audit(360, 2f))
}
