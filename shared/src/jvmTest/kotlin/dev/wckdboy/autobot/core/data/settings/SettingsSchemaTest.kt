package dev.wckdboy.autobot.core.data.settings

import dev.wckdboy.autobot.core.models.SpeculativeMode
import dev.wckdboy.autobot.core.network.NetworkMode
import dev.wckdboy.autobot.core.network.RouteOverride
import dev.wckdboy.autobot.shared.search.SearchProvider
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
            memoryEnabled = true,
            searchProvider = SearchProvider.SEARXNG,
            searchBaseUrl = "https://search.example.org",
            searchRoute = RouteOverride.TOR,
            quotaFallback = true,
            redactCloud = false,
            speculativeMode = SpeculativeMode.OFF,
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
                SettingsSchema.MEMORY_ENABLED to "yes",
                SettingsSchema.SEARCH_PROVIDER to "GOOGLE",
                SettingsSchema.SEARCH_ROUTE to "VPN",
                SettingsSchema.QUOTA_FALLBACK to "maybe",
                SettingsSchema.REDACT_CLOUD to "nah",
                SettingsSchema.SPECULATIVE to "turbo",
            ),
        )
        // Redaction fails safe to on: a damaged value never sends personal data in the clear.
        assertEquals(true, decoded.redactCloud)
        assertEquals(SpeculativeMode.AUTO, decoded.speculativeMode)
        assertEquals(false, decoded.quotaFallback)
        assertEquals(false, decoded.memoryEnabled)
        assertEquals(SearchProvider.NONE, decoded.searchProvider)
        assertEquals(RouteOverride.INHERIT, decoded.searchRoute)
        assertEquals(ThemeMode.DARK, decoded.themeMode)
        assertEquals(NetworkMode.Offline, decoded.networkMode)
        assertEquals(true, decoded.appLockEnabled)
        assertEquals(900_000L, decoded.lockTimeoutMillis)
        assertEquals(AppSettings(), SettingsSchema.decode(emptyMap()))
    }

    @Test
    fun redactionIsOnForStoresFromBeforeTheSetting() {
        // A store written by an older version has no key: the gateway is on.
        val old = SettingsSchema.encode(AppSettings()) - SettingsSchema.REDACT_CLOUD
        assertEquals(true, SettingsSchema.decode(old).redactCloud)
        assertEquals(false, SettingsSchema.decode(old + (SettingsSchema.REDACT_CLOUD to "false")).redactCloud)
    }

    @Test
    fun speculativeDecodingDefaultsToAuto() {
        assertEquals(SpeculativeMode.AUTO, AppSettings().speculativeMode)
        assertEquals("auto", SettingsSchema.encode(AppSettings())[SettingsSchema.SPECULATIVE])
        for (mode in SpeculativeMode.entries) {
            assertEquals(mode, SettingsSchema.decode(mapOf(SettingsSchema.SPECULATIVE to mode.id)).speculativeMode)
        }
    }
}
