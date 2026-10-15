package dev.wckdboy.autobot.providers.remote

/** A configured provider as a chat route candidate: its id and default model. */
data class RouteTarget(val id: String, val defaultModel: String)

/** Where a conversation sends its turns. */
data class ChatRoute(val providerId: String, val model: String)

/**
 * Which provider a conversation uses once the provider list changes. Both apps run this when
 * providers are added or deleted and before a session opens, so a chat never keeps a provider
 * that no longer exists.
 */
object ChatRoutes {
    /**
     * The new route for a conversation on [providerId] / [model], given [providers] in display
     * order; `null` keeps it as is. A conversation whose provider was deleted, or that had none,
     * moves to the first provider with that provider's default model (the old model belonged to
     * the old provider). With no providers at all there is nothing to point at.
     */
    fun repoint(providerId: String?, model: String?, providers: List<RouteTarget>): ChatRoute? {
        val first = providers.firstOrNull() ?: return null
        if (!providerId.isNullOrEmpty() && providers.any { it.id == providerId }) return null
        return ChatRoute(first.id, first.defaultModel)
    }
}
