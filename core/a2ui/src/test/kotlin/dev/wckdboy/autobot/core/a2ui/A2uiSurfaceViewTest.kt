package dev.wckdboy.autobot.core.a2ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.shared.a2ui.A2uiActionResult
import dev.wckdboy.autobot.shared.a2ui.A2uiError
import dev.wckdboy.autobot.shared.a2ui.A2uiHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Compose renderer against real surfaces: the spec's contact form and one of every component. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2400dp-xhdpi")
class A2uiSurfaceViewTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions = ArrayList<A2uiActionResult>()

    private fun show(stream: String, surfaceId: String): A2uiHost {
        val host = A2uiHost()
        assertEquals(emptyList<A2uiError>(), host.receive(stream))
        compose.setContent {
            AutobotTheme {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    A2uiSurfaceView(host, surfaceId, onAction = { actions += it })
                }
            }
        }
        return host
    }

    @Test
    fun contactFormRendersWritesBackAndSubmits() {
        val host = show(A2uiFixtures.contactForm, "contact_form_1")
        compose.onNodeWithText("Contact Us").assertExists()
        compose.onNodeWithText("Send Message").assertExists()

        // Typing writes through the binding; a failing check shows under the field and disables the button.
        compose.onNodeWithTag("email_field").performTextReplacement("not-an-email")
        assertEquals("\"not-an-email\"", host.surface("contact_form_1")!!.readJson("/contact/email"))
        compose.onNodeWithText("Please enter a valid email address.").assertExists()
        compose.onNodeWithTag("email_field").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Error))

        // The checkbox and the picker write back too.
        compose.onNodeWithText("Subscribe to our newsletter").performScrollTo().performClick()
        assertEquals("false", host.surface("contact_form_1")!!.readJson("/contact/subscribe"))

        compose.onNodeWithTag("email_field").performTextReplacement("ada@example.com")
        compose.onNodeWithText("Please enter a valid email address.").assertDoesNotExist()
        compose.onNodeWithText("Send Message").performScrollTo().performClick()
        val sent = actions.single() as A2uiActionResult.SendEvent
        assertEquals("submitContactForm", sent.name)
        val context = Json.parseToJsonElement(sent.messageJson).jsonObject["action"]!!.jsonObject["context"]!!.jsonObject
        assertEquals("false", context["isNewsletterSubscribed"]!!.jsonPrimitive.content)
    }

    @Test
    fun aButtonWhoseChecksFailIsDisabledWithItsMessage() {
        val host = show(A2uiFixtures.gallery, "all")
        compose.onNodeWithTag("order").performScrollTo()
        compose.onNodeWithText("Add your name first.").assertExists()
        compose.onNodeWithTag("order").onChildren().filterToOne(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).assertIsNotEnabled()

        compose.onNodeWithTag("name").performScrollTo().performTextReplacement("Ada")
        assertEquals("\"Ada\"", host.surface("all")!!.readJson("/form/name"))
        compose.onNodeWithText("Add your name first.").assertDoesNotExist()
        val button = compose.onNodeWithTag("order").onChildren().filterToOne(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
        button.assertIsEnabled().performClick()
        val sent = actions.single() as A2uiActionResult.SendEvent
        assertEquals("order", sent.name)
        assertEquals("Order it", sent.userMessage)
    }

    @Test
    fun everyComponentRendersAndInputsWriteBack() {
        val host = show(A2uiFixtures.gallery, "all")
        // Display and layout.
        listOf("title", "caption", "row", "icon", "vdiv", "price", "img", "video", "audio", "divider", "list", "card", "tabs", "name", "agree", "size", "toppings", "qty", "when", "order")
            .forEach { compose.onNodeWithTag(it).assertExists() }
        compose.onNodeWithText("Order a pizza").assertExists()
        compose.onNodeWithText("Total: 18.50").assertExists()
        compose.onNodeWithText("Diavola").assertExists()
        compose.onNodeWithText("Funghi").assertExists()
        compose.onNodeWithText("Free delivery over 30").assertExists()
        // The image waits for a tap (no loader here: it offers its link instead).
        compose.onNodeWithContentDescription("A margherita").assertExists()
        assertEquals(2, compose.onAllNodesWithText("example.com", useUnmergedTree = true).fetchSemanticsNodes().size)

        // Tabs show one child at a time.
        compose.onNodeWithText("Margherita, Diavola").assertExists()
        compose.onNodeWithText("Open until 23:00").assertDoesNotExist()
        compose.onNodeWithText("Info").performScrollTo().performClick()
        compose.onNodeWithText("Open until 23:00").assertExists()

        // The modal's trigger opens its content instead of acting.
        compose.onNodeWithText("Contains gluten and milk.").assertDoesNotExist()
        compose.onNodeWithText("Allergens").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Contains gluten and milk.").assertExists()
        assertTrue(actions.isEmpty())
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()

        // Inputs write back.
        compose.onNodeWithText("Extra cheese").performScrollTo().performClick()
        assertEquals("true", host.surface("all")!!.readJson("/form/cheese"))
        compose.onNodeWithText("Large").performScrollTo().performClick()
        assertEquals("[\"l\"]", host.surface("all")!!.readJson("/form/size"))
        compose.onNodeWithText("Large").assertIsSelected()
        compose.onNodeWithText("Basil").performScrollTo().performClick()
        compose.onNodeWithText("Olives").performClick()
        assertEquals("[\"basil\",\"olives\"]", host.surface("all")!!.readJson("/form/toppings"))
        compose.onNodeWithContentDescription("Quantity").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        assertEquals("4", host.surface("all")!!.readJson("/form/qty")?.removeSuffix(".0"))
        compose.onNodeWithText("4").assertExists()
        // The date and time read in the device's words.
        compose.onNodeWithText(A2uiDateTime.display(A2uiDateTime.parse("2026-10-21T19:30:00"))).assertExists()
    }

    @Test
    fun anUnknownOrMissingChildDrawsNothing() {
        val host = A2uiHost()
        // `later` is referenced but not sent yet: a placeholder, which draws nothing.
        host.receive(
            """{"version":"v1.0","createSurface":{"surfaceId":"p","catalogId":"${A2uiFixtures.BASIC}","components":[{"id":"root","component":"Column","children":["t","later"]},{"id":"t","component":"Text","text":"Hello"}]}}""",
        )
        compose.setContent { AutobotTheme { A2uiSurfaceView(host, "p", onAction = { actions += it }) } }
        compose.onNodeWithText("Hello").assertExists()
        compose.onNodeWithTag("later").assertDoesNotExist()
        // It arrives: the view follows the host.
        host.receive("""{"version":"v1.0","updateComponents":{"surfaceId":"p","components":[{"id":"later","component":"CheckBox","label":"Arrived","value":true}]}}""")
        compose.onNodeWithText("Arrived").assertExists()
        compose.onNodeWithTag("later").onChildren().filterToOne(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).assertIsOn()
        // Deleted: the view empties.
        host.receive("""{"version":"v1.0","deleteSurface":{"surfaceId":"p"}}""")
        compose.onNodeWithText("Hello").assertDoesNotExist()
    }

    @Test
    fun openUrlAsksBeforeLeaving() {
        val host = A2uiHost()
        host.receive(
            """{"version":"v1.0","createSurface":{"surfaceId":"l","catalogId":"${A2uiFixtures.BASIC}","components":[{"id":"root","component":"Button","child":"t","action":{"functionCall":{"@call":"openUrl","args":{"url":"https://example.org/menu"}}}},{"id":"t","component":"Text","text":"Menu"}]}}""",
        )
        compose.setContent { AutobotTheme { A2uiSurfaceView(host, "l", onAction = { actions += it }) } }
        compose.onNodeWithText("Menu").performClick()
        compose.onNodeWithText("Open link?").assertExists()
        compose.onNodeWithText("https://example.org/menu").assertExists()
        assertTrue(actions.isEmpty())
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Open link?").assertDoesNotExist()
    }

    @Test
    fun aCheckboxOffStaysOff() {
        val host = A2uiHost()
        host.receive("""{"version":"v1.0","createSurface":{"surfaceId":"c","catalogId":"${A2uiFixtures.BASIC}","components":[{"id":"root","component":"CheckBox","label":"Off","value":{"@path":"/v"}}],"dataModel":{"v":false}}}""")
        compose.setContent { AutobotTheme { A2uiSurfaceView(host, "c", onAction = { actions += it }) } }
        compose.onNodeWithTag("root").onChildren().filterToOne(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).assertIsOff()
    }
}
