package dev.wckdboy.autobot.shared.settings

import dev.wckdboy.autobot.shared.cascade.CascadePolicy
import org.junit.Test
import org.junit.Assert.assertTrue

class SettingsWordsTest {
    @Test
    fun phoneFirstsInfoNamesEveryOptionAndWhatItCovers() {
        CascadePolicy.entries.forEach { assertTrue(it.name, it.title + ": " + it.detail in SettingsWords.cascade) }
        assertTrue(SettingsWords.cascade.endsWith(SettingsWords.CASCADE_SCOPE))
    }

    @Test
    fun phoneFirstsInfoSaysWhatEachOptionDoesOnTheUsersNode() {
        val node = SettingsWords.cascade.substringAfter(SettingsWords.CASCADE_NODE + "\n")
        CascadePolicy.entries.forEach { assertTrue(it.name, it.title + ": " + it.nodeDetail in node) }
        assertTrue(SettingsWords.cascade.startsWith(SettingsWords.CASCADE_CLOUD + "\n"))
        assertTrue("your node" in SettingsWords.CASCADE_SCOPE)
    }

    @Test
    fun theWordsSayChatNotSession() {
        val all = listOf(SettingsWords.INCOGNITO, SettingsWords.WEB_SEARCH, SettingsWords.DELETE_ALL_TEXT, SettingsWords.DELETE_ALL_TITLE)
        all.forEach { assertTrue(it, "session" !in it.lowercase()) }
    }
}
