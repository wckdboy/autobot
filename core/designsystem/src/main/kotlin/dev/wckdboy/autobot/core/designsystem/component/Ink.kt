package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.theme.Motion

// Pictures in progress, drawn flat (BRAND.md: no halftones, no textures): a plain preview and an
// image that fades in. Progress itself is Material's (JobProgress). Animated values are read only
// while drawing.

/**
 * Where an [srcWidth]×[srcHeight] image lands in a [dstWidth]×[dstHeight] box under [scale],
 * centred (as [Image] draws it). A plate sampling the image must use this, not the whole box:
 * with Crop or Fit into a box of another shape, dots sampled over the box show the picture
 * stretched, off register with the photo fading in under them.
 */
internal fun contentRect(srcWidth: Float, srcHeight: Float, dstWidth: Float, dstHeight: Float, scale: ContentScale): Rect {
    if (srcWidth <= 0f || srcHeight <= 0f || dstWidth <= 0f || dstHeight <= 0f) return Rect(0f, 0f, dstWidth, dstHeight)
    val f = scale.computeScaleFactor(Size(srcWidth, srcHeight), Size(dstWidth, dstHeight))
    val w = srcWidth * f.scaleX
    val h = srcHeight * f.scaleY
    return Rect((dstWidth - w) / 2f, (dstHeight - h) / 2f, (dstWidth + w) / 2f, (dstHeight + h) / 2f)
}

/**
 * A picture being made: a flat surfaceContainer panel, with the engine's latest [preview] (when
 * it sends one) shown plainly over it. Progress is the job's [JobProgress], not this panel.
 */
@Composable
fun DevelopingPreview(
    preview: ImageBitmap?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
        if (preview != null) Image(preview, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
    }
}

/**
 * A finished picture arriving: when [animate] (a live arrival) it fades in over `printMillis`;
 * otherwise (and with reduced motion, over `reducedMotionMillis`) it is simply shown.
 * [key] identifies the picture across re-decoded bitmaps (e.g. a gallery id) so it fades once.
 */
@Composable
fun PrintReveal(
    image: ImageBitmap,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    key: Any? = image,
) {
    val reduced = LocalInkMotion.current.reduced
    val fade = remember(key) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(key) {
        if (fade.value < 1f) fade.animateTo(1f, tween(if (reduced) Motion.reducedMotionMillis else Motion.printMillis / 2, easing = LinearEasing))
    }
    Image(
        image,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier.graphicsLayer { alpha = fade.value },
    )
}
