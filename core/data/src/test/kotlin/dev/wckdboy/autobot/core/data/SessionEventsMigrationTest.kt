package dev.wckdboy.autobot.core.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.room.withTransaction
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.wckdboy.autobot.core.data.db.AutobotDatabase
import dev.wckdboy.autobot.core.data.model.Conversation
import dev.wckdboy.autobot.core.data.model.Message
import dev.wckdboy.autobot.core.data.model.MessageRole
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 11 → 12 drops `session_events`' separate `sessionId` index: its primary key (sessionId, seq)
 * already serves per-session reads and the cascade from `conversations`. Also the one-transaction
 * message write that bumps its chat's `updatedAt`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionEventsMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AutobotDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationToTwelveDropsOnlyTheRedundantIndexAndKeepsTheLogs() {
        helper.createDatabase(DB, 11).use { db ->
            db.execSQL("INSERT INTO conversations (id, title, providerId, model, createdAt, updatedAt) VALUES ('c', 'Trip', 'p', 'm', 1, 2)")
            db.execSQL("INSERT INTO session_events (sessionId, seq, time, json) VALUES ('c', 1, 1, '{\"a\":1}'), ('c', 2, 2, '{}')")
            assertTrue(indexes(db).contains("index_session_events_sessionId"))
        }
        helper.runMigrationsAndValidate(DB, 12, true).use { db ->
            assertFalse(indexes(db).contains("index_session_events_sessionId"))
            db.query("SELECT seq, json FROM session_events WHERE sessionId = 'c' ORDER BY seq").use { c ->
                assertEquals(2, c.count)
                c.moveToFirst()
                assertEquals(1L, c.getLong(0))
                assertEquals("{\"a\":1}", c.getString(1))
            }
            // Per-session reads still use an index: the primary key's.
            db.query("EXPLAIN QUERY PLAN SELECT * FROM session_events WHERE sessionId = 'c' ORDER BY seq").use { c ->
                val plan = buildString { while (c.moveToNext()) append(c.getString(c.columnCount - 1)).append('\n') }
                assertTrue(plan, plan.contains("sqlite_autoindex_session_events_1"))
            }
            // The cascade from the chat still works.
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM conversations WHERE id = 'c'")
            db.query("SELECT COUNT(*) FROM session_events").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun messageWriteTouchesItsChatInOneTransaction() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AutobotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            db.conversationDao().upsert(Conversation("a", "Rome trip", null, null, 1, 10))
            db.withTransaction {
                db.messageDao().upsert(Message("m", "a", MessageRole.USER, "hi", createdAt = 5))
                db.conversationDao().touch("a", 42)
            }
            assertEquals(42L, db.conversationDao().get("a")!!.updatedAt)
            assertEquals("Rome trip", db.conversationDao().get("a")!!.title)
            // A message for an unknown chat changes no chat.
            db.conversationDao().touch("missing", 99)
            assertEquals(42L, db.conversationDao().get("a")!!.updatedAt)
        } finally {
            db.close()
        }
    }

    private fun indexes(db: androidx.sqlite.db.SupportSQLiteDatabase): Set<String> =
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'session_events'").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    private companion object {
        const val DB = "session-events-migration-test.db"
    }
}
