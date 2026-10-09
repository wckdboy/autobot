package dev.wckdboy.autobot.core.data

import dev.wckdboy.autobot.core.data.db.DatabaseHolder
import dev.wckdboy.autobot.core.data.di.IoDispatcher
import dev.wckdboy.autobot.core.data.model.Provider
import dev.wckdboy.autobot.core.security.Secret
import dev.wckdboy.autobot.core.security.SecretStore
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** How [ProviderRepository.save] should treat the stored API key. */
sealed interface ApiKeyUpdate {
    /** Leave the existing key (if any) unchanged. */
    data object Keep : ApiKeyUpdate

    /** Delete the stored key. */
    data object Clear : ApiKeyUpdate

    /** Replace the key. */
    class Set(val secret: Secret) : ApiKeyUpdate {
        override fun toString(): String = "Set(${Secret.REDACTED})"
    }
}

/**
 * Provider configurations. API keys are stored exclusively in the encrypted [SecretStore];
 * the database row only holds an opaque reference.
 */
@Singleton
class ProviderRepository @Inject constructor(
    private val database: DatabaseHolder,
    private val secretStore: SecretStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    fun observeProviders(): Flow<List<Provider>> =
        flow { emitAll(database.get().providerDao().observeAll()) }.flowOn(io)

    fun observeProvider(id: String): Flow<Provider?> =
        flow { emitAll(database.get().providerDao().observe(id)) }.flowOn(io)

    suspend fun getProvider(id: String): Provider? = withContext(io) { database.get().providerDao().get(id) }

    /** The first provider in display order, used when a conversation has none selected. */
    suspend fun firstProviderId(): String? = withContext(io) { database.get().providerDao().firstId() }

    fun newProviderId(): String = UUID.randomUUID().toString()

    /** Upserts [provider] and applies [apiKey]. Returns the stored provider. */
    suspend fun save(provider: Provider, apiKey: ApiKeyUpdate = ApiKeyUpdate.Keep): Provider = withContext(io) {
        val existing = database.get().providerDao().get(provider.id)
        val currentRef = existing?.apiKeyRef ?: provider.apiKeyRef
        val ref: String? = when (apiKey) {
            ApiKeyUpdate.Keep -> currentRef
            ApiKeyUpdate.Clear -> {
                currentRef?.let { secretStore.delete(it) }
                null
            }
            is ApiKeyUpdate.Set -> {
                val newRef = currentRef ?: secretStore.newRef()
                secretStore.put(newRef, apiKey.secret)
                newRef
            }
        }
        val stored = provider.copy(apiKeyRef = ref)
        database.get().providerDao().upsert(stored)
        stored
    }

    /** Deletes [id] and its stored API key. */
    suspend fun delete(id: String) = withContext(io) {
        val dao = database.get().providerDao()
        dao.get(id)?.apiKeyRef?.let { secretStore.delete(it) }
        dao.delete(id)
    }

    /** Decrypts the API key of [provider], if one is stored. */
    suspend fun apiKey(provider: Provider): Secret? = provider.apiKeyRef?.let { secretStore.get(it) }

    suspend fun hasApiKey(provider: Provider): Boolean =
        provider.apiKeyRef?.let { secretStore.contains(it) } ?: false
}
