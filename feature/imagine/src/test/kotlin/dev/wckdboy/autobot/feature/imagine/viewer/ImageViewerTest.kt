package dev.wckdboy.autobot.feature.imagine.viewer

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.wckdboy.autobot.core.data.model.GalleryItem
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.diffusion.DiffusionMode
import dev.wckdboy.autobot.core.diffusion.DiffusionRequest
import dev.wckdboy.autobot.shared.panic.IncognitoImages
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The full-screen viewer: the image's real size, paging, the actions and the info sheet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageViewerTest {
    @get:Rule
    val compose = createComposeRule()

    private fun item(id: String, prompt: String = "a red fox in snow") = GalleryItem(
        id = id, createdAt = 0, mode = "TXT2IMG", prompt = prompt, negativePrompt = "blurry", width = 1368, height = 768,
        seed = 4242, steps = 30, cfgScale = 6f, sampler = "DPM++ 2M", engine = "This phone", model = "sdxl", durationMs = 2500, paramsJson = "{}",
    )

    private val image = ViewerImage(ImageBitmap(160, 90), pixelWidth = 1368, pixelHeight = 768)

    @Test
    fun showsTheRealSizeTheBatchPositionAndHandsOffToImg2img() {
        val acted = mutableListOf<Pair<ViewerAction, String>>()
        compose.setContent {
            AutobotTheme {
                ImageViewerContent(
                    items = listOf(item("a"), item("b", "a lighthouse")),
                    initialId = "b",
                    loadImage = { image },
                    onAction = { action, it -> acted += action to it.id },
                    onDismiss = {},
                    actionsFor = { ViewerAction.forImage(it.id, canUpscale = true) },
                )
            }
        }
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
        compose.onNodeWithText("1368×768").assertIsDisplayed()
        compose.onNodeWithText("a lighthouse").assertIsDisplayed()
        compose.onNodeWithContentDescription("a lighthouse").assertIsDisplayed()
        compose.onNodeWithText("→ IMG2IMG", ignoreCase = true).performScrollTo().performClick()
        compose.onNodeWithText("UPSCALE 4×", ignoreCase = true).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Add to favorites").performClick()
        assertEquals(listOf(ViewerAction.IMG2IMG to "b", ViewerAction.UPSCALE to "b", ViewerAction.FAVORITE to "b"), acted)
    }

    @Test
    fun deleteAsksFirst() {
        val acted = mutableListOf<ViewerAction>()
        compose.setContent {
            AutobotTheme {
                ImageViewerContent(listOf(item("a")), "a", { image }, { action, _ -> acted += action }, {}, actionsFor = { ViewerAction.forImage(it.id, false) })
            }
        }
        compose.onNodeWithText("DELETE", ignoreCase = true).performScrollTo().performClick()
        assertEquals(emptyList<ViewerAction>(), acted)
        compose.onNodeWithText("Delete image?").assertIsDisplayed()
    }

    @Test
    fun anUnsavedIncognitoImageCanOnlyBeKeptOrHandedOn() {
        val id = IncognitoImages.ID_PREFIX + "1"
        compose.setContent {
            AutobotTheme {
                ImageViewerContent(listOf(item(id)), id, { image }, { _, _ -> }, {}, actionsFor = { ViewerAction.forImage(it.id, true) })
            }
        }
        compose.onNodeWithText("SAVE TO GALLERY", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("→ IMG2IMG", ignoreCase = true).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTextCount("SAVE TO DEVICE"))
        assertEquals(0, compose.onAllNodesWithTextCount("DELETE"))
        assertEquals(0, compose.onAllNodesWithTextCount("UPSCALE 4×"))
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Share image")).fetchSemanticsNodes().size)
    }

    @Test
    fun theInfoSheetListsHowItWasMade() {
        compose.setContent {
            AutobotTheme {
                InfoSheet(item("a").copy(mode = "IMG2IMG"), 2736 to 1536, DiffusionRequest(mode = DiffusionMode.IMG2IMG, denoiseStrength = 0.45f), onCopy = {})
            }
        }
        compose.onNodeWithText("2736×1536").assertIsDisplayed()
        compose.onNodeWithText("4242").assertIsDisplayed()
        compose.onNodeWithText("0.45").assertIsDisplayed()
        compose.onNodeWithText("sdxl").assertIsDisplayed()
        compose.onNodeWithText("a red fox in snow").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text, ignoreCase = true)).fetchSemanticsNodes().size
}
