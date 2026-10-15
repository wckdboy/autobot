package dev.wckdboy.autobot.shared.chats

import dev.wckdboy.autobot.shared.backup.BackupConversation
import dev.wckdboy.autobot.shared.backup.BackupFolder
import dev.wckdboy.autobot.shared.backup.FolderImport
import dev.wckdboy.autobot.shared.backup.BackupManifest
import dev.wckdboy.autobot.shared.chats.ChatOrganizer.DAY_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatOrganizerTest {
    private val today = 100 * DAY_MS

    private fun chat(id: String, updated: Long, created: Long = updated, title: String = id, pinned: Boolean = false, archived: Boolean = false, folder: String? = null, incognito: Boolean = false) =
        ChatEntry(id, title, created, updated, pinned, archived, folder, incognito)

    @Test
    fun groupsByDayWithPinnedFirst() {
        val entries = listOf(
            chat("old", today - 30 * DAY_MS),
            chat("today", today + 5),
            chat("yesterday", today - 1),
            chat("week", today - 3 * DAY_MS),
            chat("pin", today - 50 * DAY_MS, pinned = true),
            chat("gone", today + 9, archived = true),
        )
        val sections = ChatOrganizer.sections(entries, ChatFilter.All, ChatSort.RECENT, today)
        assertEquals(
            listOf(ChatGroup.PINNED, ChatGroup.TODAY, ChatGroup.YESTERDAY, ChatGroup.THIS_WEEK, ChatGroup.OLDER),
            sections.map { it.group },
        )
        assertEquals(listOf("pin", "today", "yesterday", "week", "old"), sections.flatMap { s -> s.entries.map { it.id } })
    }

    @Test
    fun weekBoundaryIsSixDaysBeforeToday() {
        assertEquals(ChatGroup.THIS_WEEK, ChatOrganizer.group(today - 6 * DAY_MS, today))
        assertEquals(ChatGroup.OLDER, ChatOrganizer.group(today - 6 * DAY_MS - 1, today))
        assertEquals(ChatGroup.TODAY, ChatOrganizer.group(today + 3 * DAY_MS, today))
    }

    @Test
    fun sortsByCreatedAndName() {
        val entries = listOf(chat("a", updated = 3, created = 1, title = "beta"), chat("b", updated = 1, created = 2, title = "Alpha"), chat("c", updated = 2, created = 3, title = "gamma"))
        assertEquals(listOf("a", "c", "b"), ChatOrganizer.sort(entries, ChatSort.RECENT).map { it.id })
        assertEquals(listOf("c", "b", "a"), ChatOrganizer.sort(entries, ChatSort.CREATED).map { it.id })
        assertEquals(listOf("b", "a", "c"), ChatOrganizer.sort(entries, ChatSort.NAME).map { it.id })
        val byName = ChatOrganizer.sections(entries, ChatFilter.All, ChatSort.NAME, today)
        assertEquals(listOf(ChatGroup.NONE), byName.map { it.group })
    }

    @Test
    fun createdSortGroupsByCreationDay() {
        val entries = listOf(chat("a", updated = today + 1, created = today - 10 * DAY_MS))
        assertEquals(ChatGroup.OLDER, ChatOrganizer.sections(entries, ChatFilter.All, ChatSort.CREATED, today).single().group)
    }

    @Test
    fun filters() {
        val entries = listOf(
            chat("plain", 1),
            chat("pin", 2, pinned = true),
            chat("inFolder", 3, folder = "f"),
            chat("archivedInFolder", 4, folder = "f", archived = true),
            chat("ghost", 5, incognito = true),
        )
        fun ids(filter: ChatFilter) = ChatOrganizer.sections(entries, filter, ChatSort.RECENT, today).flatMap { s -> s.entries.map { it.id } }.toSet()
        assertEquals(setOf("plain", "pin", "inFolder", "ghost"), ids(ChatFilter.All))
        assertEquals(setOf("pin"), ids(ChatFilter.Pinned))
        assertEquals(setOf("inFolder"), ids(ChatFilter.Folder("f")))
        assertEquals(setOf("archivedInFolder"), ids(ChatFilter.Archived))
        assertEquals(setOf("ghost"), ids(ChatFilter.Incognito))
        assertTrue(ChatOrganizer.showsIncognitoChip(entries))
        assertEquals(mapOf("f" to 1), ChatOrganizer.folderCounts(entries))
        // The Pinned view is not split into a pinned section.
        assertEquals(listOf(ChatGroup.TODAY), ChatOrganizer.sections(entries, ChatFilter.Pinned, ChatSort.RECENT, 0).map { it.group })
    }

    @Test
    fun filterKeysRoundTrip() {
        listOf(ChatFilter.All, ChatFilter.Pinned, ChatFilter.Incognito, ChatFilter.Archived, ChatFilter.Folder("x-1")).forEach {
            assertEquals(it, ChatFilter.parse(it.key))
        }
        assertEquals(ChatFilter.All, ChatFilter.parse("folder:"))
        assertEquals(ChatFilter.All, ChatFilter.parse(null))
        assertEquals(ChatSort.RECENT, ChatSort.parse("bogus"))
        assertEquals(ChatSort.NAME, ChatSort.parse("NAME"))
    }

    @Test
    fun namingRules() {
        assertEquals("Work stuff", ChatNames.folderName("  Work \n  stuff "))
        assertEquals("Enter a name", ChatNames.folderProblem("   ", emptyList()))
        assertEquals("A folder with this name exists", ChatNames.folderProblem("work", listOf("Work")))
        assertNull(ChatNames.folderProblem("Home", listOf("Work")))
        assertEquals(ChatNames.MAX_FOLDER_NAME, ChatNames.folderName("x".repeat(100)).length)
        assertNull(ChatNames.title(" \n "))
        assertEquals("Trip plan", ChatNames.title(" Trip\nplan "))
        assertEquals("first line", ChatNames.fromPrompt("  first line\nsecond"))
        assertEquals("Image", ChatNames.fromPrompt(""))
        assertEquals(ChatNames.PROMPT_TITLE, ChatNames.fromPrompt("y".repeat(200)).length)
    }

    @Test
    fun importedFoldersMergeByIdOrName() {
        val existing = listOf(BackupFolder("a", "Work"), BackupFolder("b", "Home"))
        val plan = FolderImport.plan(existing, listOf(BackupFolder("a", "Renamed"), BackupFolder("x", " work "), BackupFolder("y", "Trips"), BackupFolder("z", "  ")))
        assertEquals(listOf("y"), plan.create.map { it.id })
        assertEquals("a", plan.localId("a"))
        assertEquals("a", plan.localId("x"))
        assertEquals("y", plan.localId("y"))
        assertNull(plan.localId("z"))
        assertNull(plan.localId(null))
    }

    @Test
    fun backupConversationFieldsAreOptionalBothWays() {
        // A 1.4 manifest (no pinned / archived / folders) still reads.
        val old = """{"conversations":[{"id":"c","title":"t","createdAt":1,"updatedAt":2}],"unknownFutureField":1}"""
        val decoded = BackupManifest.decode(old.encodeToByteArray())
        val c = decoded.conversations.single()
        assertFalse(c.pinned)
        assertFalse(c.archived)
        assertNull(c.folderId)
        assertTrue(decoded.folders.isEmpty())
        // New fields are written (older apps ignore unknown keys).
        val json = BackupManifest.encode(BackupManifest(conversations = listOf(BackupConversation("c", "t", null, null, 1, 2, pinned = true, folderId = "f")))).decodeToString()
        assertTrue(json.contains("\"pinned\":true"))
        assertTrue(json.contains("\"folderId\":\"f\""))
    }
}
