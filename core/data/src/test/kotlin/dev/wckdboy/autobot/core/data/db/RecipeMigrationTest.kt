package dev.wckdboy.autobot.core.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import dev.wckdboy.autobot.core.data.RecipePrefs
import dev.wckdboy.autobot.core.data.RecipeRepository
import dev.wckdboy.autobot.core.data.model.RecipeRow
import dev.wckdboy.autobot.shared.recipes.RecipeSchedule
import dev.wckdboy.autobot.shared.recipes.RecipeTemplates
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 13 → 14 migration (scheduled recipes) only adds `recipes`; the repository stores the shared
 * JSON and adds the example templates once.
 */
class RecipeMigrationTest {
    private val schemas = File("schemas/dev.wckdboy.autobot.core.data.db.AutobotDatabase")

    private fun entities(version: Int): Map<String, JsonObject> =
        Json.parseToJsonElement(File(schemas, "$version.json").readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
            .associate { it.jsonObject["tableName"]!!.jsonPrimitive.content to it.jsonObject }

    @Test
    fun version14OnlyAddsTheRecipesTable() {
        val v13 = entities(13)
        val v14 = entities(14)
        assertEquals(v13.keys + "recipes", v14.keys)
        v13.forEach { (table, entity) -> assertEquals(table, entity, v14[table]) }
        val columns = v14.getValue("recipes")["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
        assertEquals(listOf("id", "json", "updatedAt"), columns)
    }

    @Test
    fun generatedMigrationOnlyCreates() {
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
        val migration = AutobotDatabase_AutoMigration_13_14_Impl()
        assertEquals(13, migration.startVersion)
        assertEquals(14, migration.endVersion)
        migration.migrate(connection)
        assertEquals(1, executed.size)
        assertTrue(executed.single().startsWith("CREATE TABLE IF NOT EXISTS `recipes`"))
    }

    private class FakeDao : RecipeDao {
        val rows = MutableStateFlow<Map<String, RecipeRow>>(emptyMap())
        override fun observeAll(): Flow<List<RecipeRow>> = rows.map { it.values.sortedBy(RecipeRow::id) }
        override suspend fun all() = rows.value.values.sortedBy(RecipeRow::id)
        override suspend fun get(id: String) = rows.value[id]
        override suspend fun upsert(row: RecipeRow) = rows.update(row.id, row)
        override suspend fun delete(id: String) {
            rows.value = rows.value - id
        }
        private fun MutableStateFlow<Map<String, RecipeRow>>.update(id: String, row: RecipeRow) {
            value = value + (id to row)
        }
    }

    private class Prefs : RecipePrefs {
        override var seeded = false
        override var summaryInNotifications = false
    }

    @Test
    fun templatesAreAddedOnceDisabledAndInOrder() = runBlocking {
        val dao = FakeDao()
        val prefs = Prefs()
        val repo = RecipeRepository({ dao }, prefs, Dispatchers.Unconfined) { 42L }
        val first = repo.all()
        assertEquals(listOf(RecipeTemplates.MORNING_BRIEF, RecipeTemplates.NOTES_TIDY, RecipeTemplates.CHAT_DIGEST), first.map { it.id })
        assertTrue(first.none { it.enabled })
        assertTrue(prefs.seeded)
        // Deleted templates stay deleted.
        first.forEach { repo.delete(it.id) }
        assertTrue(repo.observe().first().isEmpty())
        val mine = first[0].copy(id = "mine", name = "Mine", schedule = RecipeSchedule.EveryHours(2), createdAt = 50)
        repo.save(mine)
        assertEquals(mine, repo.get("mine"))
        assertFalse(dao.rows.value.getValue("mine").json.isBlank())
    }
}
