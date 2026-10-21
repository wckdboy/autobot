package dev.wckdboy.autobot.feature.studio.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import dev.wckdboy.autobot.core.designsystem.component.Caption
import dev.wckdboy.autobot.core.designsystem.component.Choices
import dev.wckdboy.autobot.core.designsystem.component.EmptyContent
import dev.wckdboy.autobot.core.designsystem.component.Field
import dev.wckdboy.autobot.core.designsystem.component.SheetLabel
import dev.wckdboy.autobot.core.designsystem.component.TonalLabel
import dev.wckdboy.autobot.core.designsystem.component.labelCase
import dev.wckdboy.autobot.core.designsystem.component.isLargeText
import dev.wckdboy.autobot.feature.studio.ui.ActionChip
import dev.wckdboy.autobot.feature.studio.ui.PickChip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.motion.inkStagger
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.feature.imagine.generate.ImageCodec
import dev.wckdboy.autobot.feature.studio.engine.StudioRecipes
import dev.wckdboy.autobot.feature.studio.ui.AssetThumb
import dev.wckdboy.autobot.feature.studio.ui.ChipRow
import dev.wckdboy.autobot.feature.studio.ui.ErrorLine
import dev.wckdboy.autobot.feature.studio.ui.LocalAssetLoader
import dev.wckdboy.autobot.feature.studio.ui.ShellActions
import dev.wckdboy.autobot.feature.studio.ui.StudioShellState
import dev.wckdboy.autobot.feature.studio.ui.Transport
import dev.wckdboy.autobot.feature.studio.ui.VideoSurface
import dev.wckdboy.autobot.feature.studio.ui.Waveform
import dev.wckdboy.autobot.feature.studio.ui.duration
import dev.wckdboy.autobot.feature.studio.ui.rememberBytePlayer
import dev.wckdboy.autobot.shared.studio.AssetType
import dev.wckdboy.autobot.shared.studio.StudioAsset
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the library hands to the rooms. */
class LibraryHandoffs(
    val useAsInput: (StudioAsset) -> Unit,
    val editRecipe: (StudioAsset) -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryRoom(
    state: LibraryState,
    shell: StudioShellState,
    actions: LibraryActions,
    shellActions: ShellActions,
    handoffs: LibraryHandoffs,
    openAsset: String?,
    onOpenAsset: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var newProject by rememberSaveable { mutableStateOf(false) }
    val compare = state.compare
    if (compare != null) {
        val a = state.assets.firstOrNull { it.id == compare.first }
        val b = state.assets.firstOrNull { it.id == compare.second }
        if (a != null && b != null) {
            Compare(a, b, actions::closeCompare, modifier)
            return
        }
    }
    LazyVerticalGrid(
        columns = if (state.view == LibraryView.GRID) GridCells.Adaptive(104.dp) else GridCells.Fixed(1),
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.message?.let { ErrorLine(it, actions::dismiss) }
                SheetLabel("projects")
                ChipRow(shell.projects, shell.project, { shellActions.selectProject(it.id) }, { it.name }, trailing = {
                    ActionChip("+ new", onClick = { newProject = true })
                })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    AssetType.entries.forEach { t ->
                        PickChip(labelCase(t.name.lowercase()), selected = t in state.types, onClick = { actions.toggleType(t) })
                    }
                    PickChip("★", selected = state.favoritesOnly, onClick = { actions.setFavoritesOnly(!state.favoritesOnly) }, Modifier.semantics { contentDescription = "Favourites only" })
                    ActionChip(if (state.allProjects) "all" else "project", onClick = { actions.setAllProjects(!state.allProjects) })
                }
                Choices(LibraryView.entries, state.view, actions::setView, Modifier.fillMaxWidth()) { labelCase(it.name.lowercase()) }
                Caption("${state.assets.size} asset${if (state.assets.size == 1) "" else "s"}" + (state.comparePick?.let { " · comparing: pick the second" } ?: ""))
                if (state.assets.isEmpty()) EmptyContent("Nothing here yet.")
            }
        }
        items(state.assets, key = { it.id }) { asset ->
            val picked = asset.id == state.comparePick
            if (state.view == LibraryView.GRID) {
                AssetThumb(
                    asset,
                    Modifier
                        .aspectRatio(1f)
                        .then(if (picked) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small) else Modifier)
                        .clickable { if (state.comparePick != null) actions.compareWith(asset) else onOpenAsset(asset.id) }
                        .semantics { contentDescription = describe(asset) },
                )
            } else {
                // At a large font the size joins the summary and the title wraps in full.
                val large = isLargeText()
                val size = if (asset.type == AssetType.IMAGE) "${asset.width}×${asset.height}" else duration(asset.durationMs)
                ListItem(
                    headlineContent = {
                        Text(StudioRecipes.title(asset.recipe).ifBlank { asset.label ?: asset.type.name.lowercase() }, maxLines = if (large) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = { Text(StudioRecipes.summary(asset.recipe) + if (large) " · $size" else "", maxLines = if (large) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { AssetThumb(asset, Modifier.size(if (large) 40.dp else 56.dp)) },
                    trailingContent = if (large) null else { { Text(size, style = MaterialTheme.typography.labelMedium) } },
                    colors = ListItemDefaults.colors(containerColor = if (picked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { if (state.comparePick != null) actions.compareWith(asset) else onOpenAsset(asset.id) },
                )
            }
        }
    }
    openAsset?.let { id -> state.assets.firstOrNull { it.id == id } ?: shell.assets.firstOrNull { it.id == id } }?.let { asset ->
        AssetSheet(asset, actions, handoffs, onDismiss = { onOpenAsset(null) })
    }
    if (newProject) NewProjectDialog(onCreate = { shellActions.createProject(it); newProject = false }, onDismiss = { newProject = false })
}

private fun describe(a: StudioAsset): String =
    "${a.type.name.lowercase()}: ${StudioRecipes.title(a.recipe).ifBlank { a.label ?: "" }}" + if (a.favorite) ", favourite" else ""

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AssetSheet(asset: StudioAsset, actions: LibraryActions, handoffs: LibraryHandoffs, onDismiss: () -> Unit) {
    var confirmExport by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Preview(asset)
            Text(StudioRecipes.title(asset.recipe).ifBlank { asset.label ?: asset.type.name.lowercase() }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.inkStagger(0))
            Caption(
                listOfNotNull(
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(asset.createdAt)),
                    asset.label,
                    if (asset.width > 0) "${asset.width}×${asset.height}" else null,
                    if (asset.durationMs > 0) duration(asset.durationMs) else null,
                    "${asset.sizeBytes / 1024} KB",
                ).joinToString(" · "),
            )
            Recipe(asset)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PickChip(if (asset.favorite) "★ Favourite" else "☆ Favourite", selected = asset.favorite, onClick = { actions.toggleFavorite(asset) })
                ActionChip("compare", onClick = { actions.compareWith(asset); onDismiss() })
                ActionChip("export", onClick = { confirmExport = true })
                ActionChip("delete", onClick = { confirmDelete = true })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (asset.type == AssetType.IMAGE) OutlinedButton(onClick = { handoffs.useAsInput(asset); onDismiss() }, Modifier.weight(1f)) { Text("Use as input") }
                if (LibraryViewModel.editable(asset)) OutlinedButton(onClick = { handoffs.editRecipe(asset); onDismiss() }, Modifier.weight(1f)) { Text("Reuse recipe") }
            }
            if (asset.recipe.engine != StudioRecipes.IMPORT_ENGINE) Button(onClick = { actions.runAgain(asset); onDismiss() }, Modifier.fillMaxWidth()) { Text("Run the recipe again") }
        }
    }
    if (confirmExport) {
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text("Export unencrypted copy?") },
            text = { Text("A decrypted copy goes to ${dev.wckdboy.autobot.feature.studio.media.StudioExport.destination(asset)} in shared storage, where other apps can read it. The Studio keeps its encrypted original.") },
            confirmButton = { TextButton(onClick = { confirmExport = false; actions.export(asset) }) { Text("Export") } },
            dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this asset?") },
            text = { Text("It is removed from the library and its encrypted file is deleted.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; actions.delete(asset); onDismiss() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** The full asset: image, player with waveform, or video. */
@Composable
private fun Preview(asset: StudioAsset) {
    val loader = LocalAssetLoader.current
    val bytes by produceState<ByteArray?>(null, asset.id) { value = loader.bytes(asset.id) }
    when (asset.type) {
        AssetType.IMAGE -> {
            val image by produceState<ImageBitmap?>(null, bytes) {
                value = bytes?.let { b -> withContext(Dispatchers.Default) { ImageCodec.decode(b, 1536)?.let { it.asImageBitmap() } } }
            }
            val img = image
            if (img != null) {
                Image(img, contentDescription = describe(asset), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().aspectRatio((img.width.toFloat() / img.height).coerceIn(0.5f, 2f)))
            } else {
                AssetThumb(asset, Modifier.fillMaxWidth().aspectRatio(1f))
            }
        }
        AssetType.AUDIO -> {
            val player = rememberBytePlayer()
            val peaks by produceState<FloatArray?>(null, asset.id) { value = loader.peaks(asset.id) }
            LaunchedEffect(bytes) { bytes?.let(player::load) }
            Waveform(peaks, Modifier.fillMaxWidth().height(96.dp), progress = if (player.durationMs > 0) player.positionMs.toFloat() / player.durationMs else -1f)
            Transport(player)
        }
        AssetType.VIDEO -> {
            val player = rememberBytePlayer()
            val data = bytes
            if (data != null) {
                VideoSurface(data, asset.mimeType, player, Modifier.fillMaxWidth())
                if (!asset.mimeType.startsWith("image/")) Transport(player)
            } else {
                AssetThumb(asset, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            }
        }
    }
}

/** The recipe, parameter by parameter (what "reuse recipe" brings back). */
@Composable
private fun Recipe(asset: StudioAsset) {
    val r = asset.recipe
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SheetLabel("recipe · ${r.engine}" + (r.backendId?.let { " · on $it" } ?: " · this phone") + (r.workflowVersion?.let { " · workflow v$it" } ?: ""))
        r.model?.let { Text("model  $it", style = AutobotTheme.styles.codeBlock, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        r.seed?.let { Text("seed   $it", style = AutobotTheme.styles.codeBlock) }
        r.params.entries.sortedBy { it.key }.forEach { (k, v) ->
            Text("$k  ${v.display()}", style = AutobotTheme.styles.codeBlock, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        r.inputs.forEach { Text("input  ${it.role} ← ${it.assetId}", style = AutobotTheme.styles.codeBlock) }
    }
}

/** A/B: images share one frame with a draggable divider; audio switches between two players. */
@Composable
private fun Compare(a: StudioAsset, b: StudioAsset, onClose: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Compare A / B", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClose) { Text("Close") }
        }
        if (a.type == AssetType.IMAGE && b.type == AssetType.IMAGE) {
            ImageCompare(a, b)
        } else if (a.type == AssetType.AUDIO && b.type == AssetType.AUDIO) {
            AudioCompare(a, b)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssetThumb(a, Modifier.weight(1f).aspectRatio(1f))
                AssetThumb(b, Modifier.weight(1f).aspectRatio(1f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Caption("A · " + StudioRecipes.summary(a.recipe), Modifier.weight(1f))
            Caption("B · " + StudioRecipes.summary(b.recipe), Modifier.weight(1f))
        }
    }
}

@Composable
private fun ImageCompare(a: StudioAsset, b: StudioAsset) {
    val loader = LocalAssetLoader.current
    fun decode(bytes: ByteArray?): ImageBitmap? = bytes?.let { ImageCodec.decode(it, 1536)?.let { bmp -> bmp.asImageBitmap() } }
    val left by produceState<ImageBitmap?>(null, a.id) { value = withContext(Dispatchers.Default) { decode(loader.bytes(a.id)) } }
    val right by produceState<ImageBitmap?>(null, b.id) { value = withContext(Dispatchers.Default) { decode(loader.bytes(b.id)) } }
    var split by remember { mutableFloatStateOf(0.5f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val ratio = (a.width.toFloat() / a.height.coerceAtLeast(1)).takeIf { a.height > 0 }?.coerceIn(0.5f, 2f) ?: 1f
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .onSizeChanged { size = it }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> split = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f) } }
            .semantics {
                contentDescription = "A / B comparison, divider"
                progressBarRangeInfo = ProgressBarRangeInfo(split, 0f..1f)
                setProgress { v -> split = v.coerceIn(0f, 1f); true }
            },
    ) {
        right?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        val px = with(density) { (size.width * split).toDp() }
        Box(Modifier.width(px).fillMaxHeight().clipToBounds()) {
            left?.let { Image(it, null, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, modifier = Modifier.width(with(density) { size.width.toDp() }).fillMaxHeight()) }
        }
        Box(Modifier.offset(x = px - 1.dp).width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        TonalLabel("A", Modifier.align(Alignment.TopStart).padding(6.dp))
        TonalLabel("B", Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}

/** Two players at the same position; A / B switches which one is heard. */
@Composable
private fun AudioCompare(a: StudioAsset, b: StudioAsset) {
    val loader = LocalAssetLoader.current
    val pa = rememberBytePlayer()
    val pb = rememberBytePlayer()
    val ba by produceState<ByteArray?>(null, a.id) { value = loader.bytes(a.id) }
    val bb by produceState<ByteArray?>(null, b.id) { value = loader.bytes(b.id) }
    LaunchedEffect(ba) { ba?.let(pa::load) }
    LaunchedEffect(bb) { bb?.let(pb::load) }
    var useB by rememberSaveable { mutableStateOf(false) }
    val active = if (useB) pb else pa
    val peaksA by produceState<FloatArray?>(null, a.id) { value = loader.peaks(a.id) }
    val peaksB by produceState<FloatArray?>(null, b.id) { value = loader.peaks(b.id) }
    Waveform(peaksA, Modifier.fillMaxWidth().height(64.dp).clickable { useB = false }, progress = if (!useB && pa.durationMs > 0) pa.positionMs.toFloat() / pa.durationMs else -1f)
    Waveform(peaksB, Modifier.fillMaxWidth().height(64.dp).clickable { useB = true }, progress = if (useB && pb.durationMs > 0) pb.positionMs.toFloat() / pb.durationMs else -1f)
    Choices(listOf(false, true), useB, { b2 ->
        if (b2 != useB) {
            val from = if (useB) pb else pa
            val to = if (b2) pb else pa
            val wasPlaying = from.playing
            to.seek(from.positionMs)
            from.pause()
            if (wasPlaying) to.play()
            useB = b2
        }
    }, Modifier.fillMaxWidth()) { if (it) "B" else "A" }
    Transport(active)
}

@Composable
private fun NewProjectDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = { Field(name, { name = it }, Modifier.fillMaxWidth(), label = "Name", singleLine = true) },
        confirmButton = { TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
