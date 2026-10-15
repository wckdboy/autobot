package dev.wckdboy.autobot.core.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import dev.wckdboy.autobot.core.data.VaultRepository
import dev.wckdboy.autobot.core.data.VaultRepository.Companion.toDocument
import dev.wckdboy.autobot.core.data.VaultRepository.Companion.toRow
import dev.wckdboy.autobot.shared.vault.VaultChunk
import dev.wckdboy.autobot.shared.vault.VaultDocument
import dev.wckdboy.autobot.shared.vault.VaultFileType
import dev.wckdboy.autobot.shared.vault.VectorCodec
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the 8 → 9 migration without a device: every version-8 table is unchanged, and the
 * generated migration only creates the two vault tables (and the documents index).
 */
class VaultMigrationTest {
    private val schemas = File("schemas/dev.wckdboy.autobot.core.data.db.AutobotDatabase")

    private fun entities(version: Int): Map<String, JsonObject> =
        Json.parseToJsonElement(File(schemas, "$version.json").readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
            .associate { it.jsonObject["tableName"]!!.jsonPrimitive.content to it.jsonObject }

    private fun columns(entity: JsonObject) = entity["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }

    @Test
    fun version9OnlyAddsTheVaultTables() {
        val v8 = entities(8)
        val v9 = entities(9)
        assertEquals(v8.keys + "vault_documents" + "vault_chunks", v9.keys)
        v8.forEach { (table, entity) -> assertEquals(table, entity, v9[table]) }
        assertEquals(
            listOf("id", "name", "type", "sizeBytes", "contentHash", "chunks", "pages", "model", "dims", "addedAt", "updatedAt", "source"),
            columns(v9.getValue("vault_documents")),
        )
        assertEquals(listOf("docId", "idx", "text", "startOffset", "endOffset", "page", "vector"), columns(v9.getValue("vault_chunks")))
    }

    @Test
    fun generatedMigrationCreatesOnlyVaultTables() {
        val executed = mutableListOf<String>()
        val statement = { sql: String ->
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SQLiteStatement::class.java)) { _, method, _ ->
                when (method.name) {
                    "step" -> { executed += sql; false }
                    else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
                }
            } as SQLiteStatement
        }
        val connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SQLiteConnection::class.java)) { _, method, args ->
            when (method.name) {
                "prepare" -> statement(args!![0] as String)
                else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
            }
        } as SQLiteConnection

        val migration = AutobotDatabase_AutoMigration_8_9_Impl()
        assertEquals(8, migration.startVersion)
        assertEquals(9, migration.endVersion)
        migration.migrate(connection)

        assertEquals(3, executed.size)
        assertTrue(executed.any { it.startsWith("CREATE TABLE IF NOT EXISTS `vault_documents`") })
        assertTrue(executed.any { it.startsWith("CREATE TABLE IF NOT EXISTS `vault_chunks`") })
        assertTrue(executed.any { it.startsWith("CREATE INDEX IF NOT EXISTS `index_vault_documents_updatedAt`") })
        assertTrue(executed.none { it.contains("DROP") || it.contains("ALTER") })
    }

    @Test
    fun rowsRoundTripAndVectorsSplitPerPassage() {
        val doc = VaultDocument("d-1", "a.pdf", VaultFileType.PDF, 10, "h", 2, 3, "m", 4, 1, 2, "content://x")
        assertEquals(doc, doc.toRow().toDocument())
        val vectors = VectorCodec.encode(listOf(floatArrayOf(1f, 0f, 0f, 0f), floatArrayOf(0f, 1f, 0f, 0f)), 4)
        val chunks = listOf(VaultChunk("d-1", 0, "a", 0, 1, 1), VaultChunk("d-1", 1, "b", 3, 4, 2))
        val rows = VaultRepository.rows(chunks, vectors, 4)
        assertEquals(2, rows.size)
        assertArrayEquals(vectors, rows[0].vector + rows[1].vector)
        assertEquals(listOf(0, 1), rows.map { it.idx })
        assertEquals(listOf(1, 2), rows.map { it.page })
    }
}
