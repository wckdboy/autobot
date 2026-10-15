package dev.wckdboy.autobot.core.data

import dev.wckdboy.autobot.core.data.db.McpServerDao
import dev.wckdboy.autobot.core.data.model.McpServer
import dev.wckdboy.autobot.core.data.model.ProviderRouting
import dev.wckdboy.autobot.core.security.Secret
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerRepositoryTest {

    private class FakeDao : McpServerDao {
        val rows = MutableStateFlow<Map<String, McpServer>>(emptyMap())
        override fun observeAll(): Flow<List<McpServer>> = rows.map { m -> m.values.sortedBy { it.name.lowercase() } }
        override suspend fun get(id: String): McpServer? = rows.value[id]
        override suspend fun upsert(server: McpServer) {
            rows.value = rows.value + (server.id to server)
        }
        override suspend fun delete(id: String) {
            rows.value = rows.value - id
        }
    }

    private class FakeSecrets : SecretSlots {
        val values = HashMap<String, Secret>()
        private var n = 0
        override fun newRef() = "ref-${++n}"
        override suspend fun put(ref: String, secret: Secret) {
            values[ref] = secret
        }
        override suspend fun get(ref: String) = values[ref]
        override suspend fun contains(ref: String) = ref in values
        override suspend fun delete(ref: String) {
            values.remove(ref)
        }
    }

    private val dao = FakeDao()
    private val secrets = FakeSecrets()
    private var now = 1_000L

    private fun repo(dispatcher: kotlinx.coroutines.CoroutineDispatcher) = McpServerRepository({ dao }, secrets, dispatcher, { now })

    private val server = McpServer("s1", "Notes", "https://notes.example.com/mcp", ProviderRouting.TOR, enabled = true, trusted = false)

    @Test
    fun tokenLivesOnlyInTheSecretStore() = runTest {
        val repo = repo(StandardTestDispatcher(testScheduler))
        val stored = repo.save(server, ApiKeyUpdate.Set(Secret("tok-123")))

        assertEquals("ref-1", stored.tokenRef)
        assertEquals(Secret("tok-123"), secrets.values["ref-1"])
        val row = dao.rows.value.getValue("s1")
        assertFalse(row.toString().contains("tok-123"))
        assertEquals(Secret("tok-123"), repo.token("s1"))
        assertTrue(repo.hasToken(row))
    }

    @Test
    fun keepReplaceAndClearTheToken() = runTest {
        val repo = repo(StandardTestDispatcher(testScheduler))
        repo.save(server, ApiKeyUpdate.Set(Secret("one")))
        // Editing other fields keeps the token (the editor passes a row without the ref).
        repo.save(server.copy(name = "Renamed", tokenRef = null), ApiKeyUpdate.Keep)
        assertEquals(Secret("one"), repo.token("s1"))
        assertEquals("Renamed", repo.get("s1")!!.name)

        repo.save(server, ApiKeyUpdate.Set(Secret("two")))
        assertEquals(listOf("ref-1"), secrets.values.keys.toList())
        assertEquals(Secret("two"), repo.token("s1"))

        repo.save(server, ApiKeyUpdate.Clear)
        assertNull(repo.get("s1")!!.tokenRef)
        assertTrue(secrets.values.isEmpty())
        assertNull(repo.token("s1"))
    }

    @Test
    fun deleteRemovesTheRowAndItsToken() = runTest {
        val repo = repo(StandardTestDispatcher(testScheduler))
        repo.save(server, ApiKeyUpdate.Set(Secret("tok")))
        repo.delete("s1")
        assertNull(repo.get("s1"))
        assertTrue(secrets.values.isEmpty())
    }

    @Test
    fun everySaveBumpsTheRevisionSoTheAgentReconnects() = runTest {
        val repo = repo(StandardTestDispatcher(testScheduler))
        val first = repo.save(server)
        val second = repo.save(server, ApiKeyUpdate.Set(Secret("new")))
        assertEquals(1_000L, first.updatedAt)
        assertTrue(second.updatedAt > first.updatedAt)
        assertTrue(second.toSpec() != first.toSpec())
    }

    @Test
    fun observeListsServersAndMapsToSharedSpecs() = runTest {
        val repo = repo(StandardTestDispatcher(testScheduler))
        repo.save(server)
        repo.save(McpServer("s2", "docs", "https://docs.example.org/mcp", enabled = false, trusted = true))
        val list = repo.observeServers().first()
        assertEquals(listOf("docs", "Notes"), list.map { it.name })

        val spec = list.last().toSpec()
        assertEquals("s1", spec.id)
        assertEquals("https://notes.example.com/mcp", spec.url)
        assertEquals("TOR", spec.route)
        assertTrue(spec.enabled)
        assertFalse(spec.trusted)
        val docs = list.first().toSpec()
        assertEquals("INHERIT", docs.route)
        assertFalse(docs.enabled)
        assertTrue(docs.trusted)
        assertNotNull(spec.revision)
    }
}
