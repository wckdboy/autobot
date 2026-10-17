package dev.wckdboy.autobot.core.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import dev.wckdboy.autobot.core.data.UsageRepository.Companion.toRecord
import dev.wckdboy.autobot.core.data.UsageRepository.Companion.toRow
import dev.wckdboy.autobot.shared.usage.UsageRecord
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
 * The 12 → 13 migration (spend and quota board): every version-12 table is unchanged and the
 * generated migration only creates `usage_records` (with its index) and `usage_plan`.
 */
class UsageMigrationTest {
    private val schemas = File("schemas/dev.wckdboy.autobot.core.data.db.AutobotDatabase")

    private fun entities(version: Int): Map<String, JsonObject> =
        Json.parseToJsonElement(File(schemas, "$version.json").readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
            .associate { it.jsonObject["tableName"]!!.jsonPrimitive.content to it.jsonObject }

    @Test
    fun version13OnlyAddsTheUsageTables() {
        val v12 = entities(12)
        val v13 = entities(13)
        assertEquals(v12.keys + setOf("usage_records", "usage_plan"), v13.keys)
        v12.forEach { (table, entity) -> assertEquals(table, entity, v13[table]) }
        val columns = v13.getValue("usage_records")["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
        assertEquals(
            listOf("id", "start", "span", "provider", "model", "providerName", "presetId", "onDevice", "input", "output", "cacheRead", "cacheWrite", "reasoning", "requests", "unreported"),
            columns,
        )
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

        val migration = AutobotDatabase_AutoMigration_12_13_Impl()
        assertEquals(12, migration.startVersion)
        assertEquals(13, migration.endVersion)
        migration.migrate(connection)

        assertTrue(executed.isNotEmpty())
        assertTrue(executed.all { it.startsWith("CREATE TABLE IF NOT EXISTS") || it.startsWith("CREATE INDEX IF NOT EXISTS") })
        assertTrue(executed.any { it.contains("`usage_records`") } && executed.any { it.contains("`usage_plan`") })
    }

    @Test
    fun rowsRoundTrip() {
        val record = UsageRecord(300_000, 300_000, "p", "m", "Provider", "anthropic", false, 10, 2, 5, 1, 1, 3, 1)
        assertEquals(record, record.toRow().toRecord())
        assertEquals(record.id, record.toRow().id)
    }
}
