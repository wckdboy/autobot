package dev.wckdboy.autobot.shared.recipes

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Storage format, templates, the tool catalog, notification copy and the chat digest. */
class RecipeModelTest {
    private val berlin = TimeZone.of("Europe/Berlin")
    private fun at(text: String): Long = LocalDateTime.parse(text).toInstant(berlin).toEpochMilliseconds()

    @Test
    fun codecRoundTripsEverySchedule() {
        listOf(RecipeSchedule.Daily(1), RecipeSchedule.Weekdays(2), RecipeSchedule.Weekly(3, 4), RecipeSchedule.EveryHours(5)).forEach { s ->
            val r = Recipe(
                "id", "Name", "Prompt", s, model = "qwen", tools = listOf("read"), includeRecentChats = true, enabled = true,
                state = RecipeState(nextRunAt = 9, lastRun = RecipeRunRecord(1, 2, RecipeRunStatus.DONE, notes = listOf("n"))),
            )
            assertEquals(r, RecipeCodec.decode(RecipeCodec.encode(r)))
        }
    }

    @Test
    fun theStoredFormatIsStable() {
        // What iOS's Codable mirror reads and writes: keep these names.
        val text = RecipeCodec.encode(Recipe("a", "B", "C", RecipeSchedule.Weekly(5, 1080)))
        assertTrue(text, text.contains(""""schedule":{"type":"weekly","dayOfWeek":5,"minuteOfDay":1080}"""))
        assertTrue(text, text.contains(""""state":{"waitingForUnlock":false}"""))
        val old = """{"id":"x","name":"n","prompt":"p","schedule":{"type":"daily","minuteOfDay":60},"future":"ignored"}"""
        assertEquals(RecipeSchedule.Daily(60), RecipeCodec.decode(old)?.schedule)
        assertNull(RecipeCodec.decode("not json"))
    }

    @Test
    fun templatesArePrefilledDisabledAndValid() {
        val all = RecipeTemplates.all(0)
        assertEquals(listOf("Morning brief from my feeds", "Weekly notes tidy", "Daily digest of my chats"), all.map { it.name })
        all.forEach { r ->
            assertFalse(r.enabled)
            assertTrue(r.problems().isEmpty())
            assertEquals(r.tools, RecipeTools.sanitize(r.tools))
        }
        assertTrue(all[0].usesNetwork)
        assertFalse(all[1].usesNetwork)
        assertTrue(all[1].requiresCharging)
        assertTrue(all[2].includeRecentChats)
        assertTrue(all[2].tools.isEmpty())
    }

    @Test
    fun problemsNameWhatIsMissing() {
        val blank = RecipeTemplates.blank("n", 0)
        assertEquals(listOf("Give the recipe a name", "Write what the recipe should do"), blank.problems())
    }

    @Test
    fun toolGroupsToggleTogether() {
        var tools = RecipeTools.toggle(emptyList(), RecipeTools.WEB, true)
        assertEquals(listOf("web_fetch", "web_search"), tools)
        tools = RecipeTools.toggle(tools, RecipeTools.READ_FILES, true)
        assertEquals(listOf("read", "glob", "grep", "web_fetch", "web_search"), tools)
        assertTrue(RecipeTools.isOn(tools, RecipeTools.WEB))
        assertEquals(listOf("read", "glob", "grep"), RecipeTools.toggle(tools, RecipeTools.WEB, false))
        // Never offered: questions, deletes, subagents, MCP.
        listOf("ask_user_question", "delete", "task", "mcp__gh__search").forEach { assertFalse(it in RecipeTools.ALLOWED) }
    }

    @Test
    fun notificationsSayLittleUnlessAskedTo() {
        val done = RecipeRunRecord(0, 1, RecipeRunStatus.DONE, summary = "Five headlines", notes = listOf("Needed approval for web_fetch"))
        assertEquals("Recipe Brief finished", RecipeCopy.title("Brief", done))
        assertEquals(RecipeCopy.OPEN_TO_READ, RecipeCopy.body(done, showSummary = false))
        assertEquals("Five headlines · Needed approval for web_fetch", RecipeCopy.body(done, showSummary = true))
        assertEquals("Recipe Brief didn't finish", RecipeCopy.title("Brief", done.copy(status = RecipeRunStatus.FAILED)))
        assertEquals("Unlock to run Brief", RecipeCopy.unlockToRun("Brief"))
    }

