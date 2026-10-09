package dev.wckdboy.autobot.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Hand-drawn 24dp stroke icons (1.75 px, square caps) for the places Material's core set has
 * nothing that fits the instrument look. Tinted by `Icon` like any other vector.
 */
object AutobotIcons {
    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Square,
                strokeLineJoin = StrokeJoin.Miter,
                pathBuilder = block,
            )
        }.build()

    /** `>_` prompt: sessions / agent. */
    val Terminal: ImageVector by lazy {
        icon("Terminal") {
            moveTo(3f, 4f); lineTo(21f, 4f); lineTo(21f, 20f); lineTo(3f, 20f); close()
            moveTo(7f, 9f); lineTo(10f, 12f); lineTo(7f, 15f)
            moveTo(12f, 15f); lineTo(17f, 15f)
        }
    }

    /** Four-point spark: image generation. */
    val Spark: ImageVector by lazy {
        icon("Spark") {
            moveTo(12f, 2.5f); lineTo(14f, 10f); lineTo(21.5f, 12f); lineTo(14f, 14f)
            lineTo(12f, 21.5f); lineTo(10f, 14f); lineTo(2.5f, 12f); lineTo(10f, 10f); close()
        }
    }

    /** Die with pins: models / engines. */
    val Chip: ImageVector by lazy {
        icon("Chip") {
            moveTo(6f, 6f); lineTo(18f, 6f); lineTo(18f, 18f); lineTo(6f, 18f); close()
            moveTo(10f, 10f); lineTo(14f, 10f); lineTo(14f, 14f); lineTo(10f, 14f); close()
            moveTo(9f, 2f); lineTo(9f, 6f); moveTo(15f, 2f); lineTo(15f, 6f)
            moveTo(9f, 18f); lineTo(9f, 22f); moveTo(15f, 18f); lineTo(15f, 22f)
            moveTo(2f, 9f); lineTo(6f, 9f); moveTo(2f, 15f); lineTo(6f, 15f)
            moveTo(18f, 9f); lineTo(22f, 9f); moveTo(18f, 15f); lineTo(22f, 15f)
        }
    }

    /** Stacked sliders: system / settings. */
    val Sliders: ImageVector by lazy {
        icon("Sliders") {
            moveTo(3f, 6f); lineTo(21f, 6f); moveTo(3f, 12f); lineTo(21f, 12f); moveTo(3f, 18f); lineTo(21f, 18f)
            moveTo(8f, 4f); lineTo(8f, 8f); moveTo(16f, 10f); lineTo(16f, 14f); moveTo(11f, 16f); lineTo(11f, 20f)
        }
    }

    /** Brush: paint mask. */
    val Brush: ImageVector by lazy {
        icon("Brush") {
            moveTo(20f, 3f); lineTo(10f, 13f); moveTo(13f, 13f); lineTo(10f, 10f)
            moveTo(9f, 14f); curveTo(6f, 14f, 5f, 16f, 5f, 18f); curveTo(5f, 19.5f, 4f, 20.5f, 3f, 21f)
            curveTo(7f, 21f, 10f, 20f, 10f, 16.5f); close()
        }
    }

    /** Eraser: unpaint mask. */
    val Eraser: ImageVector by lazy {
        icon("Eraser") {
            moveTo(8f, 20f); lineTo(3.5f, 15.5f); lineTo(14f, 5f); lineTo(20f, 11f); lineTo(11f, 20f); close()
            moveTo(9f, 10f); lineTo(15f, 16f); moveTo(11f, 20f); lineTo(21f, 20f)
        }
    }

    /** Die face: randomize seed. */
    val Dice: ImageVector by lazy {
        icon("Dice") {
            moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close()
            moveTo(8.5f, 8.5f); lineTo(8.6f, 8.6f); moveTo(15.5f, 15.5f); lineTo(15.6f, 15.6f)
            moveTo(12f, 12f); lineTo(12.1f, 12.1f)
        }
    }

    /** Stacked layers: LoRA stack. */
    val Layers: ImageVector by lazy {
        icon("Layers") {
            moveTo(12f, 3f); lineTo(21f, 8f); lineTo(12f, 13f); lineTo(3f, 8f); close()
            moveTo(3f, 12.5f); lineTo(12f, 17.5f); lineTo(21f, 12.5f)
            moveTo(3f, 17f); lineTo(12f, 22f); lineTo(21f, 17f)
        }
    }

    /** Framed image: init image / gallery. */
    val Image: ImageVector by lazy {
        icon("Image") {
            moveTo(3f, 4f); lineTo(21f, 4f); lineTo(21f, 20f); lineTo(3f, 20f); close()
            moveTo(3f, 16f); lineTo(9f, 11f); lineTo(14f, 15f); lineTo(17f, 12.5f); lineTo(21f, 16f)
            moveTo(15.5f, 8.5f); lineTo(15.6f, 8.6f)
        }
    }

    /** Wrench: tools. */
    val Wrench: ImageVector by lazy {
        icon("Wrench") {
            moveTo(4f, 20f); lineTo(12.5f, 11.5f)
            curveTo(11f, 8f, 13f, 4f, 17f, 4f); lineTo(15f, 7f); lineTo(17f, 9f); lineTo(20f, 7f)
            curveTo(20f, 11f, 16f, 13f, 12.5f, 11.5f)
        }
    }

    /** Shield: privacy / permissions. */
    val Shield: ImageVector by lazy {
        icon("Shield") {
            moveTo(12f, 2.5f); lineTo(20f, 5.5f); lineTo(20f, 11f); curveTo(20f, 16f, 16.5f, 19.5f, 12f, 21.5f)
            curveTo(7.5f, 19.5f, 4f, 16f, 4f, 11f); lineTo(4f, 5.5f); close()
        }
    }

    /** Counter-clockwise arrow: undo stroke. */
    val Undo: ImageVector by lazy {
        icon("Undo") {
            moveTo(4f, 9f); lineTo(15f, 9f); curveTo(18.5f, 9f, 20.5f, 11.5f, 20.5f, 14.5f)
            curveTo(20.5f, 17.5f, 18.5f, 20f, 15f, 20f); lineTo(9f, 20f)
            moveTo(8f, 5f); lineTo(4f, 9f); lineTo(8f, 13f)
        }
    }

    /** Swap halves: invert mask. */
    val Invert: ImageVector by lazy {
        icon("Invert") {
            moveTo(12f, 3f); curveTo(17f, 3f, 21f, 7f, 21f, 12f); curveTo(21f, 17f, 17f, 21f, 12f, 21f)
            curveTo(7f, 21f, 3f, 17f, 3f, 12f); curveTo(3f, 7f, 7f, 3f, 12f, 3f); close()
            moveTo(12f, 3f); lineTo(12f, 21f)
            moveTo(12f, 7f); lineTo(17f, 7f); moveTo(12f, 11f); lineTo(20f, 11f); moveTo(12f, 15f); lineTo(19f, 15f)
        }
    }

    /** Down arrow into tray: download / save. */
    val Download: ImageVector by lazy {
        icon("Download") {
            moveTo(12f, 3f); lineTo(12f, 15f); moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f)
            moveTo(4f, 17f); lineTo(4f, 21f); lineTo(20f, 21f); lineTo(20f, 17f)
        }
    }
}
