package dev.wckdboy.autobot.feature.studio.ui

import dev.wckdboy.autobot.shared.studio.StudioRoomWords
import dev.wckdboy.autobot.core.designsystem.component.AutobotMark
import dev.wckdboy.autobot.core.designsystem.component.NoticeTone
import dev.wckdboy.autobot.core.designsystem.component.Caption
import dev.wckdboy.autobot.core.designsystem.component.DropdownField
import dev.wckdboy.autobot.core.designsystem.component.Field
import dev.wckdboy.autobot.core.designsystem.component.NoticeCard
import dev.wckdboy.autobot.core.designsystem.component.SliderField
import dev.wckdboy.autobot.core.designsystem.component.TonalLabel
import dev.wckdboy.autobot.core.designsystem.component.labelCase
import dev.wckdboy.autobot.core.designsystem.component.linesFor
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.icon.AutobotIcons
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.theme.Motion
import dev.wckdboy.autobot.feature.imagine.generate.ImageCodec
import dev.wckdboy.autobot.shared.studio.AssetRef
import dev.wckdboy.autobot.shared.studio.AssetType
import dev.wckdboy.autobot.shared.studio.ParamType
import dev.wckdboy.autobot.shared.studio.ParamValue
import dev.wckdboy.autobot.shared.studio.StudioAsset
import dev.wckdboy.autobot.shared.studio.WorkflowParam
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where the rooms get asset pixels and sound: decrypted previews and bytes (the app: the
 * encrypted Studio store; snapshot tests: fixtures).
 */
interface AssetLoader {
    suspend fun thumbnail(id: String): ImageBitmap?
    suspend fun peaks(id: String): FloatArray?
    suspend fun bytes(id: String): ByteArray?
}

val LocalAssetLoader = staticCompositionLocalOf<AssetLoader> {
    object : AssetLoader {
        override suspend fun thumbnail(id: String): ImageBitmap? = null
        override suspend fun peaks(id: String): FloatArray? = null
        override suspend fun bytes(id: String): ByteArray? = null
    }
}

