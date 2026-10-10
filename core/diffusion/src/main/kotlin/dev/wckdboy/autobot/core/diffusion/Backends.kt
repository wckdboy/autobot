package dev.wckdboy.autobot.core.diffusion

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.core.diffusion.engine.LocalSseEngine
import dev.wckdboy.autobot.core.diffusion.engine.OnDeviceEngine
import dev.wckdboy.autobot.core.diffusion.engine.SdApiEngine
import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.ModelKind
import dev.wckdboy.autobot.core.models.ModelLibrary
import dev.wckdboy.autobot.core.network.HttpClientFactory
import dev.wckdboy.autobot.core.network.Loopback
import dev.wckdboy.autobot.core.network.RouteOverride
import dev.wckdboy.autobot.engine.diffusion.LocalSd
import dev.wckdboy.autobot.engine.npu.LocalNpu
import dev.wckdboy.autobot.engine.llama.LocalLlm
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
enum class BackendKind(val label: String) {
    /** stable-diffusion.cpp on this phone (models from the Models tab). */
    ON_DEVICE("This phone"),

    /** AUTOMATIC1111 / Forge / SD.Next REST API. */
    SD_API("A1111 / Forge API"),

    /** Local HTTP+SSE diffusion server (on-device engine sidecar, Local Dream host mode). */
    LOCAL_SSE("Local SSE engine"),
}

/** A configured image backend. Holds no secrets. [routing] is a [RouteOverride] name. */
@Serializable
data class DiffusionBackend(
    val id: String,
    val kind: BackendKind,
    val name: String,
    val baseUrl: String,
    val routing: String = RouteOverride.INHERIT.name,
) {
    val isLoopback: Boolean get() = kind == BackendKind.ON_DEVICE || Loopback.isLoopbackUrl(baseUrl)
}

/** Backends, the default selection and the last request, in the settings DataStore. */
@Singleton
class DiffusionBackendRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(DiffusionBackend.serializer())

    private val prefs: Flow<Preferences> = dataStore.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    val backends: Flow<List<DiffusionBackend>> = prefs.map { p ->
        p[KEY_BACKENDS]?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }.orEmpty()
    }.distinctUntilChanged()

    val defaultBackendId: Flow<String?> = prefs.map { it[KEY_DEFAULT] }.distinctUntilChanged()

    val lastRequest: Flow<DiffusionRequest> = prefs.map { p ->
        p[KEY_LAST_REQUEST]?.let { runCatching { json.decodeFromString(DiffusionRequest.serializer(), it) }.getOrNull() } ?: DiffusionRequest()
    }.distinctUntilChanged()

    suspend fun defaultBackend(): DiffusionBackend? {
        val all = backends.first()
        val id = defaultBackendId.first()
        return all.firstOrNull { it.id == id } ?: all.firstOrNull()
    }

    suspend fun save(backend: DiffusionBackend) {
        dataStore.edit { p ->
            val current = p[KEY_BACKENDS]?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }.orEmpty()
            val updated = current.filterNot { it.id == backend.id } + backend
            p[KEY_BACKENDS] = json.encodeToString(listSerializer, updated)
            if (p[KEY_DEFAULT] == null) p[KEY_DEFAULT] = backend.id
        }
    }

    suspend fun delete(id: String) {
        dataStore.edit { p ->
            val current = p[KEY_BACKENDS]?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }.orEmpty()
            val remaining = current.filterNot { it.id == id }
            p[KEY_BACKENDS] = json.encodeToString(listSerializer, remaining)
            if (p[KEY_DEFAULT] == id) remaining.firstOrNull()?.let { p[KEY_DEFAULT] = it.id } ?: p.remove(KEY_DEFAULT)
        }
    }

    suspend fun setDefault(id: String) {
        dataStore.edit { it[KEY_DEFAULT] = id }
    }

    suspend fun saveLastRequest(request: DiffusionRequest) {
        dataStore.edit { it[KEY_LAST_REQUEST] = json.encodeToString(DiffusionRequest.serializer(), request) }
    }

    fun newId(): String = UUID.randomUUID().toString()

    companion object {
        private val KEY_BACKENDS = stringPreferencesKey("diffusion_backends")
        private val KEY_DEFAULT = stringPreferencesKey("diffusion_default_backend")
        private val KEY_LAST_REQUEST = stringPreferencesKey("diffusion_last_request")

        /** Starting points; loopback, so they work in Offline mode. */
        val PRESETS: List<Pair<BackendKind, String>> = listOf(
            BackendKind.LOCAL_SSE to "http://127.0.0.1:8081",
            BackendKind.SD_API to "http://127.0.0.1:7860",
        )
    }
}

/** Builds engines bound to the network policy (kill switch, route, audit tag per backend). */
@Singleton
class DiffusionEngineFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clients: HttpClientFactory,
    private val library: ModelLibrary,
    private val localSd: LocalSd,
    private val localLlm: LocalLlm,
    private val localNpu: LocalNpu,
) {
    fun create(backend: DiffusionBackend): DiffusionEngine {
        val override = runCatching { RouteOverride.valueOf(backend.routing) }.getOrDefault(RouteOverride.INHERIT)
        val clientFactory = { clients.create(tag = "image:${backend.name}", override = override) }
        return when (backend.kind) {
            BackendKind.SD_API -> SdApiEngine(backend.baseUrl, clientFactory)
            BackendKind.LOCAL_SSE -> LocalSseEngine(backend.baseUrl, clientFactory)
            BackendKind.ON_DEVICE -> OnDeviceEngine(library, localSd, localLlm, File(context.cacheDir, "sd-in"), localNpu)
        }
    }
}

/**
 * Adds the built-in "This phone" backend when the first on-device image model is ready (and
 * makes it the default if nothing else is configured); removes it when none are left.
 */
@Singleton
class OnDeviceBackendSync @Inject constructor(
    private val backends: DiffusionBackendRepository,
    private val library: ModelLibrary,
) {
    fun start(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            library.models
                .map { list -> list.any { it.isReady && it.engine == EngineKind.DIFFUSION && it.kind == ModelKind.IMAGE } }
                .distinctUntilChanged()
                .collect { available ->
                    val existing = backends.backends.first().firstOrNull { it.id == ON_DEVICE_ID }
                    when {
                        available && existing == null -> backends.save(DiffusionBackend(ON_DEVICE_ID, BackendKind.ON_DEVICE, "This phone", "local://this-phone"))
                        !available && existing != null -> backends.delete(ON_DEVICE_ID)
                    }
                }
        }
    }

    companion object {
        const val ON_DEVICE_ID = "on-device"
    }
}
