package dev.wckdboy.autobot.core.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.wckdboy.autobot.core.data.db.AutobotDatabase
import dev.wckdboy.autobot.core.data.model.ChatFolderRow
import dev.wckdboy.autobot.core.data.model.Conversation
import dev.wckdboy.autobot.core.data.model.ConversationTextRow
import dev.wckdboy.autobot.shared.chats.ChatSearch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Chat organization in Room: the 9 → 10 migration, pin / archive / folders and the search text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatStorageTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AutobotDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationToTenKeepsChatsAndTheirLogs() {
        helper.createDatabase(DB, 9).use { db ->
            db.execSQL("INSERT INTO conversations (id, title, providerId, model, createdAt, updatedAt) VALUES ('c', 'Trip', 'p', 'm', 1, 2)")
            db.execSQL("INSERT INTO session_events (sessionId, seq, time, json) VALUES ('c', 1, 1, '{}')")
        }
        helper.runMigrationsAndValidate(DB, 10, true).use { db ->
            db.query("SELECT title, updatedAt, pinned, archived, folderId FROM conversations WHERE id = 'c'").use { c ->
                c.moveToFirst()
                assertEquals("Trip", c.getString(0))
                assertEquals(2L, c.getLong(1))
                assertEquals(0, c.getInt(2))
                assertEquals(0, c.getInt(3))
                assertTrue(c.isNull(4))
            }
            db.query("SELECT COUNT(*) FROM session_events").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
            // Search text cascades with its chat.
            db.execSQL("INSERT INTO conversation_text (conversationId, text) VALUES ('c', 'hello')")
            db.execSQL("INSERT INTO chat_folders (id, name, createdAt) VALUES ('f', 'Work', 1)")
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM conversations WHERE id = 'c'")
            db.query("SELECT COUNT(*) FROM conversation_text").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun organizeAndSearch() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AutobotDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.conversationDao()
            dao.upsert(Conversation("a", "Rome trip", null, null, 1, 10))
            dao.upsert(Conversation("b", "Groceries", null, null, 2, 20))
            db.chatFolderDao().upsert(ChatFolderRow("f", "Travel", 3))

            dao.setPinned(listOf("a", "b"), true)
            dao.setArchived(listOf("b"), true)
            dao.setFolder(listOf("a"), "f")
            dao.rename("a", "Rome, 3 days")
            val a = dao.get("a")!!
            assertTrue(a.pinned)
            assertEquals("f", a.folderId)
            assertEquals("Rome, 3 days", a.title)
            assertEquals(10L, a.updatedAt) // renaming is not activity
            assertTrue(dao.get("b")!!.archived)

            dao.clearFolder("f")
            assertNull(dao.get("a")!!.folderId)

            assertEquals(setOf("a", "b"), dao.unindexed().toSet())
            dao.upsertSearchText(ConversationTextRow("b", "buy 50% more_milk"))
            assertEquals(listOf("a"), dao.unindexed())
            assertEquals(listOf("b"), dao.searchCandidates(ChatSearch.likePattern("MILK")).map { it.id })
            assertEquals(listOf("a"), dao.searchCandidates(ChatSearch.likePattern("rome")).map { it.id })
            // `%` and `_` are literal.
            assertEquals(listOf("b"), dao.searchCandidates(ChatSearch.likePattern("50%")).map { it.id })
            assertEquals(emptyList<String>(), dao.searchCandidates(ChatSearch.likePattern("50_")).map { it.id })
        } finally {
            db.close()
        }
    }

    private companion object {
        const val DB = "chat-migration-test.db"
    }
}
