package dev.wckdboy.autobot

import androidx.navigation3.runtime.NavKey
import dev.wckdboy.autobot.core.data.settings.ThemeMode
import dev.wckdboy.autobot.navigation.About
import dev.wckdboy.autobot.navigation.Accounts
import dev.wckdboy.autobot.navigation.Chat
import dev.wckdboy.autobot.navigation.ChatList
import dev.wckdboy.autobot.navigation.Compute
import dev.wckdboy.autobot.navigation.Gallery
import dev.wckdboy.autobot.navigation.ImageBackends
import dev.wckdboy.autobot.navigation.Imagine
import dev.wckdboy.autobot.navigation.McpServers
import dev.wckdboy.autobot.navigation.Memory
import dev.wckdboy.autobot.navigation.Models
import dev.wckdboy.autobot.navigation.NodeAgent
import dev.wckdboy.autobot.navigation.NodeBot
import dev.wckdboy.autobot.navigation.PrivacyCenter
import dev.wckdboy.autobot.navigation.Providers
import dev.wckdboy.autobot.navigation.Recipes
import dev.wckdboy.autobot.navigation.Remote
import dev.wckdboy.autobot.navigation.Run
import dev.wckdboy.autobot.navigation.Settings
import dev.wckdboy.autobot.navigation.Studio
import dev.wckdboy.autobot.navigation.Usage
import dev.wckdboy.autobot.navigation.Vault

/**
 * Debug-build switches for UI work and screenshots, the Android side of iOS's `-AutobotDemo`
 * launch arguments (`ios/App/Core/Security/DebugFlags.swift`), as intent extras:
 *
 * ```
 * adb shell am start -n ai.wckd.autobot.debug/dev.wckdboy.autobot.MainActivity \
 *   --ez allow_screenshots true --ez demo true --es open models --es theme light
 * ```
 *
 * - `demo` fills an empty install with sample data (sessions, providers, gallery images, a model,
 *   audit entries, the node digest sample); `demo_digest failed` adds a receipt that failed.
 * - `open <screen>` starts on that screen (the names iOS's `-AutobotOpen` takes, see [destinations]).
 * - `theme light|dark|system` picks this launch's theme over the saved one.
 * - `skip_welcome` starts past the first-run welcome.
 *
 * Release builds never read them: [parse] returns [OFF] unless [debug] is set (`BuildConfig.DEBUG`).
 */
data class DebugLaunch(
    val demo: Boolean = false,
    val open: String? = null,
    val theme: ThemeMode? = null,
    val skipWelcome: Boolean = false,
    val digestFailed: Boolean = false,
) {
    /** The back stack [open] names (null: the normal start). Chats are the demo's own ids. */
    fun destinations(): List<NavKey>? = open?.let(::destinations)

    companion object {
        val OFF = DebugLaunch()

        const val EXTRA_DEMO = "demo"
        const val EXTRA_OPEN = "open"
        const val EXTRA_THEME = "theme"
        const val EXTRA_SKIP_WELCOME = "skip_welcome"
        const val EXTRA_DEMO_DIGEST = "demo_digest"

        /** The demo's chats (`DemoSeed`), so `open chat|agent|phone-first|ui` can name them. */
        const val DEMO_CHAT = "demo-chat-swift"
        const val DEMO_AGENT = "demo-chat-plan"
        const val DEMO_PHONE_FIRST = "demo-chat-stiller"
        /** A chat whose agent answered with a generative UI form (`show_ui`). */
        const val DEMO_UI = "demo-chat-ui"
        const val DEMO_NODE = "demo-node"
        const val DEMO_IMAGE = "demo-image-0"

        /**
         * The switches in an intent's extras ([bool] / [string] read one), or [OFF] when this is
         * not a debug build.
         */
        fun parse(debug: Boolean, bool: (String) -> Boolean, string: (String) -> String?): DebugLaunch {
            if (!debug) return OFF
            return DebugLaunch(
                demo = bool(EXTRA_DEMO),
                open = string(EXTRA_OPEN)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
                theme = string(EXTRA_THEME)?.trim()?.uppercase()?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } },
                skipWelcome = bool(EXTRA_SKIP_WELCOME),
                digestFailed = string(EXTRA_DEMO_DIGEST)?.trim()?.lowercase() == "failed",
            )
        }

        /** The same screen names as iOS's `-AutobotOpen`; null for one it does not know. */
        fun destinations(screen: String): List<NavKey>? = when (screen) {
            "run" -> listOf(Run)
            "imagine" -> listOf(Run, Imagine())
            "gallery" -> listOf(Run, Gallery())
            "viewer" -> listOf(Run, Gallery(openId = DEMO_IMAGE))
            "backends" -> listOf(Run, ImageBackends)
            "models" -> listOf(Models)
            "compute" -> listOf(Models, Compute())
            "accounts" -> listOf(Models, Accounts)
            "remote" -> listOf(Remote)
            "providers" -> listOf(Remote, Providers())
            "node" -> listOf(Remote, NodeAgent(DEMO_NODE))
            // The demo node's ops bot (Bot detail), as iOS's `-AutobotOpen bot`.
            "bot" -> listOf(Remote, NodeBot(DEMO_NODE, "ops"))
            "settings" -> listOf(Settings)
            "sessions" -> listOf(Run, ChatList)
            "privacy" -> listOf(Settings, PrivacyCenter)
            "about", "licenses" -> listOf(Settings, About)
            "mcp" -> listOf(Settings, McpServers)
            "memory" -> listOf(Settings, Memory)
            "vault" -> listOf(Settings, Vault)
            "usage" -> listOf(Settings, Usage)
            "recipes" -> listOf(Settings, Recipes)
            "chat" -> listOf(Run, Chat(DEMO_CHAT))
            "agent" -> listOf(Run, Chat(DEMO_AGENT))
            "phone-first" -> listOf(Run, Chat(DEMO_PHONE_FIRST))
            "ui", "a2ui" -> listOf(Run, Chat(DEMO_UI))
            "studio" -> listOf(Run, Studio())
            else -> if (screen.startsWith("studio-")) listOf(Run, Studio(screen.removePrefix("studio-").uppercase())) else null
        }
    }
}
