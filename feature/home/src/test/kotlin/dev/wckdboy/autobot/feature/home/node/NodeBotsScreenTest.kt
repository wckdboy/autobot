package dev.wckdboy.autobot.feature.home.node

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import dev.wckdboy.autobot.core.designsystem.component.PrivacyStatus
import dev.wckdboy.autobot.core.designsystem.largetext.LargeTextAudit
import dev.wckdboy.autobot.core.designsystem.largetext.LargeTextAudit.Themed
import dev.wckdboy.autobot.feature.home.RemoteNode
import dev.wckdboy.autobot.feature.home.RemoteScreen
import dev.wckdboy.autobot.feature.home.RemoteUiState
import dev.wckdboy.autobot.shared.invincible.NodeBots
import dev.wckdboy.autobot.shared.invincible.NodeBotsState
import dev.wckdboy.autobot.shared.invincible.NodeDigests
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Bots list in Remote and a bot's detail: rows, the off switch's confirmation, a new chat, the older-node fallback, large text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NodeBotsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000L
    private val bots = NodeBotsState(bots = NodeBots.ordered(NodeBots.sample(now)), supported = true)
    private val node = RemoteNode("n1", "studio-mac")
    private val remote = RemoteUiState(network = PrivacyStatus.DIRECT, nodes = listOf(node), bots = mapOf("n1" to bots), now = now)

    private fun detail(name: String = "ops") = NodeBotUiState(
        node = "studio-mac",
        name = name,
        bots = bots,
        sessions = NodeBots.sampleSessions(now).filter { NodeBots.of(it.bot) == name },
        approvals = NodeBots.sampleApprovals(now).filter { NodeBots.of(it.bot) == name },
        detail = dev.wckdboy.autobot.shared.invincible.NodeBotDetails.sample(name),
        now = now,
    )

    @Test
    fun remoteListsTheBotsWithTheirStateAndADownChannelInWords() {
        val opened = mutableListOf<String>()
        compose.setContent { Themed { RemoteScreen(remote, {}, {}, {}, {}, onOpenBot = { id, bot -> opened += "$id/$bot" }) } }
        compose.onNodeWithText("Agent on studio-mac").assertIsDisplayed()
        compose.onNodeWithText("telegram · slack", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("slack down: token revoked ✗", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("2 queued · active 22m ago", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("bot-support").performClick()
        assertEquals(listOf("n1/support"), opened)
    }

    @Test
    fun anOlderNodeShowsOnlyTheAgentRow() {
        compose.setContent { Themed { RemoteScreen(remote.copy(bots = mapOf("n1" to NodeBotsState(supported = false))), {}, {}, {}, {}) } }
        compose.onNodeWithText("Agent on studio-mac").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("Bots")).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText("ops")).fetchSemanticsNodes().size)
    }

    @Test
    fun turningABotOffAsksFirst() {
        val switched = mutableListOf<Boolean>()
        compose.setContent { Themed { NodeBotScreen(detail(), {}, {}, { switched += it }, {}, {}) } }
        compose.onNodeWithText("On").performClick()
        compose.onNodeWithText("Turn off ops?").assertIsDisplayed()
        compose.onNodeWithText("It stops answering on telegram and slack until you turn it on again.").assertIsDisplayed()
        assertEquals(emptyList<Boolean>(), switched)
        compose.onNodeWithText("Turn off").performClick()
        assertEquals(listOf(false), switched)
    }

    @Test
    fun theDetailShowsChannelsApprovalsAndSessionsAndStartsANewChat() {
        val opened = mutableListOf<String?>()
        compose.setContent { Themed { NodeBotScreen(detail(), {}, {}, {}, { opened += it }, { opened += null }) } }
        compose.onNodeWithText("Keeps the servers up and says so plainly.").assertIsDisplayed()
        compose.onNodeWithText("down: token revoked ✗").assertIsDisplayed()
        compose.onNodeWithTag("node-bot").performScrollToNode(hasText("Run a command?"))
        compose.onNodeWithText("Run a command?").assertIsDisplayed()
        compose.onNodeWithTag("node-bot").performScrollToNode(hasText("tg-ops-4411"))
        compose.onNodeWithText("tg-ops-4411").performClick()
        compose.onNodeWithTag("node-bot").performScrollToNode(hasText("New chat with ops"))
        compose.onNodeWithText("New chat with ops").performClick()
        assertEquals(listOf("tg-ops-4411", null), opened)
    }

    @Test
    fun theDetailShowsPersonalityProfileAndUsageAndSetsAPersonality() {
        val picked = mutableListOf<String?>()
        compose.setContent { Themed { NodeBotScreen(detail(), {}, {}, {}, {}, {}, onPersonality = { picked += it }) } }
        compose.onNodeWithTag("node-bot").performScrollToNode(hasText("Short answers, no preamble."))
        compose.onNodeWithText("Short answers, no preamble.").assertIsDisplayed()
        compose.onNodeWithText("reviewer").performClick()
        compose.onNodeWithText("none").performClick()
        // The one in use is not set again.
        compose.onNodeWithText("concise").performClick()
        assertEquals(listOf("reviewer", null), picked)
        compose.onNodeWithTag("node-bot").performScrollToNode(hasText("Wants restarts confirmed in one line."))
        compose.onNodeWithText("Wants restarts confirmed in one line.").assertIsDisplayed()
        compose.onNodeWithText("Edit USER.md on studio-mac").assertIsDisplayed()
        compose.onNodeWithTag("node-bot").performScrollToNode(hasText("46 turns · 131 model calls · 440.4k tokens · 31m 52s"))
        compose.onNodeWithTag("node-bot").performScrollToNode(hasText("Model calls · all local"))
        compose.onNodeWithText("Model calls · all local").assertIsDisplayed()
        compose.onNodeWithTag("node-bot").performScrollToNode(hasText("terminal"))
        compose.onNodeWithText("58").assertIsDisplayed()
    }

    @Test
    fun anOlderNodeLeavesTheNewSectionsOut() {
        compose.setContent { Themed { NodeBotScreen(detail().copy(detail = dev.wckdboy.autobot.shared.invincible.NodeBotDetail()), {}, {}, {}, {}, {}) } }
        assertEquals(0, compose.onAllNodes(hasText("Personality")).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText("What it knows about you")).fetchSemanticsNodes().size)
    }

    @Test
    fun theNodesAnswerToASwitchShows() {
        val state = detail().let { it.copy(bots = it.bots.copy(notice = "ops is running.")) }
        compose.setContent { Themed { NodeBotScreen(state, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("ops is running.").assertIsDisplayed()
    }

    @Test
    fun remoteReloadsTheBots() {
        val reloaded = mutableListOf<String>()
        val state = remote.copy(bots = mapOf("n1" to bots.copy(notice = "Restarted ops")))
        compose.setContent { Themed { RemoteScreen(state, {}, {}, {}, {}, onReloadBots = { reloaded += it }) } }
        compose.onNodeWithText("Reload").performClick()
        assertEquals(listOf("n1"), reloaded)
        compose.onNodeWithText("Restarted ops").assertIsDisplayed()
    }

    @Test
    fun aBotThatIsGoneSaysSo() {
        compose.setContent { Themed { NodeBotScreen(detail("nobody"), {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("There is no bot called nobody on studio-mac.").assertIsDisplayed()
    }

    private fun auditRemote(width: Int, scale: Float, dark: Boolean) = LargeTextAudit.render(compose, "remote-bots-${if (dark) "dark" else "light"}", width, scale) {
        Themed(dark) { RemoteScreen(remote, {}, {}, {}, {}) }
    }

    private fun auditDetail(width: Int, scale: Float, dark: Boolean) = LargeTextAudit.render(compose, "bot-detail-${if (dark) "dark" else "light"}", width, scale) {
        Themed(dark) { NodeBotScreen(detail(), {}, {}, {}, {}, {}) }
    }

    private fun auditDigest(width: Int, scale: Float, dark: Boolean) = LargeTextAudit.render(compose, "digest-bots-${if (dark) "dark" else "light"}", width, scale, heightDp = 700) {
        Themed(dark) {
            androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                NodeDigestCard(NodeDigestCardState("n1", "studio-mac", NodeDigests.sampleWithBots(now)), {}, {}, {}, androidx.compose.ui.Modifier.padding(16.dp))
            }
        }
    }

    @Test fun remoteOnInk() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditRemote(411, 1f, dark = true))

    @Test fun remoteOnPaper() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditRemote(411, 1f, dark = false))

    @Test fun remoteAtTheLargestText() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditRemote(360, 2f, dark = true))

    @Test fun detailOnInk() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditDetail(411, 1f, dark = true))

    @Test fun detailOnPaper() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditDetail(411, 1f, dark = false))

    @Test fun detailAtTheLargestText() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditDetail(360, 2f, dark = false))

    @Test fun digestPerBotOnInk() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditDigest(411, 1f, dark = true))

    @Test fun digestPerBotOnPaper() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditDigest(411, 1f, dark = false))
}
