package dev.wckdboy.autobot.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.wckdboy.autobot.core.network.NetworkMode
import dev.wckdboy.autobot.core.network.RouteOverride
import dev.wckdboy.autobot.shared.search.SearchProvider
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed [AppSettings] under the shared [SettingsSchema] keys. Corrupt or unreadable files
 * fall back to safe defaults.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            AppSettings(
                themeMode = SettingsSchema.themeMode(prefs[Keys.THEME]),
                amoled = prefs[Keys.AMOLED] ?: false,
                dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: false,
                networkMode = NetworkMode.decode(prefs[Keys.NETWORK_MODE]),
                allowLoopbackWhenOffline = prefs[Keys.ALLOW_LOOPBACK] ?: true,
                appLockEnabled = prefs[Keys.APP_LOCK] ?: true,
                lockTimeoutMillis = SettingsSchema.lockTimeout(prefs[Keys.LOCK_TIMEOUT]),
                incognitoByDefault = prefs[Keys.INCOGNITO_DEFAULT] ?: false,
                onboarded = prefs[Keys.ONBOARDED] ?: false,
                memoryEnabled = prefs[Keys.MEMORY_ENABLED] ?: false,
                searchProvider = SettingsSchema.searchProvider(prefs[Keys.SEARCH_PROVIDER]),
                searchBaseUrl = prefs[Keys.SEARCH_BASE_URL].orEmpty(),
                searchRoute = SettingsSchema.searchRoute(prefs[Keys.SEARCH_ROUTE]),
                quotaFallback = prefs[Keys.QUOTA_FALLBACK] ?: false,
            )
        }
        .distinctUntilChanged()

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME] = mode.name }
    suspend fun setAmoled(enabled: Boolean) = edit { it[Keys.AMOLED] = enabled }
    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = enabled }
    suspend fun setNetworkMode(mode: NetworkMode) = edit { it[Keys.NETWORK_MODE] = NetworkMode.encode(mode) }
    suspend fun setAllowLoopbackWhenOffline(allow: Boolean) = edit { it[Keys.ALLOW_LOOPBACK] = allow }
    suspend fun setAppLockEnabled(enabled: Boolean) = edit { it[Keys.APP_LOCK] = enabled }
    suspend fun setLockTimeoutMillis(millis: Long) = edit { it[Keys.LOCK_TIMEOUT] = SettingsSchema.lockTimeout(millis) }
    suspend fun setIncognitoByDefault(enabled: Boolean) = edit { it[Keys.INCOGNITO_DEFAULT] = enabled }
    suspend fun setOnboarded() = edit { it[Keys.ONBOARDED] = true }
    suspend fun setMemoryEnabled(enabled: Boolean) = edit { it[Keys.MEMORY_ENABLED] = enabled }
    suspend fun setQuotaFallback(enabled: Boolean) = edit { it[Keys.QUOTA_FALLBACK] = enabled }

    suspend fun setWebSearch(provider: SearchProvider, baseUrl: String, route: RouteOverride) = edit {
        it[Keys.SEARCH_PROVIDER] = provider.name
        it[Keys.SEARCH_BASE_URL] = baseUrl.trim()
        it[Keys.SEARCH_ROUTE] = route.name
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit { block(it) }
    }

    private object Keys {
        val THEME = stringPreferencesKey(SettingsSchema.THEME)
        val AMOLED = booleanPreferencesKey(SettingsSchema.AMOLED)
        val DYNAMIC_COLOR = booleanPreferencesKey(SettingsSchema.DYNAMIC_COLOR)
        val NETWORK_MODE = stringPreferencesKey(SettingsSchema.NETWORK_MODE)
        val ALLOW_LOOPBACK = booleanPreferencesKey(SettingsSchema.ALLOW_LOOPBACK)
        val APP_LOCK = booleanPreferencesKey(SettingsSchema.APP_LOCK)
        val LOCK_TIMEOUT = longPreferencesKey(SettingsSchema.LOCK_TIMEOUT)
        val INCOGNITO_DEFAULT = booleanPreferencesKey(SettingsSchema.INCOGNITO_DEFAULT)
        val ONBOARDED = booleanPreferencesKey(SettingsSchema.ONBOARDED)
        val MEMORY_ENABLED = booleanPreferencesKey(SettingsSchema.MEMORY_ENABLED)
        val SEARCH_PROVIDER = stringPreferencesKey(SettingsSchema.SEARCH_PROVIDER)
        val SEARCH_BASE_URL = stringPreferencesKey(SettingsSchema.SEARCH_BASE_URL)
        val SEARCH_ROUTE = stringPreferencesKey(SettingsSchema.SEARCH_ROUTE)
        val QUOTA_FALLBACK = booleanPreferencesKey(SettingsSchema.QUOTA_FALLBACK)
    }
}