/** An asset's preview: its thumbnail (image / video) or waveform (audio); a flat panel while it loads. */
@Composable
fun AssetThumb(asset: StudioAsset, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val loader = LocalAssetLoader.current
    Box(modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
        when (asset.type) {
            AssetType.AUDIO -> {
                val peaks by produceState<FloatArray?>(null, asset.id) { value = loader.peaks(asset.id) }
                Waveform(peaks, Modifier.fillMaxSize().padding(6.dp))
            }
            else -> {
                val image by produceState<ImageBitmap?>(null, asset.id) { value = loader.thumbnail(asset.id) }
                val img = image
                if (img != null) {
                    Image(img, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
                }
                if (asset.type == AssetType.VIDEO) {
                    TonalLabel("▶ ${duration(asset.durationMs)}", Modifier.align(Alignment.BottomStart).padding(4.dp))
                }
            }
        }
        if (asset.favorite) {
            Icon(Icons.Filled.Star, contentDescription = "Favourite", Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
        }
    }
}

/** Decodes preview-frame bytes off the main thread. */
@Composable
fun rememberDecoded(bytes: ByteArray?, maxSide: Int = 768): ImageBitmap? {
    val state by produceState<ImageBitmap?>(null, bytes) {
        value = bytes?.let { b -> withContext(Dispatchers.Default) { ImageCodec.decode(b, maxSide)?.asImageBitmap() } }
    }
    return state
}

/** A clip's waveform as bars; [progress] (0…1) marks the played part in purple. */
@Composable
fun Waveform(
    peaks: FloatArray?,
    modifier: Modifier = Modifier,
    progress: Float = -1f,
    trim: ClosedFloatingPointRange<Float> = 0f..1f,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val played = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val p = peaks
        if (p == null || p.isEmpty()) {
            drawLine(dim, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
            return@Canvas
        }
        val bar = size.width / p.size
        val gap = (bar * 0.3f).coerceAtMost(2.dp.toPx())
        for (i in p.indices) {
            val x = i * bar
            val f = (i + 0.5f) / p.size
            val h = (p[i] * size.height).coerceAtLeast(1.dp.toPx())
            val c = when {
                f !in trim -> dim
                progress >= 0f && f <= progress -> played
                else -> color
            }
            drawRect(c, Offset(x, (size.height - h) / 2), androidx.compose.ui.geometry.Size((bar - gap).coerceAtLeast(1f), h))
        }
    }
}

/**
 * The control panel under the canvas, on a surfaceContainerLow sheet with Material's drag handle:
 * its header folds it away ("just the canvas") and unfolds it again. Folding is instant with
 * reduced motion.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlSheet(
    expanded: Boolean,
    onToggle: () -> Unit,
    summary: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 300.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduced = LocalInkMotion.current.reduced
    val millis = if (reduced) 0 else Motion.transitionMillis
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) { Column {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .semantics { stateDescription = if (expanded) "Controls shown" else "Controls hidden" },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BottomSheetDefaults.DragHandle(Modifier.padding(top = 2.dp))
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    labelCase(summary),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = linesFor(1, 2),
                    overflow = TextOverflow.Ellipsis,
                )
                Text(if (expanded) "Hide" else "Controls", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Icon(if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(millis)) + fadeIn(tween(millis)),
            exit = shrinkVertically(tween(millis)) + fadeOut(tween(millis)),
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    } }
}

/** What went wrong, as a notice in the error colour, and a dismiss button. */
@Composable
fun ErrorLine(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    NoticeCard(message, modifier, tone = NoticeTone.ERROR, action = "Dismiss", onAction = onDismiss)
}

/** "runs on <backend>" — where a job's work happens, said before it starts. */
@Composable
fun RunsOn(label: String?, modifier: Modifier = Modifier) {
    val local = label == null
    Text(
        if (local) "● Runs on this phone" else "◐ Runs on $label",
        modifier.semantics { contentDescription = if (local) "Runs on this phone" else "Runs on $label" },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** An empty room: the pixel a, a heading, a plain explanation and what to do. */
@Composable
fun EmptyRoom(words: StudioRoomWords.Empty, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) =
    EmptyRoom(words.title, words.text, modifier, actions)

@Composable
fun EmptyRoom(title: String, body: String, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AutobotMark(size = 24.dp)
        Text(labelCase(title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        actions()
    }
}

/** A row of choices as Material filter chips that scrolls sideways; names show as written. */
@Composable
fun <T> ChipRow(options: List<T>, selected: T?, onSelect: (T) -> Unit, label: (T) -> String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.semantics { role = Role.RadioButton },
            )
        }
        trailing()
    }
}

/**
 * Controls rendered from a workflow's parameter schema, in order; `advanced` ones under "More".
 * Media params pick an asset of the right type from [assets].
 */
@Composable
fun ParamControls(
    params: List<WorkflowParam>,
    values: Map<String, ParamValue>,
    onChange: (String, ParamValue) -> Unit,
    assets: List<StudioAsset>,
    modifier: Modifier = Modifier,
) {
    var more by rememberSaveable { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val (basic, advanced) = params.partition { !it.advanced }
        basic.forEach { ParamControl(it, values[it.name] ?: it.default, onChange, assets) }
        if (advanced.isNotEmpty()) {
            TextButton(onClick = { more = !more }) { Text(if (more) "Less" else "More · ${advanced.size}") }
            if (more) advanced.forEach { ParamControl(it, values[it.name] ?: it.default, onChange, assets) }
        }
    }
}

@Composable
private fun ParamControl(p: WorkflowParam, value: ParamValue?, onChange: (String, ParamValue) -> Unit, assets: List<StudioAsset>) {
    when (p.type) {
        ParamType.STRING -> Field(
            value?.display().orEmpty(),
            { onChange(p.name, ParamValue.of(it)) },
            Modifier.fillMaxWidth(),
            label = p.label,
            minLines = if (p.multiline) 3 else 1,
            maxLines = if (p.multiline) 8 else 1,
            singleLine = !p.multiline,
        )
        ParamType.ENUM -> DropdownField(p.label, value?.display() ?: "—", p.options, { onChange(p.name, ParamValue.of(it)) }, Modifier.fillMaxWidth())
        ParamType.BOOL -> {
            val checked = (value as? ParamValue.BoolValue)?.value ?: false
            Row(
                Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = { onChange(p.name, ParamValue.of(it)) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(p.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = checked, onCheckedChange = null)
            }
        }
        ParamType.INT, ParamType.FLOAT -> {
            val min = p.min
            val max = p.max
            val number = value?.asDouble() ?: min ?: 0.0
            val int = p.type == ParamType.INT
            if (min != null && max != null && max > min && (int.not() || max - min <= 4096)) {
                SliderField(
                    p.label,
                    number.toFloat().coerceIn(min.toFloat(), max.toFloat()),
                    { v ->
                        val step = p.step ?: if (int) 1.0 else 0.01
                        val snapped = Math.round(v / step) * step
                        onChange(p.name, if (int) ParamValue.of(snapped.toLong()) else ParamValue.of(snapped))
                    },
                    min.toFloat()..max.toFloat(),
                    format = { if (int) it.toLong().toString() else String.format(Locale.ROOT, "%.2f", it) },
                )
            } else {
                NumberField(p.label, value?.display().orEmpty(), int) { text ->
                    (if (int) text.toLongOrNull()?.let(ParamValue::of) else text.toDoubleOrNull()?.let(ParamValue::of))?.let { onChange(p.name, it) }
                }
            }
        }
        ParamType.SEED -> NumberField(p.label, value?.display().orEmpty(), integer = true) { text -> text.toLongOrNull()?.let { onChange(p.name, ParamValue.of(it)) } }
        ParamType.IMAGE, ParamType.AUDIO -> {
            val type = if (p.type == ParamType.IMAGE) AssetType.IMAGE else AssetType.AUDIO
            val choices = assets.filter { it.type == type }.take(24)
            val picked = (value as? ParamValue.AssetValue)?.ref?.assetId
            Text(labelCase(p.label + if (picked == null) " · pick one" else ""), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (choices.isEmpty()) {
                Caption("no ${type.name.lowercase()} in this project yet — make or import one")
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    choices.forEach { a ->
                        AssetThumb(
                            a,
                            Modifier
                                .size(64.dp)
                                .then(if (a.id == picked) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small) else Modifier)
                                .clickable { onChange(p.name, ParamValue.AssetValue(AssetRef(a.id, p.role ?: p.name))) }
                                .semantics { contentDescription = "Use ${a.label ?: a.id} for ${p.label}" },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NumberField(label: String, value: String, integer: Boolean, onValue: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Field(
        text,
        {
            text = it
            onValue(it)
        },
        Modifier.fillMaxWidth(),
        label = label,
        singleLine = true,
        mono = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal),
    )
}

/** A short action ("Upscale ×2", "+ PC") as Material's assist chip, in sentence case. */
@Composable
fun ActionChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    AssistChip(onClick = onClick, label = { Text(labelCase(text)) }, modifier = modifier, enabled = enabled)
}

/** A toggle or a pick among a few ("16:9 · 768×432", "Inpaint") as Material's filter chip. */
@Composable
fun PickChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(text) }, modifier = modifier)
}

/** `1:05` / `0:08`. */
fun duration(ms: Long): String {
    val s = (ms + 500) / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** Section scaffold: a full-height scrolling column with the room's gutter. */
@Composable
fun RoomColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** The "image" placeholder icon for empty media slots. */
@Composable
fun MediaPlaceholder(type: AssetType, modifier: Modifier = Modifier) {
    Box(modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerLow), contentAlignment = Alignment.Center) {
        Icon(
            when (type) {
                AssetType.IMAGE -> AutobotIcons.Image
                AssetType.AUDIO -> AutobotIcons.Speaker
                AssetType.VIDEO -> AutobotIcons.Layers
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
