package dev.wckdboy.autobot.core.data.settings

import dev.wckdboy.autobot.core.network.NetworkMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSchemaTest {
    @Test
    fun roundTripsEverySetting() {
        val settings = AppSettings(
            themeMode = ThemeMode.LIGHT,
            amoled = true,
            dynamicColor = true,
            networkMode = NetworkMode.Tor("10.0.0.2", 9150),
            allowLoopbackWhenOffline = false,
            appLockEnabled = false,
            lockTimeoutMillis = 300_000L,
            incognitoByDefault = true,
            onboarded = true,
        )
        assertEquals(settings, SettingsSchema.decode(SettingsSchema.encode(settings)))
    }

    @Test
    fun damagedValuesFailSafe() {
        val decoded = SettingsSchema.decode(
            mapOf(
                SettingsSchema.THEME to "NEON",
                SettingsSchema.NETWORK_MODE to "tor:nohost",
                SettingsSchema.APP_LOCK to "maybe",
                SettingsSchema.LOCK_TIMEOUT to "999999999",
            ),
        )
        assertEquals(ThemeMode.DARK, decoded.themeMode)
        assertEquals(NetworkMode.Offline, decoded.networkMode)
        assertEquals(true, decoded.appLockEnabled)
        assertEquals(900_000L, decoded.lockTimeoutMillis)
        assertEquals(AppSettings(), SettingsSchema.decode(emptyMap()))
    }
}
