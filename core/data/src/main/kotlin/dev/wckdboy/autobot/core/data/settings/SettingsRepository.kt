package dev.wckdboy.autobot.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.wckdboy.autobot.core.network.NetworkMode
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/**
 * Non-secret user preferences. Secrets (API keys) never go here; they live in the SecretStore.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val amoled: Boolean = false,
    val dynamicColor: Boolean = false,
    val networkMode: NetworkMode = NetworkMode.Offline,
    val allowLoopbackWhenOffline: Boolean = true,
    val appLockEnabled: Boolean = true,
    val lockTimeoutMillis: Long = DEFAULT_LOCK_TIMEOUT_MILLIS,
    val incognitoByDefault: Boolean = false,
) {
    companion object {
        const val DEFAULT_LOCK_TIMEOUT_MILLIS = 60_000L
    }
}

/** DataStore-backed [AppSettings]. Corrupt or unreadable files fall back to safe defaults. */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            AppSettings(
                themeMode = prefs[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
                amoled = prefs[Keys.AMOLED] ?: false,
                dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: false,
                networkMode = NetworkMode.decode(prefs[Keys.NETWORK_MODE]),
                allowLoopbackWhenOffline = prefs[Keys.ALLOW_LOOPBACK] ?: true,
                appLockEnabled = prefs[Keys.APP_LOCK] ?: true,
                lockTimeoutMillis = prefs[Keys.LOCK_TIMEOUT] ?: AppSettings.DEFAULT_LOCK_TIMEOUT_MILLIS,
                incognitoByDefault = prefs[Keys.INCOGNITO_DEFAULT] ?: false,
            )
        }
        .distinctUntilChanged()

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME] = mode.name }
    suspend fun setAmoled(enabled: Boolean) = edit { it[Keys.AMOLED] = enabled }
    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = enabled }
    suspend fun setNetworkMode(mode: NetworkMode) = edit { it[Keys.NETWORK_MODE] = NetworkMode.encode(mode) }
    suspend fun setAllowLoopbackWhenOffline(allow: Boolean) = edit { it[Keys.ALLOW_LOOPBACK] = allow }
    suspend fun setAppLockEnabled(enabled: Boolean) = edit { it[Keys.APP_LOCK] = enabled }
    suspend fun setLockTimeoutMillis(millis: Long) = edit { it[Keys.LOCK_TIMEOUT] = millis.coerceAtLeast(0) }
    suspend fun setIncognitoByDefault(enabled: Boolean) = edit { it[Keys.INCOGNITO_DEFAULT] = enabled }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit { block(it) }
    }

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val AMOLED = booleanPreferencesKey("amoled")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val NETWORK_MODE = stringPreferencesKey("network_mode")
        val ALLOW_LOOPBACK = booleanPreferencesKey("allow_loopback_offline")
        val APP_LOCK = booleanPreferencesKey("app_lock_enabled")
        val LOCK_TIMEOUT = longPreferencesKey("lock_timeout_ms")
        val INCOGNITO_DEFAULT = booleanPreferencesKey("incognito_default")
    }
}
