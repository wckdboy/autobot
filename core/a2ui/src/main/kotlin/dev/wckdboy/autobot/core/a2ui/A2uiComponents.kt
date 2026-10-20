@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.wckdboy.autobot.core.a2ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.MarkdownBlocks
import dev.wckdboy.autobot.core.designsystem.icon.AutobotIcons
import dev.wckdboy.autobot.core.designsystem.markdown.LiteMarkdown
import dev.wckdboy.autobot.core.designsystem.markdown.MdLinks
import dev.wckdboy.autobot.shared.a2ui.A2uiChat
import dev.wckdboy.autobot.shared.a2ui.A2uiHost
import dev.wckdboy.autobot.shared.a2ui.RenderNode
import dev.wckdboy.autobot.shared.a2ui.RenderValue
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToLong

/** What every node needs: the host to write to, how to act, how to open a link, media. */
@Stable
internal class A2uiContext(
    val host: A2uiHost,
    val media: A2uiMedia,
    val dispatch: (RenderNode) -> Unit,
    val openLink: (String) -> Unit,
)

/** Inside a Button: text and icons take the button's content colour and type, no margins. */
private val LocalInButton = compositionLocalOf { false }

/** Card nesting depth: a card in a card is outlined, so it stays distinct. */
private val LocalCardDepth = compositionLocalOf { 0 }

/** Set while drawing a Modal's trigger: tapping it opens the modal instead of acting. */
private val LocalModalTrigger = compositionLocalOf<(() -> Unit)?> { null }

/** Leaf margin (the catalog guide's leaf-margin strategy: containers add no spacing, leaves do). */
private val LeafMargin = 4.dp

/** Components that take the full width when their Column stretches its children. */
private val Stretchable = setOf(
    "Text", "Image", "Video", "AudioPlayer", "Row", "Column", "List", "Card", "Tabs", "Divider",
    "TextField", "ChoicePicker", "Slider", "DateTimeInput",
)

/**
 * One node of a render tree. Unknown component types and placeholders (a child that has not
 * arrived) draw nothing.
 */
@Composable
internal fun A2uiNode(node: RenderNode, ctx: A2uiContext, modifier: Modifier = Modifier) {
    if (node.isPlaceholder) return
    val tagged = modifier.testTag(node.key)
    val trigger = LocalModalTrigger.current
    if (trigger != null && node.type != "Button") {
        CompositionLocalProvider(LocalModalTrigger provides null) {
            Box(tagged.clickable(onClickLabel = "Open", role = Role.Button) { trigger() }) { NodeBody(node, ctx, Modifier) }
        }
        return
    }
    NodeBody(node, ctx, tagged)
}

@Composable
private fun NodeBody(node: RenderNode, ctx: A2uiContext, modifier: Modifier) {
    when (node.type) {
        "Text" -> TextNode(node, ctx, modifier)
        "Image" -> ImageNode(node, ctx, modifier)
        "Icon" -> IconNode(node, modifier)
        "Video" -> MediaNode(node, ctx, modifier, video = true)
        "AudioPlayer" -> MediaNode(node, ctx, modifier, video = false)
        "Row" -> RowNode(node, ctx, modifier)
        "Column" -> ColumnNode(node, ctx, modifier)
        "List" -> ListNode(node, ctx, modifier)
        "Card" -> CardNode(node, ctx, modifier)
        "Tabs" -> TabsNode(node, ctx, modifier)
        "Modal" -> ModalNode(node, ctx, modifier)
        "Divider" -> DividerNode(node, modifier)
        "Button" -> ButtonNode(node, ctx, modifier)
        "TextField" -> TextFieldNode(node, ctx, modifier)
        "CheckBox" -> CheckBoxNode(node, ctx, modifier)
        "ChoicePicker" -> ChoicePickerNode(node, ctx, modifier)
        "Slider" -> SliderNode(node, ctx, modifier)
        "DateTimeInput" -> DateTimeNode(node, ctx, modifier)
        else -> Unit
    }
}

// ---- Display ----

