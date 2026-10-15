package dev.wckdboy.autobot.core.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.wckdboy.autobot.core.data.db.AutobotDatabase
import dev.wckdboy.autobot.core.data.model.Provider
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.data.model.ProviderModelsRow
import dev.wckdboy.autobot.providers.remote.ModelCache
import dev.wckdboy.autobot.providers.remote.RemoteModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Providers in Room: the migrations (preset id, fetched model lists, helper model) and the model-list cache. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProviderStorageTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AutobotDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationsFromFourKeepProvidersAndAddPresetIdAndModelLists() {
        helper.createDatabase(DB, 4).use { db ->
            db.execSQL(
                "INSERT INTO providers (id, kind, displayName, baseUrl, defaultModel, routing, apiKeyRef) " +
                    "VALUES ('p1', 'OPENAI_COMPATIBLE', 'Groq', 'https://api.groq.com/openai/v1', 'llama-3.3-70b-versatile', 'TOR', 'ref-1')",
            )
        }
        helper.runMigrationsAndValidate(DB, 7, true).use { db ->
            db.query("SELECT kind, baseUrl, routing, apiKeyRef, presetId FROM providers WHERE id = 'p1'").use { c ->
                c.moveToFirst()
                assertEquals("OPENAI_COMPATIBLE", c.getString(0))
                assertEquals("https://api.groq.com/openai/v1", c.getString(1))
                assertEquals("TOR", c.getString(2))
                assertEquals("ref-1", c.getString(3))
                assertEquals(true, c.isNull(4))
            }
            db.execSQL("INSERT INTO provider_models (providerId, fetchedAt, json) VALUES ('p1', 1, '{}')")
            // Deleting the provider deletes its model list.
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM providers WHERE id = 'p1'")
            db.query("SELECT COUNT(*) FROM provider_models").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun migrationToEightKeepsProvidersAndAddsAnEmptyHelperModel() {
        helper.createDatabase(DB, 7).use { db ->
            db.execSQL(
                "INSERT INTO providers (id, kind, displayName, baseUrl, defaultModel, routing, apiKeyRef, presetId) " +
                    "VALUES ('g', 'OPENAI_COMPATIBLE', 'OpenCode Go', 'https://opencode.ai/inference/go/v1', 'kimi-k2.6', 'INHERIT', 'ref-g', 'opencode-go')",
            )
        }
        helper.runMigrationsAndValidate(DB, 8, true).use { db ->
            db.query("SELECT displayName, defaultModel, apiKeyRef, presetId, helperModel FROM providers WHERE id = 'g'").use { c ->
                c.moveToFirst()
                assertEquals("OpenCode Go", c.getString(0))
                assertEquals("kimi-k2.6", c.getString(1))
                assertEquals("ref-g", c.getString(2))
                assertEquals("opencode-go", c.getString(3))
                assertEquals(true, c.isNull(4))
            }
            db.execSQL("UPDATE providers SET helperModel = 'glm-5.3-flash' WHERE id = 'g'")
            db.query("SELECT helperModel FROM providers WHERE id = 'g'").use { c ->
                c.moveToFirst()
                assertEquals("glm-5.3-flash", c.getString(0))
            }
        }
    }

    @Test
    fun oldRowsResolveToTheirPresetByHost() {
        val legacy = Provider("p", ProviderKind.OPENAI_COMPATIBLE, "Groq", "https://api.groq.com/openai/v1", "m")
        assertEquals("groq", legacy.preset().id)
        assertEquals("venice", legacy.copy(presetId = "venice").preset().id)
        assertEquals("deepseek", Provider("d", ProviderKind.DEEPSEEK, "DeepSeek", "https://api.deepseek.com", "deepseek-chat").preset().id)
    }

    @Test
    fun modelListsRoundTripAndCascade() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AutobotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            db.providerDao().upsert(Provider("p", ProviderKind.ANTHROPIC, "Anthropic", "https://api.anthropic.com/v1", "claude-opus-5-5", presetId = "anthropic"))
            val cache = ModelCache(1_000, listOf(RemoteModel("claude-opus-5-5", "Claude Opus 5.5", contextWindow = 1_000_000)))
            db.providerModelsDao().upsert(ProviderModelsRow("p", cache.fetchedAt, cache.encode()))
            assertEquals(cache, ModelCache.decode(db.providerModelsDao().get("p")!!.json))
            assertEquals("anthropic", db.providerDao().get("p")!!.presetId)
            assertNull(db.providerDao().get("p")!!.helperModel)
            db.providerDao().upsert(db.providerDao().get("p")!!.copy(helperModel = "claude-haiku-5-5"))
            assertEquals("claude-haiku-5-5", db.providerDao().get("p")!!.helperModel)
            assertEquals(ProviderKind.ANTHROPIC, db.providerDao().get("p")!!.kind)
            assertEquals(1, db.providerModelsDao().observeAll().first().size)
            db.providerDao().delete("p")
            assertNull(db.providerModelsDao().get("p"))
        } finally {
            db.close()
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
