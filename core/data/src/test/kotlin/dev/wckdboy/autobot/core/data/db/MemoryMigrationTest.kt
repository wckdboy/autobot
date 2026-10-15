package dev.wckdboy.autobot.core.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import dev.wckdboy.autobot.core.data.MemoryRepository.Companion.toNote
import dev.wckdboy.autobot.core.data.MemoryRepository.Companion.toRow
import dev.wckdboy.autobot.shared.memory.MemoryNote
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the 5 → 6 migration without a device: the exported schemas must agree that every
 * version-5 table is unchanged, and the generated migration may only create `memory_notes`.
 */
class MemoryMigrationTest {
    private val schemas = File("schemas/dev.wckdboy.autobot.core.data.db.AutobotDatabase")

    private fun entities(version: Int): Map<String, JsonObject> =
        Json.parseToJsonElement(File(schemas, "$version.json").readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
            .associate { it.jsonObject["tableName"]!!.jsonPrimitive.content to it.jsonObject }

    @Test
    fun version6OnlyAddsTheMemoryTable() {
        val v5 = entities(5)
        val v6 = entities(6)
        assertEquals(v5.keys + "memory_notes", v6.keys)
        v5.forEach { (table, entity) -> assertEquals(table, entity, v6[table]) }
        val columns = v6.getValue("memory_notes")["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
        assertEquals(listOf("id", "text", "tags", "createdAt", "updatedAt", "sessionId"), columns)
    }

    @Test
    fun generatedMigrationCreatesOnlyMemoryNotes() {
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

        val migration = AutobotDatabase_AutoMigration_5_6_Impl()
        assertEquals(5, migration.startVersion)
        assertEquals(6, migration.endVersion)
        migration.migrate(connection)

        assertEquals(2, executed.size)
        assertTrue(executed[0].startsWith("CREATE TABLE IF NOT EXISTS `memory_notes`"))
        assertTrue(executed[1].startsWith("CREATE INDEX IF NOT EXISTS `index_memory_notes_updatedAt`"))
        assertTrue(executed.none { it.contains("DROP") || it.contains("ALTER") })
    }

    @Test
    fun rowsRoundTripTags() {
        val note = MemoryNote("m-1", "text", listOf("a", "side-project"), 1, 2, null)
        assertEquals(note, note.toRow().toNote())
        assertEquals(emptyList<String>(), note.copy(tags = emptyList()).toRow().toNote().tags)
    }
}
