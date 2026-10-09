package dev.wckdboy.autobot.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.wckdboy.autobot.core.designsystem.component.AutobotNavBar
import dev.wckdboy.autobot.core.designsystem.component.NavItem
import dev.wckdboy.autobot.core.designsystem.theme.PaperTheme
import dev.wckdboy.autobot.feature.chat.conversation.ChatRoute
import dev.wckdboy.autobot.feature.chat.list.ChatListRoute
import dev.wckdboy.autobot.feature.home.RemoteRoute
import dev.wckdboy.autobot.feature.home.RunRoute
import dev.wckdboy.autobot.feature.imagine.backends.BackendsRoute
import dev.wckdboy.autobot.feature.imagine.gallery.GalleryRoute
import dev.wckdboy.autobot.feature.imagine.generate.GalleryHandoff
import dev.wckdboy.autobot.feature.imagine.generate.ImagineRoute
import dev.wckdboy.autobot.feature.models.AccountsRoute
import dev.wckdboy.autobot.feature.models.ModelsRoute
import dev.wckdboy.autobot.feature.settings.SettingsRoute
import dev.wckdboy.autobot.feature.settings.privacy.PrivacyCenterRoute
import dev.wckdboy.autobot.feature.settings.providers.ProvidersRoute

private enum class Tab(val label: String, val root: TopLevel) {
    RUN("run", Run),
    MODELS("models", Models),
    REMOTE("remote", Remote),
    SETTINGS("settings", Settings),
}

private fun NavKey.tab(): Tab? = Tab.entries.firstOrNull { it.root == this }

private val TABS = Tab.entries.map { NavItem(it, it.label) }

/**
 * Navigation 3 host: one back stack whose first entry is a tab root. Detail screens push on
 * top and hide the bottom bar. The image studio is printed on paper (light) as in the mockups.
 */
@Composable
fun AutobotNavDisplay(backStack: NavBackStack<NavKey>) {
    fun navigate(key: NavKey) {
        if (backStack.lastOrNull() != key) backStack.add(key)
    }

    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    fun switchTo(root: TopLevel) {
        backStack.clear()
        backStack.add(root)
    }

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
                ),
                entryProvider = entryProvider {
                    entry<Run> {
                        RunRoute(
                            onOpenSession = { navigate(Chat(it)) },
                            onOpenImagine = { navigate(Imagine()) },
                            onOpenAllRuns = { navigate(ChatList) },
                            onOpenGalleryItem = { navigate(Gallery(it)) },
                            onOpenModels = { switchTo(Models) },
                        )
                    }
                    entry<Models> { ModelsRoute(onOpenAccounts = { navigate(Accounts) }) }
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
                            onOpenProviders = { navigate(Providers) },
                            onOpenPrivacyCenter = { navigate(PrivacyCenter) },
                            onOpenImageBackends = { navigate(ImageBackends) },
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
                        )
                    }
                    entry<Imagine> { key ->
                        PaperTheme {
                            ImagineRoute(
                                onBack = ::back,
                                onOpenGallery = { navigate(Gallery()) },
                                onOpenBackends = { navigate(ImageBackends) },
                                onOpenModels = { switchTo(Models) },
                                handoff = key.handoffId?.let { GalleryHandoff(it, key.asInit) },
                            )
                        }
                    }
                    entry<Gallery> { key ->
                        GalleryRoute(
                            onBack = ::back,
                            openId = key.openId,
                            onReuse = { id, asInit -> navigate(Imagine(id, asInit)) },
                        )
                    }
                    entry<Providers> { ProvidersRoute(onBack = ::back) }
                    entry<PrivacyCenter> { PrivacyCenterRoute(onBack = ::back) }
                    entry<ImageBackends> { BackendsRoute(onBack = ::back) }
                    entry<Accounts> { AccountsRoute(onBack = ::back) }
                },
            )
            if (showBar && tab != null) {
                AutobotNavBar(TABS, tab, { selected -> if (selected != tab) switchTo(selected.root) })
            }
        }
    }
}
