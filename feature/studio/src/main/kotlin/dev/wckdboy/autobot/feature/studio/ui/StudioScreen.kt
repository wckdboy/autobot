package dev.wckdboy.autobot.feature.studio.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.TitleWithDot
import dev.wckdboy.autobot.core.designsystem.component.isLargeText
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wckdboy.autobot.core.designsystem.theme.DarkroomTheme
import dev.wckdboy.autobot.core.designsystem.theme.PaperTheme
import dev.wckdboy.autobot.feature.studio.ui.audio.AudioActions
import dev.wckdboy.autobot.feature.studio.ui.audio.AudioRoom
import dev.wckdboy.autobot.feature.studio.ui.audio.AudioRoomState
import dev.wckdboy.autobot.feature.studio.ui.audio.AudioRoomViewModel
import dev.wckdboy.autobot.feature.studio.ui.image.ImageActions
import dev.wckdboy.autobot.feature.studio.ui.image.ImageRoom
import dev.wckdboy.autobot.feature.studio.ui.image.ImageRoomState
import dev.wckdboy.autobot.feature.studio.ui.image.ImageRoomViewModel
import dev.wckdboy.autobot.feature.studio.ui.library.LibraryActions
import dev.wckdboy.autobot.feature.studio.ui.library.LibraryHandoffs
import dev.wckdboy.autobot.feature.studio.ui.library.LibraryRoom
import dev.wckdboy.autobot.feature.studio.ui.library.LibraryState
import dev.wckdboy.autobot.feature.studio.ui.library.LibraryViewModel
import dev.wckdboy.autobot.feature.studio.ui.video.VideoActions
import dev.wckdboy.autobot.feature.studio.ui.video.VideoRoom
import dev.wckdboy.autobot.feature.studio.ui.video.VideoRoomState
import dev.wckdboy.autobot.feature.studio.ui.video.VideoRoomViewModel
import dev.wckdboy.autobot.feature.studio.ui.workflows.WorkflowsActions
import dev.wckdboy.autobot.feature.studio.ui.workflows.WorkflowsRoom
import dev.wckdboy.autobot.feature.studio.ui.workflows.WorkflowsState
import dev.wckdboy.autobot.feature.studio.ui.workflows.WorkflowsViewModel
import dev.wckdboy.autobot.feature.studio.engine.AuthUpdate
import dev.wckdboy.autobot.feature.studio.engine.ComfyBackendConfig
import dev.wckdboy.autobot.shared.studio.AssetType
import dev.wckdboy.autobot.shared.studio.StudioAsset

/** The Studio's rooms, in bar order. */
enum class StudioRoom(val label: String) { IMAGE("Image"), AUDIO("Audio"), VIDEO("Video"), WORKFLOWS("Workflows"), LIBRARY("Library") }

/**
 * Studio mode: a top app bar, Material tabs for its rooms (Image · Audio · Video · Workflows ·
 * Library) in place of the app's bar, the queue strip at the bottom, the brand's dark scheme for
 * image and video and its light scheme for the library. Back leaves the Studio; jobs keep running
 * (the queue lives in the app scope).
 */
