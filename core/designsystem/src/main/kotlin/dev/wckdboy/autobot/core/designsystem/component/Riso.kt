package dev.wckdboy.autobot.core.designsystem.component

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.R
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Where a run executes. Shown as a tag on every card and row. */
enum class Route(val glyph: String, val label: String, val local: Boolean) {
    LOCAL_NPU("●", "local · npu", true),
    LOCAL_GPU("●", "local · gpu", true),
    LOCAL_CPU("●", "local · cpu", true),
    PC_LAN("◐", "pc · lan", false),
    CLOUD_API("○", "cloud · api", false),
}

/** `● LOCAL · CPU` (filled red) / `◐ PC · LAN` / `○ CLOUD · API` (outlined). */
@Composable
fun RouteTag(route: Route, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.extraSmall
    val base = if (route.local) {
        modifier.background(MaterialTheme.colorScheme.primary, shape)
    } else {
        modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
    }
    Text(
        "${route.glyph} ${route.label}".uppercase(Locale.ROOT),
        modifier = base.padding(horizontal = 6.dp, vertical = 3.dp),
        style = AutobotTheme.styles.micro,
        color = if (route.local) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
    )
}

/** Red `// SECTION · DETAIL` line. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        "// ${text.uppercase(Locale.ROOT)}",
        modifier = modifier,
        style = AutobotTheme.styles.micro,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Full-width red block button with a mono caps label. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 52.dp),
        shape = MaterialTheme.shapes.extraSmall,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) { Text(text.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelLarge) }
}

/** Outlined block button; [accent] tints border and label (e.g. red for DENY). */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, if (enabled) accent else MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) { Text(text.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelLarge) }
}

/** Cream block button (APPROVE on red cards). */
@Composable
fun PaperButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.extraSmall,
        colors = ButtonDefaults.buttonColors(containerColor = AutobotColors.Bone100, contentColor = AutobotColors.PaperInk),
    ) { Text(text.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelLarge) }
}

/** The brand mark (halftone "a" on red). */
@Composable
fun AutobotMark(modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Image(painterResource(R.drawable.ic_autobot_mark), contentDescription = null, modifier = modifier.size(size))
}

/** Top bar: mark + condensed wordmark (or a title) and trailing actions. */
@Composable
fun AutobotTopBar(
    modifier: Modifier = Modifier,
    title: String = "AUTOBOT",
    showMark: Boolean = true,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 52.dp).padding(start = if (navigation == null) 16.dp else 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            navigation?.invoke()
            if (showMark && navigation == null) AutobotMark()
            Text(
                title.uppercase(Locale.ROOT),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
    }
}

/** Small boxed counter (e.g. `16+` / running jobs). */
@Composable
fun CountBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall).padding(horizontal = 6.dp, vertical = 3.dp),
        style = AutobotTheme.styles.micro,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** A mode tile on the Run screen: `03` / big title / model / route tag. */
@Composable
fun ModeCard(
    index: String,
    title: String,
    subtitle: String,
    route: Route?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        val card = MaterialTheme.colorScheme.surfaceContainerHigh
        Box {
            if (background != null) {
                background()
                // Fade the art into the card colour so the title stays legible over dense dots.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(0.45f to Color.Transparent, 0.78f to card.copy(alpha = 0.92f), 1f to card)),
                )
            }
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        index,
                        modifier = if (background != null) Modifier.background(card).padding(horizontal = 4.dp, vertical = 2.dp) else Modifier,
                        style = AutobotTheme.styles.micro,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    route?.let { RouteTag(it) }
                }
                Spacer(Modifier.weight(1f, fill = true))
                Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    subtitle,
                    style = AutobotTheme.styles.micro,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Hairline-separated list row: title, mono subtitle, trailing slot. */
@Composable
fun RunRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = AutobotTheme.styles.micro, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing()
        }
        Hairline()
    }
}

