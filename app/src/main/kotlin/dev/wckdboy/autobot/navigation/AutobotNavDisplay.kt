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
import dev.wckdboy.autobot.core.designsystem.icon.AutobotIcons
import dev.wckdboy.autobot.feature.chat.conversation.ChatRoute
import dev.wckdboy.autobot.feature.chat.list.ChatListRoute
import dev.wckdboy.autobot.feature.imagine.backends.BackendsRoute
import dev.wckdboy.autobot.feature.imagine.gallery.GalleryRoute
import dev.wckdboy.autobot.feature.imagine.generate.GalleryHandoff
import dev.wckdboy.autobot.feature.imagine.generate.ImagineRoute
import dev.wckdboy.autobot.feature.settings.SettingsRoute
import dev.wckdboy.autobot.feature.settings.privacy.PrivacyCenterRoute
import dev.wckdboy.autobot.feature.settings.providers.ProvidersRoute

/** Bottom-bar tabs, keyed by destination class so `Imagine(handoff)` still selects IMAGINE. */
private enum class Tab(val label: String) { SESSIONS("sessions"), IMAGINE("imagine"), GALLERY("gallery"), SYSTEM("system") }

private fun NavKey.tab(): Tab? = when (this) {
    ChatList -> Tab.SESSIONS
    is Imagine -> Tab.IMAGINE
    Gallery -> Tab.GALLERY
    Settings -> Tab.SYSTEM
    else -> null
}

private fun Tab.root(): TopLevel = when (this) {
    Tab.SESSIONS -> ChatList
    Tab.IMAGINE -> Imagine()
    Tab.GALLERY -> Gallery
    Tab.SYSTEM -> Settings
}

private val TABS = listOf(
    NavItem(Tab.SESSIONS, Tab.SESSIONS.label, AutobotIcons.Terminal),
    NavItem(Tab.IMAGINE, Tab.IMAGINE.label, AutobotIcons.Spark),
    NavItem(Tab.GALLERY, Tab.GALLERY.label, AutobotIcons.Image),
    NavItem(Tab.SYSTEM, Tab.SYSTEM.label, AutobotIcons.Sliders),
)

/**
 * Navigation 3 host: one back stack whose first entry is a top-level tab. Detail screens push
 * on top and hide the bottom bar. Each entry gets its own saveable state and ViewModelStore.
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
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            val tab = current?.tab()
            val showBar = tab != null && backStack.size == 1
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
                    entry<ChatList> {
                        ChatListRoute(
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
                            onOpenGallery = { switchTo(Gallery) },
                        )
                    }
                    entry<Imagine> { key ->
                        ImagineRoute(
                            onOpenGallery = { switchTo(Gallery) },
                            onOpenBackends = { navigate(ImageBackends) },
                            handoff = key.handoffId?.let { GalleryHandoff(it, key.asInit) },
                        )
                    }
                    entry<Gallery> {
                        GalleryRoute(
                            onBack = null,
                            onReuse = { id, asInit -> switchTo(Imagine(id, asInit)) },
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
                    entry<Providers> { ProvidersRoute(onBack = ::back) }
                    entry<PrivacyCenter> { PrivacyCenterRoute(onBack = ::back) }
                    entry<ImageBackends> { BackendsRoute(onBack = ::back) }
                },
            )
            if (showBar && tab != null) {
                AutobotNavBar(TABS, tab, { selected -> if (selected != tab) switchTo(selected.root()) })
            }
        }
    }
}
