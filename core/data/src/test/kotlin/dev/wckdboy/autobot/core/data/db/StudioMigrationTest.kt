package dev.wckdboy.autobot.core.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.wckdboy.autobot.core.data.StudioRepository.Companion.toRow
import dev.wckdboy.autobot.core.data.model.StudioJobRow
import dev.wckdboy.autobot.core.data.model.StudioProjectRow
import dev.wckdboy.autobot.shared.studio.AssetType
import dev.wckdboy.autobot.shared.studio.EngineKinds
import dev.wckdboy.autobot.shared.studio.ParamValue
import dev.wckdboy.autobot.shared.studio.Recipe
import dev.wckdboy.autobot.shared.studio.StudioAsset
import dev.wckdboy.autobot.shared.studio.StudioCodec
import dev.wckdboy.autobot.shared.studio.StudioJob
import dev.wckdboy.autobot.shared.studio.StudioProject
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 10 → 11 migration (Creative Studio tables): every version-10 table is unchanged, the
 * generated migration only creates the Studio tables and their indices, existing data survives,
 * and the Studio rows round-trip their shared records.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StudioMigrationTest {
    private val schemas = File("schemas/dev.wckdboy.autobot.core.data.db.AutobotDatabase")

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AutobotDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun entities(version: Int): Map<String, JsonObject> =
        Json.parseToJsonElement(File(schemas, "$version.json").readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
            .associate { it.jsonObject["tableName"]!!.jsonPrimitive.content to it.jsonObject }

    private fun columns(entity: JsonObject) = entity["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }

    @Test
    fun version11OnlyAddsTheStudioTables() {
        val v10 = entities(10)
        val v11 = entities(11)
        assertEquals(v10.keys + STUDIO_TABLES, v11.keys)
        v10.forEach { (table, entity) -> assertEquals(table, entity, v11[table]) }
        assertEquals(listOf("id", "projectId", "type", "createdAt", "favorite", "durationMs", "json"), columns(v11.getValue("studio_assets")))
        assertEquals(listOf("id", "name", "updatedAt", "json"), columns(v11.getValue("studio_projects")))
        assertEquals(listOf("id", "createdAt", "json"), columns(v11.getValue("studio_jobs")))
    }

    @Test
    fun generatedMigrationCreatesOnlyStudioTables() {
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

        val migration = AutobotDatabase_AutoMigration_10_11_Impl()
        assertEquals(10, migration.startVersion)
        assertEquals(11, migration.endVersion)
        migration.migrate(connection)

        STUDIO_TABLES.forEach { table -> assertTrue(table, executed.any { it.startsWith("CREATE TABLE IF NOT EXISTS `$table`") }) }
        assertTrue(executed.all { it.startsWith("CREATE TABLE IF NOT EXISTS `studio_") || it.startsWith("CREATE INDEX IF NOT EXISTS `index_studio_") })
        assertTrue(executed.none { it.contains("DROP") || it.contains("ALTER") })
    }

    @Test
    fun migrationKeepsExistingData() {
        helper.createDatabase(DB, 10).use { db ->
            db.execSQL("INSERT INTO conversations (id, title, providerId, model, createdAt, updatedAt) VALUES ('c', 'Trip', 'p', 'm', 1, 2)")
        }
        helper.runMigrationsAndValidate(DB, 11, true).use { db ->
            db.query("SELECT title FROM conversations WHERE id = 'c'").use { c ->
                c.moveToFirst()
                assertEquals("Trip", c.getString(0))
            }
            db.query("SELECT COUNT(*) FROM studio_assets").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun rowsRoundTripAndProjectsTakeTheirAssets() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AutobotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.studioDao()
            val project = StudioProject("p-1", "Posters", null, 1, 2)
            dao.upsertProject(StudioProjectRow(project.id, project.name, project.updatedAt, StudioCodec.encodeProject(project)))
            val recipe = Recipe(EngineKinds.AUDIO, params = mapOf("prompt" to ParamValue.of("rain"), "seconds" to ParamValue.of(8.0)), seed = 7, output = AssetType.AUDIO)
            val clip = StudioAsset("a-000000000001", "p-1", AssetType.AUDIO, recipe, 5, "audio/wav", 100, durationMs = 8000)
            val other = StudioAsset("a-000000000002", "p-2", AssetType.IMAGE, Recipe(EngineKinds.DIFFUSION), 6, "image/png")
            dao.upsertAsset(clip.toRow())
            dao.upsertAsset(other.toRow())

            assertEquals(clip, StudioCodec.decodeAsset(dao.asset(clip.id)!!.json))
            assertEquals(listOf(clip.id), dao.assetsOf("p-1").map { it.id })
            assertEquals(listOf(other.id, clip.id), dao.assets().map { it.id })

            dao.deleteProject("p-1")
            assertNull(dao.asset(clip.id))
            assertEquals(listOf(other.id), dao.assets().map { it.id })
            assertTrue(dao.projects().isEmpty())

            val job = StudioJob("j-1", "p-2", recipe, createdAt = 3)
            dao.upsertJob(StudioJobRow(job.id, job.createdAt, StudioCodec.encodeJob(job)))
            assertEquals(job, StudioCodec.decodeJob(dao.jobs().single().json))
            dao.deleteJob(job.id)
            assertTrue(dao.jobs().isEmpty())
        } finally {
            db.close()
        }
    }

    private companion object {
        const val DB = "studio-migration-test"
        val STUDIO_TABLES = setOf("studio_projects", "studio_assets", "studio_workflows", "studio_snippets", "studio_jobs")
    }
}
