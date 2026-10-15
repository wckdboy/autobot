package dev.wckdboy.autobot.core.data

import dev.wckdboy.autobot.core.data.db.DatabaseHolder
import dev.wckdboy.autobot.core.data.db.McpServerDao
import dev.wckdboy.autobot.core.data.di.IoDispatcher
import dev.wckdboy.autobot.core.data.model.McpServer
import dev.wckdboy.autobot.core.security.Secret
import dev.wckdboy.autobot.core.security.SecretStore
import dev.wckdboy.autobot.shared.mcp.McpServerSpec
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** The secret operations [McpServerRepository] needs (the app: Keystore-backed [SecretStore]). */
internal interface SecretSlots {
    fun newRef(): String
    suspend fun put(ref: String, secret: Secret)
    suspend fun get(ref: String): Secret?
    suspend fun contains(ref: String): Boolean
    suspend fun delete(ref: String)
}

/**
 * MCP server configurations. Bearer tokens are stored exclusively in the encrypted
 * [SecretStore] (like provider API keys); the row only holds an opaque reference.
 */
@Singleton
class McpServerRepository internal constructor(
    private val dao: () -> McpServerDao,
    private val secrets: SecretSlots,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
) {
    @Inject
    constructor(
        database: DatabaseHolder,
        secretStore: SecretStore,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(
        { database.get().mcpServerDao() },
        object : SecretSlots {
            override fun newRef() = secretStore.newRef()
            override suspend fun put(ref: String, secret: Secret) = secretStore.put(ref, secret)
            override suspend fun get(ref: String) = secretStore.get(ref)
            override suspend fun contains(ref: String) = secretStore.contains(ref)
            override suspend fun delete(ref: String) = secretStore.delete(ref)
        },
        io,
        System::currentTimeMillis,
    )

    fun observeServers(): Flow<List<McpServer>> = flow { emitAll(dao().observeAll()) }.flowOn(io)

    suspend fun get(id: String): McpServer? = withContext(io) { dao().get(id) }

    fun newId(): String = UUID.randomUUID().toString()

    /** Upserts [server] (bumping [McpServer.updatedAt]) and applies [token]. Returns the stored row. */
    suspend fun save(server: McpServer, token: ApiKeyUpdate = ApiKeyUpdate.Keep): McpServer = withContext(io) {
        val existing = dao().get(server.id)
        val currentRef = existing?.tokenRef ?: server.tokenRef
        val ref: String? = when (token) {
            ApiKeyUpdate.Keep -> currentRef
            ApiKeyUpdate.Clear -> {
                currentRef?.let { secrets.delete(it) }
                null
            }
            is ApiKeyUpdate.Set -> {
                val newRef = currentRef ?: secrets.newRef()
                secrets.put(newRef, token.secret)
                newRef
            }
        }
        val stored = server.copy(tokenRef = ref, updatedAt = maxOf(clock(), (existing?.updatedAt ?: 0) + 1))
        dao().upsert(stored)
        stored
    }

    /** Deletes [id] and its stored token. */
    suspend fun delete(id: String) = withContext(io) {
        val dao = dao()
        dao.get(id)?.tokenRef?.let { secrets.delete(it) }
        dao.delete(id)
    }

    /** Decrypts the token of [server], if one is stored. Only for the request to that server. */
    suspend fun token(server: McpServer): Secret? = server.tokenRef?.let { secrets.get(it) }

    suspend fun token(id: String): Secret? = get(id)?.let { token(it) }

    suspend fun hasToken(server: McpServer): Boolean = server.tokenRef?.let { secrets.contains(it) } ?: false
}

/** The shared runtime's view of a stored server (the token stays in the secret store). */
fun McpServer.toSpec(): McpServerSpec = McpServerSpec(
    id = id,
    name = name,
    url = url,
    route = routing.name,
    enabled = enabled,
    trusted = trusted,
    revision = updatedAt,
)
