package dev.wckdboy.autobot.navigation

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Back on a wide window, where a list and what it opens sit side by side: it closes the detail
 * (the list stays, with its "Nothing open" placeholder) instead of leaving the app, and opening
 * another item from the list replaces the detail rather than stacking it.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h800dp-xhdpi")
class ListDetailBackTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(
        object : ExternalResource() {
            override fun before() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
            }
        },
    ).around(compose)

    private lateinit var stack: NavBackStack<NavKey>

    private fun show(vararg start: NavKey) {
        compose.setContent {
            stack = rememberNavBackStack(*start)
            val nothingOpen = remember { ListDetailSceneStrategy.listPane(detailPlaceholder = { Text("Nothing open") }) }
            val detail = remember { ListDetailSceneStrategy.detailPane() }
            NavDisplay(
                backStack = stack,
                onBack = { if (stack.size > 1) stack.removeAt(stack.lastIndex) },
                sceneStrategies = listOf(rememberAutobotListDetail()),
                entryProvider = entryProvider {
                    entry<Settings>(metadata = nothingOpen) { Box(Modifier.fillMaxSize()) { Text("settings list") } }
                    entry<PrivacyCenter>(metadata = detail) { Text("privacy page") }
                    entry<Memory>(metadata = detail) { Text("memory page") }
                },
            )
        }
        compose.waitForIdle()
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun backClosesTheDetailBesideTheList() {
        show(Settings, PrivacyCenter)
        compose.onNodeWithText("settings list").assertExists()
        compose.onNodeWithText("privacy page").assertExists()

        pressBack()

        assertEquals(listOf<NavKey>(Settings), stack.toList())
        assertFalse("back must not leave the app", compose.activity.isFinishing)
        compose.onNodeWithText("Nothing open").assertExists()
    }

    @Test
    fun backPopsOneDetailAtATime() {
        show(Settings, PrivacyCenter, Memory)
        pressBack()
        assertEquals(listOf(Settings, PrivacyCenter), stack.toList())
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    fun openingFromTheListReplacesTheDetailOnTwoPanes() {
        val keys = mutableListOf<NavKey>(Run, Chat("a"))
        keys.openDetail(Run, Chat("b"), twoPane = true)
        assertEquals(listOf(Run, Chat("b")), keys)

        val nested = mutableListOf(Run, ChatList, Chat("a"))
        nested.openDetail(ChatList, Chat("b"), twoPane = true)
        assertEquals(listOf(Run, ChatList, Chat("b")), nested)
    }

    @Test
    fun openingOnOnePanePushes() {
        val keys = mutableListOf<NavKey>(Run, Chat("a"))
        keys.openDetail(Run, Chat("b"), twoPane = false)
        assertEquals(listOf(Run, Chat("a"), Chat("b")), keys)
        // The same item again is a no-op.
        keys.openDetail(Run, Chat("b"), twoPane = true)
        assertEquals(listOf(Run, Chat("a"), Chat("b")), keys)
    }
}
