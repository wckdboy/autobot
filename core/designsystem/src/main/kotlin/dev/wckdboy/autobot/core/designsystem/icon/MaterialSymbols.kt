package dev.wckdboy.autobot.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Material Symbols (Outlined, weight 400, 24 dp; Apache 2.0, google/material-design-icons) that
 * Material's core icon set lacks, for the Run screen's mode cards. Each one matches the SF Symbol
 * the iOS app uses for the same mode. Tinted by `Icon` like any other vector.
 */
object MaterialSymbols {
    /** [viewport]: the source drawable's viewport (960 for most symbols; some are drawn on 24). */
    private fun symbol(name: String, pathData: String, viewport: Float = 960f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, viewport, viewport).addPath(
            pathData = addPathNodes(pathData),
            fill = SolidColor(Color.Black),
        ).build()

    /** A picture: the Image mode (SF Symbols `photo`). */
    val Image: ImageVector by lazy { symbol("Image", "M200,840Q167,840 143.5,816.5Q120,793 120,760L120,200Q120,167 143.5,143.5Q167,120 200,120L760,120Q793,120 816.5,143.5Q840,167 840,200L840,760Q840,793 816.5,816.5Q793,840 760,840L200,840ZM200,760L760,760Q760,760 760,760Q760,760 760,760L760,200Q760,200 760,200Q760,200 760,200L200,200Q200,200 200,200Q200,200 200,200L200,760Q200,760 200,760Q200,760 200,760ZM240,680L720,680L570,480L450,640L360,520L240,680ZM200,760Q200,760 200,760Q200,760 200,760L200,200Q200,200 200,200Q200,200 200,200L200,200Q200,200 200,200Q200,200 200,200L200,760Q200,760 200,760Q200,760 200,760L200,760Z") }

    /** Two speech bubbles: the Chat mode (SF Symbols `bubble.left.and.bubble.right`). */
    val Forum: ImageVector by lazy { symbol("Forum", "M280,720Q263,720 251.5,708.5Q240,697 240,680L240,600L760,600L760,600L760,240L840,240Q857,240 868.5,251.5Q880,263 880,280L880,880L720,720L280,720ZM80,680L80,120Q80,103 91.5,91.5Q103,80 120,80L640,80Q657,80 668.5,91.5Q680,103 680,120L680,480Q680,497 668.5,508.5Q657,520 640,520L240,520L80,680ZM600,440L600,160L160,160L160,440L160,440L600,440ZM160,440L160,440L160,440L160,160L160,160L160,440Z") }

    /** A wand with sparks: the Agent mode (SF Symbols `wand.and.stars`). */
    val AutoFixHigh: ImageVector by lazy { symbol("AutoFixHigh", "M20,7 L19.05,4.95 17,4 19.05,3.05 20,1 20.95,3.05 23,4 20.95,4.95ZM8.5,7 L7.55,4.95 5.5,4 7.55,3.05 8.5,1 9.45,3.05 11.5,4 9.45,4.95ZM20,18.5 L19.05,16.45 17,15.5 19.05,14.55 20,12.5 20.95,14.55 23,15.5 20.95,16.45ZM5.1,21.7 L2.3,18.9Q2,18.6 2,18.175Q2,17.75 2.3,17.45L13.45,6.3Q13.75,6 14.175,6Q14.6,6 14.9,6.3L17.7,9.1Q18,9.4 18,9.825Q18,10.25 17.7,10.55L6.55,21.7Q6.25,22 5.825,22Q5.4,22 5.1,21.7ZM5.85,19.6 L13,12.4 11.6,11 4.4,18.15Z", viewport = 24f) }

    /** Code brackets: the Code mode (SF Symbols `chevron.left.forwardslash.chevron.right`). */
    val Code: ImageVector by lazy { symbol("Code", "M320,720L80,480L320,240L377,297L193,481L376,664L320,720ZM640,720L583,663L767,479L584,296L640,240L880,480L640,720Z") }

    /** Stacked layers: the Studio (SF Symbols `square.stack`). */
    val Stacks: ImageVector by lazy { symbol("Stacks", "M480,560L40,320L480,80L920,320L480,560ZM480,720L63,493L147,447L480,629L813,447L897,493L480,720ZM480,880L63,653L147,607L480,789L813,607L897,653L480,880ZM480,469L753,320L480,171L207,320L480,469ZM480,320L480,320L480,320L480,320L480,320Z") }
}
