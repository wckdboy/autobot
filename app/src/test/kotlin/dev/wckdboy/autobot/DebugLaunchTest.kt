package dev.wckdboy.autobot

import dev.wckdboy.autobot.core.data.settings.ThemeMode
import dev.wckdboy.autobot.navigation.Chat
import dev.wckdboy.autobot.navigation.Compute
import dev.wckdboy.autobot.navigation.Models
import dev.wckdboy.autobot.navigation.Remote
import dev.wckdboy.autobot.navigation.Run
import dev.wckdboy.autobot.navigation.Settings
import dev.wckdboy.autobot.navigation.Studio
import dev.wckdboy.autobot.navigation.TopLevel
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class DebugLaunchTest {
    private val all = mapOf(
        DebugLaunch.EXTRA_DEMO to true,
        DebugLaunch.EXTRA_SKIP_WELCOME to true,
    )
    private val strings = mapOf(
        DebugLaunch.EXTRA_OPEN to " Models ",
        DebugLaunch.EXTRA_THEME to "light",
        DebugLaunch.EXTRA_DEMO_DIGEST to "failed",
    )

    private fun parse(debug: Boolean) = DebugLaunch.parse(debug, { all[it] == true }, { strings[it] })

    @Test
    fun `release builds ignore every extra`() {
        assertEquals(DebugLaunch.OFF, parse(debug = false))
        assertNull(parse(debug = false).destinations())
    }

    @Test
    fun `debug builds read the extras`() {
        val launch = parse(debug = true)
        assertTrue(launch.demo)
        assertTrue(launch.skipWelcome)
        assertTrue(launch.digestFailed)
        assertEquals("models", launch.open)
        assertEquals(ThemeMode.LIGHT, launch.theme)
        assertEquals(listOf(Models), launch.destinations())
    }

    @Test
    fun `nothing set is the normal start`() {
        val launch = DebugLaunch.parse(true, { false }, { null })
        assertEquals(DebugLaunch.OFF, launch)
        assertNull(launch.destinations())
    }

    @Test
    fun `an unknown theme or screen is ignored`() {
        val launch = DebugLaunch.parse(true, { false }, { if (it == DebugLaunch.EXTRA_THEME) "sepia" else "nowhere" })
        assertNull(launch.theme)
        assertNull(launch.destinations())
    }

    @Test
    fun `screens take the iOS names and start on their tab`() {
        assertEquals(listOf(Models, Compute()), DebugLaunch.destinations("compute"))
        assertEquals(listOf(Run, Chat(DebugLaunch.DEMO_PHONE_FIRST)), DebugLaunch.destinations("phone-first"))
        assertEquals(listOf(Run, Studio("AUDIO")), DebugLaunch.destinations("studio-audio"))
        val ios = listOf(
            "imagine", "gallery", "viewer", "backends", "models", "compute", "accounts", "remote", "settings", "sessions",
            "privacy", "providers", "about", "mcp", "licenses", "memory", "vault", "usage", "recipes", "studio", "chat", "agent", "phone-first", "bot",
        )
        for (screen in ios) {
            val keys = DebugLaunch.destinations(screen)
            assertTrue(screen, keys != null && keys.first() is TopLevel)
        }
        assertEquals(Settings, DebugLaunch.destinations("licenses")!!.first())
        assertEquals(Remote, DebugLaunch.destinations("node")!!.first())
    }
}
