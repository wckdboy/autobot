package dev.wckdboy.autobot.feature.settings.usage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import dev.wckdboy.autobot.core.designsystem.motion.InkMotion
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.providers.remote.ProviderPresets
import dev.wckdboy.autobot.shared.usage.LimitUnit
import dev.wckdboy.autobot.shared.usage.SubscriptionWindow
import dev.wckdboy.autobot.shared.usage.UsageBoard
import dev.wckdboy.autobot.shared.usage.UsageLedger
import dev.wckdboy.autobot.shared.usage.UsagePlan
import dev.wckdboy.autobot.shared.usage.UsageRecord
import dev.wckdboy.autobot.shared.usage.WindowPeriod
import dev.wckdboy.autobot.shared.usage.ZoneRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The board shows totals, the on-device contrast, windows with their reset and the price table. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsageScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private class FakeActions : UsageActions {
        val calls = mutableListOf<String>()
        override fun savePrice(provider: String, model: String, input: String, output: String, cacheRead: String, cacheWrite: String) {
            calls += "price $provider $model $input $output"
        }
        override fun saveWindow(window: SubscriptionWindow) {
            calls += "window ${window.id}"
        }
        override fun deleteWindow(id: String) {
            calls += "delete $id"
        }
        override fun addOpenCodeGoWindows(provider: String) {
            calls += "go $provider"
        }
        override fun clearHistory() {
            calls += "clear"
        }
        override fun dismissMessage() = Unit
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        AutobotTheme { CompositionLocalProvider(LocalInkMotion provides InkMotion(reduced = true)) { content() } }
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
    }

    private val now = 1_792_000_000_000L
    private val start = UsageLedger.bucketStart(now - 60_000)

    private fun state(plan: UsagePlan): UsageUiState {
        val records = listOf(
            UsageRecord(start, UsageLedger.FINE_SPAN, "anth", "claude-opus-5-5", "Anthropic", ProviderPresets.ANTHROPIC, input = 1_000_000, output = 100_000, requests = 3),
            UsageRecord(start, UsageLedger.FINE_SPAN, "local", "qwen3", "This phone", null, onDevice = true, input = 20_000, output = 2_000, requests = 2),
            UsageRecord(start, UsageLedger.FINE_SPAN, "go", "glm-5", "OpenCode Go", ProviderPresets.OPENCODE_GO, input = 40_000, output = 4_000, requests = 1),
        )
        return UsageUiState(
            report = UsageBoard.report(records, plan, now, ZoneRules.UTC),
            providers = listOf(UsageProviderOption("anth", "Anthropic", false), UsageProviderOption("go", "OpenCode Go", true)),
        )
    }

    @Test
    fun showsTotalsBreakdownAndPrices() {
        val actions = FakeActions()
        compose.setContent { Themed { UsageScreen(state(UsagePlan()), actions, onBack = {}) } }
        compose.onNodeWithText("1.2M").assertIsDisplayed()
        compose.onNodeWithText("$6.00 + unknown").assertIsDisplayed()
        compose.onNodeWithText("ON THIS PHONE · 22K TOKENS · $0").assertIsDisplayed()
        compose.onNodeWithText("44K TOKENS WITHOUT A PRICE").assertIsDisplayed()
        scrollTo("// PRICES · USD PER 1M TOKENS")
        compose.onNodeWithText("// PRICES · USD PER 1M TOKENS").assertIsDisplayed()
        scrollTo("+ OPENCODE GO 5 H / WEEK / MONTH")
        compose.onNodeWithText("+ OPENCODE GO 5 H / WEEK / MONTH").performClick()
        assertEquals(listOf("go go"), actions.calls)
    }

    @Test
    fun windowsShowUsageOfTheLimitAndTheReset() {
        val plan = UsagePlan(windows = listOf(SubscriptionWindow("w", "go", WindowPeriod.FIVE_HOURS, rolling = true, unit = LimitUnit.REQUESTS, limit = 10.0)))
        compose.setContent { Themed { UsageScreen(state(plan), FakeActions(), onBack = {}) } }
        val line = "1 requests of 10 requests · oldest use rolls off in ~5 h"
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(line, substring = true))
        compose.onNodeWithText(line, substring = true).assertIsDisplayed()
    }

    @Test
    fun aMistypedLimitCannotBeSavedAsNoLimit() {
        val actions = FakeActions()
        compose.setContent { Themed { UsageScreen(state(UsagePlan()), actions, onBack = {}) } }
        scrollTo("+ WINDOW")
        compose.onNodeWithText("+ WINDOW").performClick()
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput("12x")
        compose.onNodeWithText("SAVE").assertIsNotEnabled()
        field.performTextReplacement("1,000,000")
        compose.onNodeWithText("SAVE").assertIsEnabled().performClick()
        assertEquals(1, actions.calls.count { it.startsWith("window ") })
    }

    @Test
    fun limitsReadGroupedCountsAndDollars() {
        assertEquals(1_000_000.0, parseLimit("1,000,000", LimitUnit.TOKENS))
        assertEquals(1_000_000.0, parseLimit("1.000.000", LimitUnit.TOKENS))
        assertEquals(2_500.0, parseLimit(" 2 500 ", LimitUnit.REQUESTS))
        assertEquals(300.0, parseLimit("300", LimitUnit.REQUESTS))
        assertNull(parseLimit("1.5", LimitUnit.TOKENS))
        assertNull(parseLimit("1,00", LimitUnit.TOKENS))
        assertNull(parseLimit("12x", LimitUnit.TOKENS))
        assertEquals(12.5, parseLimit("12,50", LimitUnit.USD)!!, 1e-9)
    }
}