@Composable
fun StudioRoute(
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenImageBackends: () -> Unit,
    initialRoom: String? = null,
    shell: StudioViewModel = hiltViewModel(),
    image: ImageRoomViewModel = hiltViewModel(),
    audio: AudioRoomViewModel = hiltViewModel(),
    video: VideoRoomViewModel = hiltViewModel(),
    workflows: WorkflowsViewModel = hiltViewModel(),
    library: LibraryViewModel = hiltViewModel(),
) {
    val shellState by shell.state.collectAsStateWithLifecycle()
    val imageState by image.state.collectAsStateWithLifecycle()
    val audioState by audio.state.collectAsStateWithLifecycle()
    val videoState by video.state.collectAsStateWithLifecycle()
    val workflowsState by workflows.state.collectAsStateWithLifecycle()
    val libraryState by library.state.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalAssetLoader provides shell.loader) {
        StudioScreen(
            initialRoom = StudioRoom.entries.firstOrNull { it.name.equals(initialRoom, ignoreCase = true) } ?: StudioRoom.IMAGE,
            shell = shellState,
            shellActions = shell,
            image = imageState,
            imageActions = image,
            audio = audioState,
            audioActions = audio,
            video = videoState,
            videoActions = video,
            workflows = workflowsState,
            workflowsActions = workflows,
            newBackend = workflows::newBackend,
            auth = workflows::auth,
            library = libraryState,
            libraryActions = library,
            onLoadRecipe = image::loadRecipe,
            onBack = onBack,
            onOpenModels = onOpenModels,
            onOpenImageBackends = onOpenImageBackends,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(
    initialRoom: StudioRoom,
    shell: StudioShellState,
    shellActions: ShellActions,
    image: ImageRoomState,
    imageActions: ImageActions,
    audio: AudioRoomState,
    audioActions: AudioActions,
    video: VideoRoomState,
    videoActions: VideoActions,
    workflows: WorkflowsState,
    workflowsActions: WorkflowsActions,
    newBackend: (String) -> ComfyBackendConfig,
    auth: (String) -> AuthUpdate,
    library: LibraryState,
    libraryActions: LibraryActions,
    onLoadRecipe: (StudioAsset) -> Unit,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenImageBackends: () -> Unit,
) {
    var room by rememberSaveable { mutableStateOf(initialRoom) }
    var openAsset by rememberSaveable { mutableStateOf<String?>(null) }
    val openInLibrary: (StudioAsset) -> Unit = { openAsset = it.id; room = StudioRoom.LIBRARY }
    val theme: @Composable (@Composable () -> Unit) -> Unit = when (room) {
        StudioRoom.IMAGE, StudioRoom.VIDEO -> { c -> DarkroomTheme(c) }
        StudioRoom.LIBRARY -> { c -> PaperTheme(c) }
        else -> { c -> c() }
    }
    val large = isLargeText()
    theme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    // "Studio · Image": the room is in the title, as on iOS.
                    // At a large font the bar has room for "Studio" alone: the tabs say the room.
                    title = { TitleWithDot(if (large) "Studio" else "Studio · ${room.label}") },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Leave the Studio") } },
                    actions = {
                        if (!large) shell.project?.let { p ->
                            AssistChip(
                                onClick = { room = StudioRoom.LIBRARY },
                                label = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.padding(end = 8.dp).widthIn(max = 200.dp),
                            )
                        }
                    },
                )
                PrimaryScrollableTabRow(selectedTabIndex = room.ordinal, edgePadding = 8.dp) {
                    StudioRoom.entries.forEach { r ->
                        Tab(selected = r == room, onClick = { room = r }, text = { Text(r.label, maxLines = 1) })
                    }
                }
                Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars).imePadding()) {
                    when (room) {
                        StudioRoom.IMAGE -> ImageRoom(image, shell, imageActions, onOpenModels, onOpenImageBackends, openInLibrary)
                        StudioRoom.AUDIO -> AudioRoom(audio, shell, audioActions, onOpenModels, onOpenWorkflows = { room = StudioRoom.WORKFLOWS })
                        StudioRoom.VIDEO -> VideoRoom(video, shell, videoActions, onOpenWorkflows = { room = StudioRoom.WORKFLOWS })
                        StudioRoom.WORKFLOWS -> WorkflowsRoom(workflows, shell, workflowsActions, newBackend, auth, openInLibrary)
                        StudioRoom.LIBRARY -> LibraryRoom(
                            library, shell, libraryActions, shellActions,
                            LibraryHandoffs(
                                useAsInput = { a -> if (a.type == AssetType.IMAGE) { imageActions.useAsInput(a.id); room = StudioRoom.IMAGE } },
                                editRecipe = { a -> onLoadRecipe(a); room = StudioRoom.IMAGE },
                            ),
                            openAsset,
                            onOpenAsset = { openAsset = it },
                        )
                    }
                }
                QueueStrip(shell.jobs, shell.assets.associateBy { it.id }, shellActions)
                Spacer(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}
