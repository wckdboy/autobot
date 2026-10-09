package dev.wckdboy.autobot.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Top-level destinations shown in the bottom bar. Selecting one resets the back stack to it. */
sealed interface TopLevel : NavKey

@Serializable
data object ChatList : TopLevel

/** @param handoffId gallery item to load ([asInit] = as img2img input, else reuse its params). */
@Serializable
data class Imagine(val handoffId: String? = null, val asInit: Boolean = false) : TopLevel

@Serializable
data object Gallery : TopLevel

@Serializable
data object Settings : TopLevel

@Serializable
data class Chat(val conversationId: String) : NavKey

@Serializable
data object Providers : NavKey

@Serializable
data object PrivacyCenter : NavKey

@Serializable
data object ImageBackends : NavKey
