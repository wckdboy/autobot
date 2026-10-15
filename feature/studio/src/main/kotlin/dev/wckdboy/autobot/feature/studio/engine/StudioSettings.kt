package dev.wckdboy.autobot.feature.studio.engine

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.wckdboy.autobot.core.network.Loopback
import dev.wckdboy.autobot.core.network.RouteOverride
import dev.wckdboy.autobot.core.security.Secret
import dev.wckdboy.autobot.core.security.SecretStore
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A ComfyUI server (a PC on the LAN / tailnet, or a loopback tunnel). [routing] is a
 * [RouteOverride] name. The optional auth header (a reverse proxy's token) is a name here and a
 * value in the encrypted [SecretStore] under [authRef]; this record holds no secret.
 */
@Serializable
data class ComfyBackendConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val routing: String = RouteOverride.INHERIT.name,
    val authHeader: String? = null,
    val authRef: String? = null,
) {
    val isLoopback: Boolean get() = Loopback.isLoopbackUrl(baseUrl)

    /** Cleartext to anything but loopback is refused by the kill switch (`ws://` too): https instead. */
    val cleartextRemote: Boolean get() = baseUrl.trim().startsWith("http://", ignoreCase = true) && !isLoopback
}

/** What changes the auth header's value on save. */
sealed interface AuthUpdate {
    data object Keep : AuthUpdate
    data object Clear : AuthUpdate
    class Set(val secret: Secret) : AuthUpdate {
        override fun toString(): String = "Set(${Secret.REDACTED})"
    }
}

/** Studio preferences: ComfyUI backends and the current project, in the settings DataStore. */
@Singleton
class StudioSettings @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val secrets: SecretStore,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(ComfyBackendConfig.serializer())
    private val prefs: Flow<Preferences> = dataStore.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    val comfyBackends: Flow<List<ComfyBackendConfig>> = prefs.map { decode(it[KEY_COMFY]) }.distinctUntilChanged()

    val currentProjectId: Flow<String?> = prefs.map { it[KEY_PROJECT] }.distinctUntilChanged()

    suspend fun setCurrentProject(id: String) {
        dataStore.edit { it[KEY_PROJECT] = id }
    }

    suspend fun saveComfy(config: ComfyBackendConfig, auth: AuthUpdate = AuthUpdate.Keep): ComfyBackendConfig {
        val existing = comfyBackends.first().firstOrNull { it.id == config.id }
        val ref = when (auth) {
            AuthUpdate.Keep -> existing?.authRef ?: config.authRef
            AuthUpdate.Clear -> {
                existing?.authRef?.let { secrets.delete(it) }
                null
            }
            is AuthUpdate.Set -> (existing?.authRef ?: secrets.newRef()).also { secrets.put(it, auth.secret) }
        }
        val saved = config.copy(authRef = ref, authHeader = config.authHeader?.trim()?.takeIf { it.isNotEmpty() && ref != null })
        dataStore.edit { p -> p[KEY_COMFY] = json.encodeToString(listSerializer, decode(p[KEY_COMFY]).filterNot { it.id == saved.id } + saved) }
        return saved
    }

    suspend fun deleteComfy(id: String) {
        val existing = comfyBackends.first().firstOrNull { it.id == id }
        existing?.authRef?.let { secrets.delete(it) }
        dataStore.edit { p -> p[KEY_COMFY] = json.encodeToString(listSerializer, decode(p[KEY_COMFY]).filterNot { it.id == id }) }
    }

    /** The auth header (name to value) for [config], read from the secret store. */
    suspend fun authHeader(config: ComfyBackendConfig): Pair<String, String>? {
        val name = config.authHeader ?: return null
        val ref = config.authRef ?: return null
        return secrets.get(ref)?.let { name to it.reveal() }
    }

    fun newId(): String = "comfy-" + UUID.randomUUID().toString().take(8)

    private fun decode(text: String?): List<ComfyBackendConfig> =
        text?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }.orEmpty()

    companion object {
        private val KEY_COMFY = stringPreferencesKey("studio_comfy_backends")
        private val KEY_PROJECT = stringPreferencesKey("studio_current_project")

        /** Starting points: ComfyUI's default port on loopback (a tunnel) or a PC over https. */
        val PRESETS = listOf("http://127.0.0.1:8188", "https://comfy.example.ts.net")
    }
}
