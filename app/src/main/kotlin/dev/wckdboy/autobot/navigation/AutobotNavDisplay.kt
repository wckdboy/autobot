package dev.wckdboy.autobot.navigation

import dev.wckdboy.autobot.BuildConfig

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import dev.wckdboy.autobot.MainActivity
import dev.wckdboy.autobot.core.designsystem.component.AutobotNavBar
import dev.wckdboy.autobot.core.designsystem.component.NavItem
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.motion.inkScreenTransition
import dev.wckdboy.autobot.core.designsystem.motion.misregisterEnter
import dev.wckdboy.autobot.core.designsystem.theme.Motion
import dev.wckdboy.autobot.core.designsystem.theme.PaperTheme
import dev.wckdboy.autobot.entry.EntryActions
import dev.wckdboy.autobot.feature.chat.conversation.ChatRoute
import dev.wckdboy.autobot.feature.chat.list.ChatListRoute
import dev.wckdboy.autobot.feature.home.RemoteRoute
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
import dev.wckdboy.autobot.feature.studio.ui.StudioRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val root: TopLevel) {
    RUN("run", Run),
    MODELS("models", Models),
    REMOTE("remote", Remote),
    SETTINGS("settings", Settings),
}

private fun NavKey.tab(): Tab? = Tab.entries.firstOrNull { it.root == this }

private val TABS = Tab.entries.map { NavItem(it, it.label) }

/** Entry metadata: the screen is printed on paper (light), whatever the app theme. */
private const val PAPER = "ink.paper"

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
 * Navigation 3 host: one back stack whose first entry is a tab root. Detail screens push on
 * top and hide the bottom bar. The image studio is printed on paper (light) as in the mockups.
 */
@Composable
fun AutobotNavDisplay(backStack: NavBackStack<NavKey>) {
    // A forward push is in flight (cleared after its transition): the screen composed now is the
    // pushed one and enters with the misregistration plate.
    // Only read lazily (by the entry decorator) and cleared from a coroutine, never read here:
    // a push must not cost the host an extra recomposition when its transition ends.
    var pushing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val settle = remember { arrayOfNulls<Job>(1) }

    fun navigate(key: NavKey) {
        if (backStack.lastOrNull() != key) {
            pushing = true
            settle[0]?.cancel()
            settle[0] = scope.launch {
                delay(Motion.transitionMillis.toLong())
                pushing = false
            }
            backStack.add(key)
        }
    }

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
    val current = backStack.lastOrNull()
    val tab = current?.tab()
    val showBar = tab != null && backStack.size == 1
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            NavDisplay(
                backStack = backStack,
                // The bar owns the navigation-bar inset while it is shown.
                modifier = Modifier.weight(1f).then(if (showBar) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier),
                onBack = { back() },
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                    inkEnterDecorator { pushing },
                ),
                transitionSpec = inkScreenTransition(reduced),
                popTransitionSpec = inkScreenTransition(reduced),
                // predictivePopTransitionSpec stays the platform default so the back gesture keeps
                // its interactive, seekable preview.
                entryProvider = entryProvider {
                    entry<Run> {
                        RunRoute(
                            onOpenSession = { navigate(Chat(it)) },
                            onOpenImagine = { navigate(Imagine()) },
                            onOpenStudio = { navigate(Studio()) },
                            onOpenAllRuns = { navigate(ChatList) },
                            onOpenGalleryItem = { navigate(Gallery(it)) },
                            onOpenModels = { switchTo(Models) },
                        )
                    }
                    entry<Models> {
                        ModelsRoute(
                            onOpenAccounts = { navigate(Accounts) },
                            onOpenCompute = { navigate(Compute()) },
                            onOpenSession = { navigate(Chat(it)) },
                            onOpenImagine = { navigate(Imagine(modelId = it)) },
                            onOpenGallery = { navigate(Gallery()) },
                            onOpenNetwork = { navigate(PrivacyCenter) },
                            // The same new voice chat the voice tile and widget open.
                            onOpenVoice = { context.startActivity(Intent(context, MainActivity::class.java).setAction(EntryActions.VOICE)) },
                        )
                    }
                    entry<Remote> {
                        RemoteRoute(
                            onOpenProviders = { navigate(Providers) },
                            onOpenBackends = { navigate(ImageBackends) },
                            onOpenAccounts = { navigate(Accounts) },
                            onOpenPrivacy = { navigate(PrivacyCenter) },
                        )
                    }
                    entry<Settings> {
                        SettingsRoute(
                            onBack = null,
                            onOpenPrivacyCenter = { navigate(PrivacyCenter) },
                            onOpenMemory = { navigate(Memory) },
                            onOpenUsage = { navigate(Usage) },
                            onOpenRecipes = { navigate(Recipes) },
                            onOpenVault = { navigate(Vault) },
                            onOpenMcpServers = { navigate(McpServers) },
                            onOpenAbout = { navigate(About) },
                            about = aboutRowTitle(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong()),
                        )
                    }
                    entry<ChatList> {
                        ChatListRoute(
                            onBack = ::back,
                            onOpenChat = { navigate(Chat(it)) },
                            onOpenProviders = { navigate(Providers) },
                            onOpenPrivacyCenter = { navigate(PrivacyCenter) },
                        )
                    }
                    entry<Chat> { key ->
                        ChatRoute(
                            conversationId = key.conversationId,
                            onBack = ::back,
                            onOpenPrivacyCenter = { navigate(PrivacyCenter) },
                            onOpenProviders = { navigate(Providers) },
                            onOpenGallery = { navigate(Gallery()) },
                            onOpenImage = { id, batch -> navigate(Gallery(openId = id, scope = batch)) },
                            onOpenUsage = { navigate(Usage) },
                        )
                    }
                    entry<Imagine>(metadata = mapOf(PAPER to true)) { key ->
                        PaperTheme {
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
                    entry<Providers> { ProvidersRoute(onBack = ::back) }
                    entry<McpServers> { McpServersRoute(onBack = ::back) }
                    entry<PrivacyCenter> { PrivacyCenterRoute(onBack = ::back) }
                    entry<ImageBackends> { BackendsRoute(onBack = ::back) }
                    entry<Accounts> { AccountsRoute(onBack = ::back) }
                    entry<Compute> { key -> ComputeRoute(onBack = ::back, openReport = key.report) }
                    entry<About> {
                        AboutRoute(
                            info = AboutInfo(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), BuildConfig.BUILD_DATE, BuildConfig.DEBUG),
                            onBack = ::back,
                            onOpenDiagnostics = { navigate(Compute(report = true)) },
                        )
                    }
                    entry<Memory> { MemoryRoute(onBack = ::back) }
                    entry<Usage> { UsageRoute(onBack = ::back) }
                    entry<Recipes> { RecipesRoute(onBack = ::back, onOpenChat = { navigate(Chat(it)) }) }
                    entry<Vault> { VaultRoute(onBack = ::back, onOpenModels = { switchTo(Models) }) }
                },
            )
            if (showBar && tab != null) {
                AutobotNavBar(TABS, tab, { selected -> if (selected != tab) switchTo(selected.root) })
            }
        }
    }
}
