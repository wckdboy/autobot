package dev.wckdboy.autobot.providers.remote

import kotlin.random.Random
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

/**
 * Per-conversation session ids for providers that require one (`ProviderPreset.sessionHeader`;
 * OpenCode Go refuses requests without `x-opencode-session` and uses it for routing and prompt
 * caching). The id is a hash of the local session id: stable for one conversation, different
 * for every other, and it reveals nothing about the device or the local id.
 */
object ProviderSessions {
    /** The opaque key sent for local session [sessionId]. */
    fun key(sessionId: String): String = "autobot-session:$sessionId".encodeUtf8().sha256().hex().take(32)

    /**
     * The session header of [request] for [preset], or null when the provider needs none. A
     * request without a key (none of the app's own) gets a random one rather than a refusal.
     */
    fun header(preset: ProviderPreset, request: ChatRequest): Pair<String, String>? {
        val name = preset.sessionHeader ?: return null
        return name to (request.sessionKey ?: Random.nextBytes(16).toByteString().hex())
    }

    /** How error messages name the provider. */
    fun name(preset: ProviderPreset): String = if (preset.id == ProviderPresets.CUSTOM) "The provider" else preset.displayName
}