/** `STEPS` over a big condensed number. */
@Composable
fun BigReadout(label: String, value: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Column(
        modifier.then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        MicroLabel(label)
        Text(value, style = AutobotTheme.styles.number, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/**
 * Renders an image as halftone dots (riso print look): the image is sampled on a grid and each
 * cell becomes a dot whose radius follows the cell's darkness.
 */
@Composable
fun HalftoneImage(
    image: ImageBitmap,
    modifier: Modifier = Modifier,
    dot: Color = MaterialTheme.colorScheme.primary,
    paper: Color = Color.Transparent,
    cell: Dp = 6.dp,
) {
    val samples = remember(image) { luminanceGrid(image.asAndroidBitmap(), 96) }
    Canvas(modifier.background(paper).clipToBounds()) {
        val c = cell.toPx()
        val cols = max(1, (size.width / c).toInt())
        val rows = max(1, (size.height / c).toInt())
        for (r in 0 until rows) for (col in 0 until cols) {
            val sx = (col + 0.5f) / cols
            val sy = (r + 0.5f) / rows
            val l = samples.sample(sx, sy)
            val radius = (1f - l) * c * 0.62f
            if (radius > 0.25f) drawCircle(dot, radius, Offset((col + 0.5f) * c, (r + 0.5f) * c))
        }
    }
}

/** Procedural halftone field (hero art): dots swell along a soft diagonal wave. */
@Composable
fun HalftoneField(modifier: Modifier = Modifier, dot: Color = MaterialTheme.colorScheme.primary, cell: Dp = 9.dp, phase: Float = 0f) {
    Canvas(modifier.clipToBounds()) {
        val c = cell.toPx()
        val cols = (size.width / c).toInt() + 1
        val rows = (size.height / c).toInt() + 1
        for (r in 0 until rows) for (col in 0 until cols) {
            val x = col / cols.toFloat()
            val y = r / rows.toFloat()
            val wave = 0.5f + 0.5f * sin((x * 3.1f + y * 2.3f + phase) * 3.14159f).toFloat()
            val blob = 1f - min(1f, sqrt((x - 0.62f) * (x - 0.62f) * 2.2f + (y - 0.38f) * (y - 0.38f) * 3.0f))
            val v = (0.55f * wave + 0.75f * blob - 0.25f * y).coerceIn(0f, 1f)
            val radius = v * c * 0.6f
            if (radius > 0.3f) drawCircle(dot, radius, Offset(col * c, r * c))
        }
    }
}

/** Downsampled luminance grid used by [HalftoneImage]. */
private class LumaGrid(val w: Int, val h: Int, val values: FloatArray) {
    fun sample(x: Float, y: Float): Float {
        val ix = (x * w).toInt().coerceIn(0, w - 1)
        val iy = (y * h).toInt().coerceIn(0, h - 1)
        return values[iy * w + ix]
    }
}

private fun luminanceGrid(bitmap: Bitmap, longSide: Int): LumaGrid {
    val scale = longSide.toFloat() / max(bitmap.width, bitmap.height)
    val w = max(1, (bitmap.width * scale).toInt())
    val h = max(1, (bitmap.height * scale).toInt())
    val small = Bitmap.createScaledBitmap(bitmap.copy(Bitmap.Config.ARGB_8888, false), w, h, true)
    val px = IntArray(w * h)
    small.getPixels(px, 0, w, 0, 0, w, h)
    val values = FloatArray(px.size) { i ->
        val p = px[i]
        ((p shr 16 and 0xFF) * 0.299f + (p shr 8 and 0xFF) * 0.587f + (p and 0xFF) * 0.114f) / 255f
    }
    return LumaGrid(w, h, values)
}

@Preview(name = "Riso kit", showBackground = true, backgroundColor = 0xFF0E0E0E, widthDp = 380)
@Composable
private fun RisoPreview() {
    AutobotTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AutobotTopBar(actions = { CountBadge("16+") })
            SectionLabel("this phone · cpu idle · 9.1 gb free")
            Text("Run anything.", style = MaterialTheme.typography.displayMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Route.entries.forEach { RouteTag(it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeCard("02", "Chat", "Qwen3 4B · Q4", Route.LOCAL_CPU, {}, Modifier.weight(1f).height(120.dp))
                ModeCard("03", "Agent", "Claude Sonnet", Route.CLOUD_API, {}, Modifier.weight(1f).height(120.dp))
            }
            RunRow("Isometric greenhouse, riso print", "IMAGE · 12 S") { RouteTag(Route.LOCAL_CPU) }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                BigReadout("Steps", "4")
                BigReadout("Seed", "4182")
                BigReadout("Size", "768²")
            }
            PrimaryButton("Set up this phone", {}, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Deny", {}, Modifier.weight(1f), accent = MaterialTheme.colorScheme.primary)
                PaperButton("Approve", {}, Modifier.weight(1f))
            }
            HalftoneField(Modifier.fillMaxWidth().height(120.dp))
            Spacer(Modifier.width(1.dp))
        }
    }
}
