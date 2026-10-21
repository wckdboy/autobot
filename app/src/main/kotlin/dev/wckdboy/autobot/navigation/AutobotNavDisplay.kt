package dev.wckdboy.autobot.navigation

import dev.wckdboy.autobot.BuildConfig

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.scene.Scene
import dev.wckdboy.autobot.MainActivity
import dev.wckdboy.autobot.core.designsystem.component.EmptyContent
import dev.wckdboy.autobot.core.designsystem.icon.AutobotIcons
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.motion.inkScreenTransition
import dev.wckdboy.autobot.core.designsystem.motion.misregisterEnter
import dev.wckdboy.autobot.core.designsystem.theme.Motion
import dev.wckdboy.autobot.entry.EntryActions
import dev.wckdboy.autobot.feature.chat.conversation.ChatRoute
import dev.wckdboy.autobot.feature.chat.list.ChatListRoute
import dev.wckdboy.autobot.feature.home.RemoteRoute
import dev.wckdboy.autobot.feature.home.node.NodeAgentRoute
import dev.wckdboy.autobot.feature.home.node.NodeBotRoute
import dev.wckdboy.autobot.feature.home.RunRoute
import dev.wckdboy.autobot.feature.imagine.backends.BackendsRoute
import dev.wckdboy.autobot.feature.imagine.gallery.GalleryRoute
import dev.wckdboy.autobot.feature.imagine.generate.GalleryHandoff
import dev.wckdboy.autobot.feature.imagine.generate.ImagineRoute
import dev.wckdboy.autobot.feature.imagine.viewer.ViewerAction
import dev.wckdboy.autobot.feature.models.AccountsRoute
import dev.wckdboy.autobot.feature.models.ComputeRoute
import dev.wckdboy.autobot.feature.models.ModelsRoute
import dev.wckdboy.autobot.feature.settings.SettingsRoute
import dev.wckdboy.autobot.feature.settings.about.AboutInfo
import dev.wckdboy.autobot.feature.settings.about.AboutRoute
import dev.wckdboy.autobot.feature.settings.aboutRowTitle
import dev.wckdboy.autobot.feature.settings.mcp.McpServersRoute
import dev.wckdboy.autobot.feature.settings.memory.MemoryRoute
import dev.wckdboy.autobot.feature.settings.recipes.RecipesRoute
import dev.wckdboy.autobot.feature.settings.usage.UsageRoute
import dev.wckdboy.autobot.feature.settings.vault.VaultRoute
import dev.wckdboy.autobot.feature.settings.privacy.PrivacyCenterRoute
import dev.wckdboy.autobot.feature.settings.providers.ProvidersRoute
import dev.wckdboy.autobot.feature.settings.providers.ProvidersStart
import dev.wckdboy.autobot.feature.studio.ui.StudioRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val root: TopLevel, val icon: () -> ImageVector) {
    RUN("Run", Run, { Icons.Filled.Home }),
    MODELS("Models", Models, { AutobotIcons.Chip }),
    REMOTE("Remote", Remote, { AutobotIcons.Terminal }),
    SETTINGS("Settings", Settings, { Icons.Filled.Settings }),
}

private fun NavKey.tab(): Tab? = Tab.entries.firstOrNull { it.root == this }

/**
 * Wraps each entry so one that enters while a push is in flight ([pushing], judged once when it
 * first composes) — i.e. the pushed screen — settles into register as it fades in. A screen
 * revealed by back (button or predictive gesture) or a tab switch never does.
 */
@Composable
private fun inkEnterDecorator(pushing: () -> Boolean): NavEntryDecorator<NavKey> {
    val currentPushing by rememberUpdatedState(pushing)
    return remember {
        NavEntryDecorator(decorate = { entry ->
            val animate = remember { currentPushing() }
            Box(Modifier.fillMaxSize().misregisterEnter(animate)) { entry.Content() }
        })
    }
}