@Composable
private fun TextNode(node: RenderNode, ctx: A2uiContext, modifier: Modifier) {
    val text = node.stringOr("text", "")
    if (text.isEmpty()) return
    when {
        // A button's label: plain, in the button's colour and type.
        LocalInButton.current -> Text(stripMarkdown(text), modifier)
        node.string("variant") == "caption" -> Text(
            stripMarkdown(text),
            modifier.padding(LeafMargin),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        looksLikeMarkdown(text) -> MarkdownBlocks(LiteMarkdown.cached(text).blocks, onLink = ctx.openLink, modifier = modifier.padding(LeafMargin))
        else -> Text(text, modifier.padding(LeafMargin), style = MaterialTheme.typography.bodyLarge)
    }
}

private sealed interface ImageState {
    data object Idle : ImageState
    data object Loading : ImageState
    data object Failed : ImageState
    class Loaded(val bitmap: ImageBitmap) : ImageState
}

@Composable
private fun ImageNode(node: RenderNode, ctx: A2uiContext, modifier: Modifier) {
    val url = node.string("url").orEmpty()
    val description = node.string("description")
    val variant = node.string("variant") ?: "mediumFeature"
    val web = isWebUrl(url)
    var state by remember(url) { mutableStateOf<ImageState>(if (web && ctx.media.loadsWithoutAsking) ImageState.Loading else ImageState.Idle) }
    LaunchedEffect(url, state is ImageState.Loading) {
        if (state is ImageState.Loading) state = ctx.media.image(url)?.let { ImageState.Loaded(it) } ?: ImageState.Failed
    }
    val small = variant in FixedImages
    val box = when (variant) {
        "icon" -> Modifier.size(24.dp)
        "avatar" -> Modifier.size(40.dp).clip(CircleShape)
        "smallFeature" -> Modifier.size(100.dp).clip(RoundedCornerShape(8.dp))
        "largeFeature" -> Modifier.fillMaxWidth().heightIn(max = 400.dp).height(280.dp).clip(RoundedCornerShape(12.dp))
        "header" -> Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp))
        else -> Modifier.fillMaxWidth().widthIn(max = 480.dp).height(200.dp).clip(RoundedCornerShape(12.dp))
    }
    val load = { if (ctx.media === A2uiMedia.None) ctx.openLink(url) else state = ImageState.Loading }
    val host = MdLinks.host(url).ifEmpty { url.take(40) }
    Box(
        modifier.padding(LeafMargin).then(box).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .semantics(mergeDescendants = true) {
                contentDescription = description ?: "Image"
                role = Role.Image
            },
        contentAlignment = Alignment.Center,
    ) {
        val s = state
        when {
            s is ImageState.Loaded -> Image(
                s.bitmap,
                contentDescription = description,
                modifier = Modifier.matchParentSize(),
                contentScale = if (variant == "header") ContentScale.Crop else contentScale(node.string("fit")),
            )
            !web -> Icon(AutobotIcons.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            small -> Icon(
                AutobotIcons.Image,
                contentDescription = "Load image from $host",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(enabled = s !is ImageState.Loading, onClick = load),
            )
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(8.dp)) {
                Icon(AutobotIcons.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                description?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                Text(host, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (s) {
                    ImageState.Loading -> Text("Loading…", style = MaterialTheme.typography.labelMedium)
                    else -> TextButton(onClick = load) {
                        Text(
                            when {
                                ctx.media === A2uiMedia.None -> "Open link"
                                s is ImageState.Failed -> "Couldn't load · retry"
                                else -> "Load image"
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun contentScale(fit: String?): ContentScale = when (fit) {
    "contain" -> ContentScale.Fit
    "cover" -> ContentScale.Crop
    "none" -> ContentScale.None
    "scaleDown" -> ContentScale.Inside
    else -> ContentScale.FillBounds
}

@Composable
private fun IconNode(node: RenderNode, modifier: Modifier) {
    val vector = iconOf(node) ?: return
    Icon(vector, contentDescription = null, modifier = if (LocalInButton.current) modifier.size(18.dp) else modifier.padding(LeafMargin).size(24.dp))
}

private fun iconOf(node: RenderNode): ImageVector? = when (val v = node.value("name")) {
    is RenderValue.ObjectValue -> v.fields["svgPath"]?.asText()?.let { A2uiIcons.fromSvgPath(it) }
    RenderValue.Null -> null
    else -> A2uiIcons.forName(v.asText())
}

/** Video and audio: a card that opens the media in the browser (after the link prompt); no in-app player yet. */
@Composable
private fun MediaNode(node: RenderNode, ctx: A2uiContext, modifier: Modifier, video: Boolean) {
    val url = node.string("url").orEmpty()
    val title = node.string("description") ?: if (video) "Video" else "Audio"
    OutlinedCard(modifier.padding(LeafMargin).fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (video) Icons.Filled.PlayArrow else AutobotIcons.Speaker, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(MdLinks.host(url).ifEmpty { url.take(40) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isWebUrl(url)) TextButton(onClick = { ctx.openLink(url) }) { Text(if (video) "Watch" else "Listen") }
        }
    }
}

@Composable
private fun DividerNode(node: RenderNode, modifier: Modifier) {
    if (node.string("axis") == "vertical") {
        VerticalDivider(modifier.padding(horizontal = LeafMargin).height(24.dp))
    } else {
        HorizontalDivider(modifier.padding(vertical = LeafMargin))
    }
}

// ---- Layout ----

@Composable
private fun RowNode(node: RenderNode, ctx: A2uiContext, modifier: Modifier) {
    val justify = node.string("justify")
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = horizontal(justify),
        verticalAlignment = when (node.string("align")) {
            "center" -> Alignment.CenterVertically
            "end" -> Alignment.Bottom
            else -> Alignment.Top
        },
    ) {
        node.nodes("children").forEach { child ->
            val weight = child.number("weight")?.takeIf { it > 0 }?.toFloat() ?: if (justify == "stretch") 1f else null
            A2uiNode(child, ctx, if (weight != null) Modifier.weight(weight) else Modifier)
        }
    }
}

/** A column; `weight` is ignored here (a chat column has no fixed height to share out). */
@Composable
private fun ColumnNode(node: RenderNode, ctx: A2uiContext, modifier: Modifier) {
    val align = node.string("align") ?: "stretch"
    Column(modifier, verticalArrangement = vertical(node.string("justify")), horizontalAlignment = horizontalAlignment(align)) {
        node.nodes("children").forEach { child -> A2uiNode(child, ctx, stretch(child, align)) }
    }
}

@Composable
private fun ListNode(node: RenderNode, ctx: A2uiContext, modifier: Modifier) {
    val align = node.string("align") ?: "stretch"
    if (node.string("direction") == "horizontal") {
        Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.Top) {
            node.nodes("children").forEach { A2uiNode(it, ctx, Modifier.widthIn(max = 280.dp)) }
        }
    } else {
        // Not lazy: the surface sits inside the chat's own scrolling list.
        Column(modifier.fillMaxWidth(), horizontalAlignment = horizontalAlignment(align)) {
            node.nodes("children").forEach { A2uiNode(it, ctx, stretch(it, align)) }
        }
    }
}

private fun stretch(child: RenderNode, align: String): Modifier =
    if (align == "stretch" && child.type in Stretchable && !(child.type == "Image" && child.string("variant") in FixedImages)) Modifier.fillMaxWidth() else Modifier

/** Image variants with a fixed size: never stretched, loaded by tapping the image itself. */
private val FixedImages = setOf("icon", "avatar", "smallFeature")

private fun horizontal(justify: String?): Arrangement.Horizontal = when (justify) {
    "center" -> Arrangement.Center
    "end" -> Arrangement.End
    "spaceBetween" -> Arrangement.SpaceBetween
    "spaceAround" -> Arrangement.SpaceAround
    "spaceEvenly" -> Arrangement.SpaceEvenly
    else -> Arrangement.Start
}

private fun vertical(justify: String?): Arrangement.Vertical = when (justify) {
    "center" -> Arrangement.Center
    "end" -> Arrangement.Bottom
    "spaceBetween" -> Arrangement.SpaceBetween
    "spaceAround" -> Arrangement.SpaceAround
    "spaceEvenly" -> Arrangement.SpaceEvenly
    else -> Arrangement.Top
}

private fun horizontalAlignment(align: String): Alignment.Horizontal = when (align) {
    "center" -> Alignment.CenterHorizontally
    "end" -> Alignment.End
    else -> Alignment.Start
}

@Composable
private fun CardNode(node: RenderNode, ctx: A2uiContext, modifier: Modifier) {
    val depth = LocalCardDepth.current
    val content: @Composable () -> Unit = {
        CompositionLocalProvider(LocalCardDepth provides depth + 1) {
            Box(Modifier.padding(12.dp)) { node.node("child")?.let { A2uiNode(it, ctx, Modifier.fillMaxWidth()) } }
        }
    }
    if (depth == 0) {
        Card(modifier.padding(LeafMargin), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) { content() }
    } else {
        OutlinedCard(modifier.padding(LeafMargin)) { content() }
    }
}

@Composable
private fun TabsNode(node: RenderNode, ctx: A2uiContext, modifier: Modifier) {
    val tabs = (node.value("tabs") as? RenderValue.ListValue)?.items.orEmpty().mapNotNull { it as? RenderValue.ObjectValue }
    if (tabs.isEmpty()) return
    var selected by rememberSaveable(node.key) { mutableIntStateOf(0) }
    val index = selected.coerceIn(0, tabs.lastIndex)
    Column(modifier) {
        PrimaryTabRow(selectedTabIndex = index, containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f)) {
            tabs.forEachIndexed { i, tab ->
                Tab(selected = i == index, onClick = { selected = i }, text = { Text(tab.fields["title"]?.asText().orEmpty(), maxLines = 1) })
            }
        }
        (tabs[index].fields["child"] as? RenderValue.NodeValue)?.node?.let { A2uiNode(it, ctx, Modifier.fillMaxWidth().padding(top = 8.dp)) }
    }
}

/** A Modal: its trigger in place; a tap opens the content in a bottom sheet (swipe, scrim or ✕ closes it). */
@Composable
private fun ModalNode(node: RenderNode, ctx: A2uiContext, modifier: Modifier) {
    var open by rememberSaveable(node.key) { mutableStateOf(false) }
    node.node("trigger")?.let { trigger ->
        CompositionLocalProvider(LocalModalTrigger provides { open = true }) { A2uiNode(trigger, ctx, modifier) }
    }
    val content = node.node("content")
    if (open && content != null) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { open = false }) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                }
                A2uiNode(content, ctx, Modifier.fillMaxWidth())
            }
        }
    }
}

// ---- Interactive ----

@Composable
private fun ButtonNode(node: RenderNode, ctx: A2uiContext, modifier: Modifier) {
    val trigger = LocalModalTrigger.current
    val child = node.node("child")
    val enabled = trigger != null || (node.action != null && node.isValid)
    val onClick: () -> Unit = trigger ?: { ctx.dispatch(node) }
    // An icon-only button is named after its icon.
    val label = child?.takeIf { it.type == "Icon" }?.string("name")?.let { A2uiChat.humanize(it) }
    val problem = if (trigger == null && !node.isValid) node.firstError else null
    val semantics = Modifier.semantics {
        if (label != null) contentDescription = label
        if (problem != null) stateDescription = problem
    }
    val content: @Composable () -> Unit = {
        CompositionLocalProvider(LocalInButton provides true, LocalModalTrigger provides null) {
            child?.let { A2uiNode(it, ctx) }
        }
    }
    Column(modifier.padding(LeafMargin)) {
        when (node.string("variant")) {
            "primary" -> Button(onClick, semantics, enabled = enabled) { content() }
            "borderless" -> TextButton(onClick, semantics, enabled = enabled) { content() }
            else -> FilledTonalButton(onClick, semantics, enabled = enabled) { content() }
        }
        if (problem != null) {
            Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun TextFieldNode(node: RenderNode, ctx: A2uiContext, modifier: Modifier) {
    val bound = node.string("value").orEmpty()
    var field by remember(node.key) { mutableStateOf(TextFieldValue(bound, TextRange(bound.length))) }
    var touched by rememberSaveable(node.key) { mutableStateOf(false) }
    // The agent (or another input on the same path) changed the value.
    if (field.text != bound && ctx.host.surface(node.surfaceId) != null && node.binding("value") != null) {
        field = TextFieldValue(bound, TextRange(bound.length))
    }
    val variant = node.string("variant") ?: "shortText"
    val error = if (touched) node.firstError else null
    OutlinedTextField(
        value = field,
        onValueChange = { v ->
            field = v
            touched = true
            ctx.host.setString(node, "value", v.text)
        },
        modifier = modifier.padding(LeafMargin).semantics { if (error != null) error(error) },
        label = node.string("label")?.let { { Text(it) } },
        placeholder = node.string("placeholder")?.let { { Text(it) } },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = variant != "longText",
        minLines = if (variant == "longText") 3 else 1,
        visualTransformation = if (variant == "obscured") PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = when (variant) {
            "number" -> KeyboardOptions(keyboardType = KeyboardType.Decimal)
            "obscured" -> KeyboardOptions(keyboardType = KeyboardType.Password)
            else -> KeyboardOptions.Default
        },
    )
}

@Composable
private fun CheckBoxNode(node: RenderNode, ctx: A2uiContext, modifier: Modifier) {
    val checked = node.boolOr("value", false)
    Column(modifier.padding(LeafMargin)) {
        Row(
            Modifier.toggleable(value = checked, role = Role.Checkbox) { ctx.host.setBoolean(node, "value", it) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
            Text(node.stringOr("label", ""), style = MaterialTheme.typography.bodyLarge)
        }
        node.firstError?.let { ErrorLine(it) }
    }
}

private class Choice(val label: String, val value: String)

@Composable
private fun ChoicePickerNode(node: RenderNode, ctx: A2uiContext, modifier: Modifier) {
    val options = (node.value("options") as? RenderValue.ListValue)?.items.orEmpty().mapNotNull { o ->
        val f = (o as? RenderValue.ObjectValue)?.fields ?: return@mapNotNull null
        val value = f["value"]?.asText() ?: return@mapNotNull null
        Choice(f["label"]?.asText()?.ifEmpty { null } ?: value, value)
    }
    val selected = node.strings("value")
    val multi = node.string("variant") == "multipleSelection"
    var touched by rememberSaveable(node.key) { mutableStateOf(false) }
    var filter by rememberSaveable(node.key + "/filter") { mutableStateOf("") }
    fun pick(value: String) {
        touched = true
        val next = if (multi) (if (value in selected) selected - value else selected + value) else listOf(value)
        ctx.host.setStrings(node, "value", next)
    }
    val shown = if (filter.isBlank()) options else options.filter { it.label.contains(filter.trim(), ignoreCase = true) }
    Column(modifier.padding(LeafMargin)) {
        node.string("label")?.let { Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp)) }
        if (node.boolOr("filterable", false)) {
            OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Filter") })
        }
        if (node.string("displayStyle") == "chips") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.forEach { c -> FilterChip(selected = c.value in selected, onClick = { pick(c.value) }, label = { Text(c.label) }) }
            }
        } else {
            Column(Modifier.selectableGroup()) {
                shown.forEach { c ->
                    val on = c.value in selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).then(
                            if (multi) Modifier.toggleable(on, role = Role.Checkbox) { pick(c.value) }
                            else Modifier.selectable(on, role = Role.RadioButton) { pick(c.value) },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (multi) Checkbox(checked = on, onCheckedChange = null) else RadioButton(selected = on, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(c.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        if (touched) node.firstError?.let { ErrorLine(it) }
    }
}

@Composable
private fun SliderNode(node: RenderNode, ctx: A2uiContext, modifier: Modifier) {
    val min = node.numberOr("min", 0.0)
    val max = node.numberOr("max", 1.0).let { if (it > min) it else min + 1.0 }
    val value = node.numberOr("value", min).coerceIn(min, max)
    val label = node.string("label")
    Column(modifier.padding(LeafMargin)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label.orEmpty(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(formatNumber(value), style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { ctx.host.setNumber(node, "value", (it.toDouble() * 1e6).roundToLong() / 1e6) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = node.number("steps")?.toInt()?.let { (it - 1).coerceAtLeast(0) } ?: 0,
            modifier = Modifier.semantics { if (label != null) contentDescription = label },
        )
        node.firstError?.let { ErrorLine(it) }
    }
}

private fun formatNumber(v: Double): String = if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else "%.2f".format(v)

/** Date and/or time: a field that opens the Material date picker, then the time picker. Writes ISO 8601. */
@Composable
private fun DateTimeNode(node: RenderNode, ctx: A2uiContext, modifier: Modifier) {
    val enableDate = node.boolOr("enableDate", false)
    val enableTime = node.boolOr("enableTime", false)
    val useDate = enableDate || !enableTime
    val useTime = enableTime
    val current = A2uiDateTime.parse(node.string("value"))
    var step by remember(node.key) { mutableIntStateOf(0) }
    var pickedDate by remember(node.key) { mutableStateOf<LocalDate?>(null) }
    fun write(date: LocalDate?, time: LocalTime?) {
        ctx.host.setString(node, "value", A2uiDateTime.format(if (useDate) date ?: current?.date ?: LocalDate.now() else null, if (useTime) time else null))
    }
    val source = remember { MutableInteractionSource() }
    LaunchedEffect(source) {
        source.interactions.collect { if (it is PressInteraction.Release) step = if (useDate) 1 else 2 }
    }
    val label = node.string("label") ?: if (useTime && !useDate) "Time" else "Date"
    OutlinedTextField(
        value = A2uiDateTime.display(current),
        onValueChange = {},
        readOnly = true,
        modifier = modifier.padding(LeafMargin).semantics { role = Role.Button },
        label = { Text(label) },
        trailingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
        isError = node.firstError != null && current != null,
        supportingText = node.firstError?.takeIf { current != null }?.let { { Text(it) } },
        singleLine = true,
        interactionSource = source,
    )
    if (step == 1) {
        val min = A2uiDateTime.parse(node.string("min"))?.date
        val max = A2uiDateTime.parse(node.string("max"))?.date
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (current?.date ?: LocalDate.now()).let(A2uiDateTime::utcMillis),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = A2uiDateTime.fromUtcMillis(utcTimeMillis)
                    return (min == null || !d.isBefore(min)) && (max == null || !d.isAfter(max))
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { step = 0 },
            confirmButton = {
                TextButton(onClick = {
                    val d = state.selectedDateMillis?.let(A2uiDateTime::fromUtcMillis)
                    if (useTime) {
                        pickedDate = d
                        step = 2
                    } else {
                        if (d != null) write(d, null)
                        step = 0
                    }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
    if (step == 2) {
        val initial = current?.time ?: LocalTime.now()
        val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute)
        AlertDialog(
            onDismissRequest = { step = 0 },
            confirmButton = {
                TextButton(onClick = {
                    write(pickedDate, LocalTime.of(state.hour, state.minute))
                    step = 0
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }
}

@Composable
private fun ErrorLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
}

// ---- Text helpers ----

private val MarkdownMarks = Regex("""(^|\n)\s{0,3}(#{1,6}\s|[-*+]\s|\d+\.\s|>\s|```)|\*\*|__|`|\[[^\]]+]\(|\|""")

internal fun looksLikeMarkdown(text: String): Boolean = MarkdownMarks.containsMatchIn(text)

/** Plain text for places markdown cannot go (button labels, captions): markers dropped. */
internal fun stripMarkdown(text: String): String = text
    .replace(Regex("""(?m)^\s{0,3}#{1,6}\s+"""), "")
    .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
    .replace(Regex("""\*\*|__|`|~~"""), "")
    .replace(Regex("""(?<![\w*])\*(?!\s)([^*]+)(?<!\s)\*"""), "$1")
    .trim()
