package dev.wckdboy.autobot.feature.imagine

import androidx.compose.ui.test.junit4.createComposeRule
import dev.wckdboy.autobot.core.designsystem.largetext.LargeTextAudit
import dev.wckdboy.autobot.core.designsystem.largetext.LargeTextAudit.Themed
import dev.wckdboy.autobot.core.diffusion.BackendKind
import dev.wckdboy.autobot.core.diffusion.DiffusionBackend
import dev.wckdboy.autobot.feature.imagine.gallery.GalleryScreen
import dev.wckdboy.autobot.feature.imagine.gallery.GalleryUiState
import dev.wckdboy.autobot.feature.imagine.generate.ImagineScreen
import dev.wckdboy.autobot.feature.imagine.generate.ImagineUiState
import dev.wckdboy.autobot.feature.imagine.generate.ModelChip
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Imagine and the gallery at the default and the largest system font, on a narrow and a common phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LargeTextTest {
    @get:Rule
    val compose = createComposeRule()

    private val backend = DiffusionBackend("b1", BackendKind.SD_API, "Forge on the desk PC", "http://192.168.1.20:7860")

    private val imagine = ImagineUiState(
        backends = listOf(backend),
        backend = backend,
        loaded = true,
        chips = listOf(
            ModelChip("b1", "sdxl-turbo", "SDXL Turbo", onDevice = false),
            ModelChip("local", "sd15", "SD 1.5", onDevice = true, accelerator = "npu"),
        ),
    )

    private fun auditImagine(width: Int, scale: Float) = LargeTextAudit.render(compose, "imagine", width, scale) {
        Themed { ImagineScreen(imagine, LargeTextAudit.uncalled(), {}, {}, {}, {}) }
    }

    // Thumbnails load through the view model, so the gallery is audited empty (title bar, filters, empty state).
    private fun auditGallery(width: Int, scale: Float) = LargeTextAudit.render(compose, "gallery", width, scale, heightDp = 800) {
        Themed { GalleryScreen(GalleryUiState(loaded = true), LargeTextAudit.uncalled(), onBack = {}, onReuse = { _, _ -> }) }
    }

    @Test fun imagineDefaultTextFits() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditImagine(411, 1f))

    @Test fun imagineLargestTextFitsOnANarrowPhone() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditImagine(360, 2f))

    @Test fun galleryDefaultTextFits() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditGallery(411, 1f))

    @Test fun galleryLargestTextFitsOnANarrowPhone() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditGallery(360, 2f))
}
