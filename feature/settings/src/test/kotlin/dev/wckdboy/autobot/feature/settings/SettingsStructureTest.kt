package dev.wckdboy.autobot.feature.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.wckdboy.autobot.core.designsystem.motion.InkMotion
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.feature.settings.about.AboutContent
import dev.wckdboy.autobot.feature.settings.about.AboutInfo
import dev.wckdboy.autobot.feature.settings.about.AboutLinks
import dev.wckdboy.autobot.feature.settings.about.AboutScreen
import dev.wckdboy.autobot.feature.settings.about.NoteBlock
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings has one home per thing, in a fixed order, and ends at About (version + release notes). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsStructureTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sectionsAreInOrder() {
        assertEquals(listOf("Privacy & security", "Agent", "Appearance", "Data", "About"), SettingsSections)
    }

    @Test
    fun settingsShowsItsSectionsAndNoConnections() {
        var aboutOpened = false
        compose.setContent {
            Themed {
                SettingsScreen(
                    state = SettingsUiState(networkLabel = "Direct"),
                    onBack = null, onOpenPrivacyCenter = {}, onThemeMode = {}, onAmoled = {},
                    onDynamicColor = {}, onAppLock = {}, onLockTimeout = {}, onIncognitoByDefault = {},
                    onOpenAbout = { aboutOpened = true },
                    about = aboutRowTitle("1.6.4", 16),
                )
            }
        }
        // The tab's title, in the tab header style (upper-cased like Models and Remote).
        // (not "SYSTEM": that is now only the theme option)
        compose.onNodeWithText("Settings").assertIsDisplayed()
        for (section in SettingsSections) compose.onNodeWithText("${section.uppercase()}").performScrollTo().assertIsDisplayed()
        for (row in listOf("Privacy Center", "App lock", "Redact before cloud", "Phone first", "Panic wipe", "Memory", "Memory notes", "Vault", "Web search", "Fallback on limit", "Usage", "MCP servers", "Export & import", "Delete all chats")) {
            compose.onNodeWithText(row).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Direct").performScrollTo().assertIsDisplayed()
        // Providers, image backends and accounts live on the Remote tab only.
        for (gone in listOf("CONNECTIONS", "Model providers", "Image backends")) compose.onAllNodesWithText(gone).assertCountEquals(0)
        compose.onNodeWithText("Autobot 1.6.4 (16)").performScrollTo().performClick()
        assertEquals(true, aboutOpened)
    }

    @Test
    fun aboutShowsVersionNotesAndTheSigningKey() {
        compose.setContent {
            Themed {
                AboutScreen(
                    info = AboutInfo("1.6.4", 16, buildDate = "2026-10-16", debug = true),
                    content = AboutContent(
                        signerSha256 = "ab".repeat(32),
                        notes = listOf(NoteBlock.Heading("Look"), NoteBlock.Bullet("Living cards match iPhone.")),
                        licenses = listOf("llama.cpp-MIT.txt"),
                        loaded = true,
                    ),
                    onBack = {}, onOpenLink = {}, onOpenDiagnostics = {},
                )
            }
        }
        compose.onNodeWithText("Version 1.6.4 (16)").assertIsDisplayed()
        compose.onNodeWithText("Built 2026-10-16").assertIsDisplayed()
        compose.onNodeWithText(AboutLinks.PRIVACY).assertIsDisplayed()
        compose.onNodeWithText("WHAT'S NEW IN 1.6.4").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Living cards match iPhone.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("All releases").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("9a13 78ac", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Debug build", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Copy diagnostics").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun whatsNewShowsFourBlocksThenShowAll() {
        compose.setContent {
            Themed {
                AboutScreen(
                    info = AboutInfo("1.6.4", 16),
                    content = AboutContent(notes = (1..6).map { NoteBlock.Bullet("Note $it") }, loaded = true),
                    onBack = {}, onOpenLink = {}, onOpenDiagnostics = {},
                )
            }
        }
        compose.onNodeWithText("Note 4").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Note 5").assertCountEquals(0)
        compose.onNodeWithText("SHOW ALL").performScrollTo().performClick()
        compose.onNodeWithText("Note 6").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("SHOW ALL").assertCountEquals(0)
        // Links that leave the app say so.
        compose.onAllNodesWithText("github.com ↗").assertCountEquals(2)
    }

    @androidx.compose.runtime.Composable
    private fun Themed(content: @androidx.compose.runtime.Composable () -> Unit) {
        AutobotTheme {
            CompositionLocalProvider(LocalInkMotion provides InkMotion(reduced = true)) { content() }
        }
    }
}