    @Test
    fun statusLinesAndMoments() {
        val now = at("2026-10-18T06:00")
        val r = Recipe(
            "r", "Brief", "p", RecipeSchedule.Daily(420), enabled = true,
            state = RecipeState(nextRunAt = at("2026-10-18T07:00"), lastRun = RecipeRunRecord(at("2026-10-17T07:00"), at("2026-10-17T07:02"), RecipeRunStatus.DONE)),
        )
        assertEquals("Next: today 07:00 · Done yesterday 07:02", RecipeCopy.status(r, now, berlin, approximate = false))
        assertEquals("Next: today around 07:00 · Done yesterday 07:02", RecipeCopy.status(r, now, berlin, approximate = true))
        assertEquals("Off", RecipeCopy.status(r.copy(enabled = false, state = RecipeState()), now, berlin, false))
        assertEquals("Waiting for unlock", RecipeCopy.status(r.copy(state = RecipeState(nextRunAt = 1, waitingForUnlock = true)), now, berlin, false))
        assertEquals("tomorrow 07:00", RecipeCopy.moment(at("2026-10-19T07:00"), now, berlin))
        assertEquals("Fri 18:00", RecipeCopy.moment(at("2026-10-23T18:00"), now, berlin))
        assertEquals("1 Nov 07:00", RecipeCopy.moment(at("2026-11-01T07:00"), now, berlin))
        assertEquals("Brief · Sun 18 Oct 07:00", RecipeCopy.chatTitle("Brief", at("2026-10-18T07:00"), berlin))
    }

    @Test
    fun summariesArePlainAndShort() {
        assertEquals("Heading text and more", RecipeCopy.summarize("# Heading\n**text** and `more`"))
        val long = "word ".repeat(100)
        val s = RecipeCopy.summarize(long)
        assertTrue(s.length <= RecipeCopy.SUMMARY_MAX)
        assertTrue(s.endsWith("…"))
        assertEquals("", RecipeCopy.summarize("```\ncode only\n```"))
    }

    @Test
    fun theChatDigestIsRecentNewestFirstAndBounded() {
        val chats = listOf(
            RecipeContext.ChatExcerpt("Old", 10, "old text"),
            RecipeContext.ChatExcerpt("Trip", 300, "plan the trip ".repeat(10)),
            RecipeContext.ChatExcerpt("Code", 200, "x".repeat(20_000) + " the end"),
        )
        val digest = RecipeContext.recentChats(chats, sinceMillis = 100)
        assertFalse(digest.contains("Old"))
        assertTrue(digest.indexOf("## Trip") < digest.indexOf("## Code"))
        assertTrue(digest.contains("the end"))
        assertTrue(digest.length <= RecipeContext.MAX_CHARS + 200)
        assertEquals("No chats changed since the last run.", RecipeContext.recentChats(chats, 1_000))
        val first = Recipe("r", "n", "p", RecipeSchedule.Daily(0))
        assertEquals(1_000 - RecipeSchedule.DAY_MS, RecipeContext.since(first, 1_000))
        assertEquals(5L, RecipeContext.since(first.copy(state = RecipeState(lastRun = RecipeRunRecord(5, 6, RecipeRunStatus.DONE))), 1_000))
    }

    @Test
    fun theSwiftBridgeAppliesTheSameRules() {
        val json = RecipeBridge.templates(0).first()
        assertFalse(RecipeBridge.isDue(json, Long.MAX_VALUE / 2))
        val on = RecipeBridge.saved(json.replace("\"enabled\":false", "\"enabled\":true"), json, 0)
        assertTrue(RecipeCodec.decode(on)!!.state.nextRunAt!! > 0)
        assertEquals(RecipeCodec.decode(on)!!.state.nextRunAt, RecipeBridge.nextWake(listOf(on, json)))
        assertEquals(-1L, RecipeBridge.nextWake(listOf(json)))
        assertTrue(RecipeBridge.usesNetwork(json))
        assertEquals(listOf("web_fetch", "web_search", "vault_search"), RecipeBridge.toggleTools(listOf("web_fetch", "web_search"), "vault", true))
        val record = RecipeBridge.failedRecord(RecipeCopy.NO_LOCAL_MODEL, 1, 2, manual = true)
        assertEquals("Recipe Morning brief from my feeds didn't finish", RecipeBridge.notificationTitle("Morning brief from my feeds", record))
        val after = RecipeCodec.decode(RecipeBridge.finished(on, record))!!
        assertEquals(RecipeCopy.NO_LOCAL_MODEL, after.state.lastRun?.summary)
        assertEquals(RecipeTrigger.MANUAL, after.state.lastRun?.trigger)
    }
}