/**
 * Navigation 3 host, adaptive: one back stack whose first entry is a tab root. The tabs are a
 * bottom bar on a phone and a rail (or a drawer) on a tablet, a foldable or a desktop window,
 * where they stay beside the screens pushed on top; on a phone a pushed screen takes the whole
 * window. On a wide window a list and what it opens sit side by side (Run or the chat list and
 * a chat, Settings and its pages, Remote and a node); on a phone they take turns.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AutobotNavDisplay(backStack: NavBackStack<NavKey>) {
    // A forward push is in flight (cleared after its transition): the screen composed now is the
    // pushed one and enters with the misregistration plate.
    // Only read lazily (by the entry decorator) and cleared from a coroutine, never read here:
    // a push must not cost the host an extra recomposition when its transition ends.
    var pushing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val settle = remember { arrayOfNulls<Job>(1) }

    // A list and what it opens side by side: opening another item from the list replaces the
    // detail beside it, so back returns to the list instead of walking every item opened.
    val adaptive = currentWindowAdaptiveInfo()
    val twoPane = calculatePaneScaffoldDirective(adaptive).maxHorizontalPartitions > 1

    /** Pushes [key]; opened from the list [from], it replaces the detail beside that list on a wide window. */
    fun navigate(key: NavKey, from: NavKey? = null) {
        if (backStack.lastOrNull() != key) {
            pushing = true
            settle[0]?.cancel()
            settle[0] = scope.launch {
                delay(Motion.transitionMillis.toLong())
                pushing = false
            }
            if (from != null) backStack.openDetail(from, key, twoPane) else backStack.add(key)
        }
    }

    fun open(list: NavKey, key: NavKey) = navigate(key, from = list)

    fun back() {
        pushing = false
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    fun switchTo(root: TopLevel) {
        pushing = false
        backStack.clear()
        backStack.add(root)
    }

    val reduced = LocalInkMotion.current.reduced
    val context = LocalContext.current
    // The tab is the stack's root; a screen pushed on top keeps it.
    val tab = backStack.firstOrNull()?.tab()
    val suite = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptive)
    // A phone's bottom bar gives the whole window to a pushed screen (and to the Studio, which has
    // its own bar); a rail or a drawer stays.
    val layout = when {
        tab == null -> NavigationSuiteType.None
        suite == NavigationSuiteType.NavigationBar && backStack.size > 1 -> NavigationSuiteType.None
        else -> suite
    }
    val listDetail = rememberAutobotListDetail()
    // The pane beside a list before anything is opened in it.
    val nothingOpen = remember {
        ListDetailSceneStrategy.listPane(
            detailPlaceholder = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyContent("Nothing open") }
            },
        )
    }
    val detail = remember { ListDetailSceneStrategy.detailPane() }
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Tab.entries.forEach { item ->
                item(
                    selected = item == tab,
                    onClick = { if (item != tab || backStack.size > 1) switchTo(item.root) },
                    icon = { Icon(item.icon(), contentDescription = null) },
                    label = { Text(item.label) },
                )
            }
        },
        layoutType = layout,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
            NavDisplay(
                backStack = backStack,
                modifier = Modifier.fillMaxSize(),
                onBack = { back() },
                sceneStrategies = listOf(listDetail),
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                    inkEnterDecorator { pushing },
                ),
                transitionSpec = inkScreenTransition<Scene<NavKey>>(reduced),
                popTransitionSpec = inkScreenTransition<Scene<NavKey>>(reduced),
                // predictivePopTransitionSpec stays the platform default so the back gesture keeps
                // its interactive, seekable preview.
                entryProvider = entryProvider {
                    entry<Run>(metadata = nothingOpen) {
                        RunRoute(
                            onOpenSession = { open(Run, Chat(it)) },
                            onOpenImagine = { navigate(Imagine()) },
                            onOpenStudio = { navigate(Studio()) },
                            onOpenAllRuns = { open(Run, ChatList) },
                            onOpenGalleryItem = { navigate(Gallery(it)) },
                            onOpenModels = { switchTo(Models) },
                            onOpenNodeAgent = { id, sheet, session -> open(Run, NodeAgent(id, sheet, session)) },
                        )
                    }
                    entry<Models>(metadata = nothingOpen) {
                        ModelsRoute(
                            onOpenAccounts = { open(Models, Accounts) },
                            onOpenCompute = { open(Models, Compute()) },
                            onOpenSession = { open(Models, Chat(it)) },
                            onOpenImagine = { navigate(Imagine(modelId = it)) },
                            onOpenGallery = { navigate(Gallery()) },
                            onOpenNetwork = { open(Models, PrivacyCenter) },
                            // The same new voice chat the voice tile and widget open.
                            onOpenVoice = { context.startActivity(Intent(context, MainActivity::class.java).setAction(EntryActions.VOICE)) },
                        )
                    }
                    entry<Remote>(metadata = nothingOpen) {
                        RemoteRoute(
                            onOpenProviders = { open(Remote, Providers()) },
                            onOpenBackends = { open(Remote, ImageBackends) },
                            onOpenAccounts = { open(Remote, Accounts) },
                            onOpenPrivacy = { open(Remote, PrivacyCenter) },
                            onOpenNodeAgent = { open(Remote, NodeAgent(it)) },
                            onPairNode = { open(Remote, Providers(Providers.PAIR)) },
                            onOpenBot = { id, bot -> open(Remote, NodeBot(id, bot)) },
                        )
                    }
                    entry<NodeAgent>(metadata = detail) { key -> NodeAgentRoute(providerId = key.providerId, onBack = ::back, open = key.open, session = key.session, bot = key.bot) }
                    entry<NodeBot>(metadata = detail) { key ->
                        NodeBotRoute(key.providerId, key.name, onBack = ::back, onOpenSession = { session, bot -> navigate(NodeAgent(key.providerId, session = session, bot = bot)) })
                    }
                    entry<Settings>(metadata = nothingOpen) {
                        SettingsRoute(
                            onBack = null,
                            onOpenPrivacyCenter = { open(Settings, PrivacyCenter) },
                            onOpenMemory = { open(Settings, Memory) },
                            onOpenUsage = { open(Settings, Usage) },
                            onOpenRecipes = { open(Settings, Recipes) },
                            onOpenVault = { open(Settings, Vault) },
                            onOpenMcpServers = { open(Settings, McpServers) },
                            onOpenAbout = { open(Settings, About) },
                            about = aboutRowTitle(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong()),
                        )
                    }
                    entry<ChatList>(metadata = nothingOpen) {
                        ChatListRoute(
                            onBack = ::back,
                            onOpenChat = { open(ChatList, Chat(it)) },
                            onOpenProviders = { open(ChatList, Providers()) },
                        )
                    }
                    entry<Chat>(metadata = detail) { key ->
                        ChatRoute(
                            conversationId = key.conversationId,
                            onBack = ::back,
                            onOpenPrivacyCenter = { navigate(PrivacyCenter) },
                            onOpenProviders = { navigate(Providers()) },
                            onOpenGallery = { navigate(Gallery()) },
                            onOpenImage = { id, batch -> navigate(Gallery(openId = id, scope = batch)) },
                            onOpenUsage = { navigate(Usage) },
                        )
                    }
                    entry<Imagine> { key ->
                        ImagineRoute(
                            onBack = ::back,
                            onOpenGallery = { navigate(Gallery()) },
                            onOpenBackends = { navigate(ImageBackends) },
                            onOpenModels = { switchTo(Models) },
                            onOpenStudio = { navigate(Studio()) },
                            handoff = key.handoffId?.let { id ->
                                val action = key.action?.let { name -> ViewerAction.entries.firstOrNull { it.name == name } }
                                if (action != null) GalleryHandoff(id, action = action) else GalleryHandoff(id, key.asInit)
                            },
                            modelId = key.modelId,
                            prompt = key.prompt,
                        )
                    

                    }
                    // Studio mode: its own bar replaces the app's (no tab root, so the app bar hides).
                    entry<Studio> { key ->
                        StudioRoute(
                            onBack = ::back,
                            onOpenModels = { switchTo(Models) },
                            onOpenImageBackends = { navigate(ImageBackends) },
                            initialRoom = key.room,
                        )
                    }
                    entry<Gallery> { key ->
                        GalleryRoute(
                            onBack = ::back,
                            openId = key.openId,
                            scope = key.scope,
                            onReuse = { id, action -> navigate(Imagine(id, asInit = action == ViewerAction.IMG2IMG, action = action.name)) },
                        )
                    }
                    entry<Providers>(metadata = detail) { key -> ProvidersRoute(onBack = ::back, start = when (key.start) { Providers.PAIR -> ProvidersStart.PAIR; Providers.ADD -> ProvidersStart.ADD; else -> null }) }
                    entry<McpServers>(metadata = detail) { McpServersRoute(onBack = ::back) }
                    entry<PrivacyCenter>(metadata = detail) { PrivacyCenterRoute(onBack = ::back) }
                    entry<ImageBackends>(metadata = detail) { BackendsRoute(onBack = ::back) }
                    entry<Accounts>(metadata = detail) { AccountsRoute(onBack = ::back) }
                    entry<Compute>(metadata = detail) { key -> ComputeRoute(onBack = ::back, openReport = key.report) }
                    entry<About>(metadata = detail) {
                        AboutRoute(
                            info = AboutInfo(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), BuildConfig.BUILD_DATE, BuildConfig.DEBUG),
                            onBack = ::back,
                            onOpenDiagnostics = { navigate(Compute(report = true)) },
                        )
                    }
                    entry<Memory>(metadata = detail) { MemoryRoute(onBack = ::back) }
                    entry<Usage>(metadata = detail) { UsageRoute(onBack = ::back) }
                    entry<Recipes>(metadata = detail) { RecipesRoute(onBack = ::back, onOpenChat = { navigate(Chat(it)) }) }
                    entry<Vault>(metadata = detail) { VaultRoute(onBack = ::back, onOpenModels = { switchTo(Models) }) }
                },
            )
    }
}

/**
 * The list-detail scenes. Back pops one entry ([BackNavigationBehavior.PopLatest]): the default
 * (pop until the panes change) found nothing to pop on a wide window, where closing a detail
 * leaves the "Nothing open" placeholder in the same two panes, so back left the app instead.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun rememberAutobotListDetail(): ListDetailSceneStrategy<NavKey> =
    rememberListDetailSceneStrategy(backNavigationBehavior = BackNavigationBehavior.PopLatest)

/**
 * Opens [key] from the list [list]. With two panes the list stays and [key] takes the detail
 * pane: what was open beside the list is closed first, so back returns to the list. With one
 * pane [key] is pushed as usual.
 */
internal fun MutableList<NavKey>.openDetail(list: NavKey, key: NavKey, twoPane: Boolean) {
    if (lastOrNull() == key) return
    if (twoPane) {
        val at = lastIndexOf(list)
        if (at >= 0) while (size > at + 1) removeAt(lastIndex)
    }
    add(key)
}
