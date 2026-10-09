package dev.wckdboy.autobot.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Bottom-bar destinations (RUN · MODELS · REMOTE · SETTINGS). Selecting one resets the stack. */
sealed interface TopLevel : NavKey

@Serializable
data object Run : TopLevel

@Serializable
data object Models : TopLevel

@Serializable
data object Remote : TopLevel

@Serializable
data object Settings : TopLevel

/** All sessions. */
@Serializable
data object ChatList : NavKey

@Serializable
data class Chat(val conversationId: String) : NavKey

/** @param handoffId gallery item to load ([asInit] = as img2img input, else reuse its params). */
@Serializable
data class Imagine(val handoffId: String? = null, val asInit: Boolean = false) : NavKey

@Serializable
data class Gallery(val openId: String? = null) : NavKey

@Serializable
data object Providers : NavKey

@Serializable
data object PrivacyCenter : NavKey

@Serializable
data object ImageBackends : NavKey

@Serializable
data object Accounts : NavKey
