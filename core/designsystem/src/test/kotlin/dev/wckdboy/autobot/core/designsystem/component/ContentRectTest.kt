package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import org.junit.Assert.assertEquals
import org.junit.Test

/** The print plate samples the picture where [androidx.compose.foundation.Image] draws it. */
class ContentRectTest {
    private fun assertRect(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 0.01f)
        assertEquals(expected.top, actual.top, 0.01f)
        assertEquals(expected.right, actual.right, 0.01f)
        assertEquals(expected.bottom, actual.bottom, 0.01f)
    }

    @Test
    fun aWideImageCroppedIntoASquareOverflowsLeftAndRight() {
        // A 16:9 agent image in the chat's 120 dp square thumbnail (here 300 px).
        assertRect(Rect(-116.67f, 0f, 416.67f, 300f), contentRect(1600f, 900f, 300f, 300f, ContentScale.Crop))
    }

    @Test
    fun aTallImageFittedIntoASquareIsLetterboxed() {
        assertRect(Rect(75f, 0f, 225f, 300f), contentRect(512f, 1024f, 300f, 300f, ContentScale.Fit))
    }

    @Test
    fun anImageOfTheBoxShapeFillsIt() {
        assertRect(Rect(0f, 0f, 400f, 600f), contentRect(512f, 768f, 400f, 600f, ContentScale.Crop))
        assertRect(Rect(0f, 0f, 400f, 600f), contentRect(0f, 0f, 400f, 600f, ContentScale.Fit))
    }
}
