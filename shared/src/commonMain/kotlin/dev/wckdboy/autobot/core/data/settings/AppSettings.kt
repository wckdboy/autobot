package dev.wckdboy.autobot.core.data.settings

import dev.wckdboy.autobot.core.network.NetworkMode
import dev.wckdboy.autobot.core.network.RouteOverride
import dev.wckdboy.autobot.shared.search.SearchProvider

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/**
 * Non-secret user preferences, the same on both platforms. Secrets (API keys) never go here;
 * they live in the platform's secret store.
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
    /** The first-run welcome has been dismissed. */
    val onboarded: Boolean = false,
    /** Local memory: the agent may recall and save notes. Off by default. */
    val memoryEnabled: Boolean = false,
    /** `web_search` provider; [SearchProvider.NONE] keeps the tool away from the agent. */
    val searchProvider: SearchProvider = SearchProvider.NONE,
    /** SearXNG instance URL (Brave uses its fixed API host). The Brave key lives in the secret store. */
    val searchBaseUrl: String = "",
    /** Route for search requests, like a provider's routing override. */
    val searchRoute: RouteOverride = RouteOverride.INHERIT,
) {
    companion object {
        const val DEFAULT_LOCK_TIMEOUT_MILLIS = 60_000L

        /** Secret-store reference of the Brave Search API key (both platforms). */
        const val SEARCH_API_KEY_REF = "web-search-api-key"
    }
}

/**
 * Storage keys and a string codec for [AppSettings]. Android's DataStore stores typed values
 * under these keys; iOS and the encrypted export bundle store the string form. Unknown, missing
 * or malformed values fall back to the defaults, so a damaged store fails safe (Offline, locked).
 */
object SettingsSchema {
    const val THEME = "theme_mode"
    const val AMOLED = "amoled"
    const val DYNAMIC_COLOR = "dynamic_color"
    const val NETWORK_MODE = "network_mode"
    const val ALLOW_LOOPBACK = "allow_loopback_offline"
    const val APP_LOCK = "app_lock_enabled"
    const val LOCK_TIMEOUT = "lock_timeout_ms"
    const val INCOGNITO_DEFAULT = "incognito_default"
    const val ONBOARDED = "onboarded"
    const val MEMORY_ENABLED = "memory_enabled"
    const val SEARCH_PROVIDER = "search_provider"
    const val SEARCH_BASE_URL = "search_base_url"
    const val SEARCH_ROUTE = "search_route"

    /** Lock timeouts offered in Settings; anything else read from storage is clamped to these bounds. */
    val LOCK_TIMEOUTS_MILLIS = listOf(0L, 30_000L, 60_000L, 300_000L, 900_000L)

    fun themeMode(value: String?): ThemeMode = ThemeMode.entries.firstOrNull { it.name == value } ?: ThemeMode.DARK

    fun searchProvider(value: String?): SearchProvider = SearchProvider.parse(value)

    fun searchRoute(value: String?): RouteOverride = RouteOverride.entries.firstOrNull { it.name == value } ?: RouteOverride.INHERIT

    fun lockTimeout(value: Long?): Long = value?.coerceIn(0L, LOCK_TIMEOUTS_MILLIS.last()) ?: AppSettings.DEFAULT_LOCK_TIMEOUT_MILLIS

    fun encode(settings: AppSettings): Map<String, String> = mapOf(
        THEME to settings.themeMode.name,
        AMOLED to settings.amoled.toString(),
        DYNAMIC_COLOR to settings.dynamicColor.toString(),
        NETWORK_MODE to NetworkMode.encode(settings.networkMode),
        ALLOW_LOOPBACK to settings.allowLoopbackWhenOffline.toString(),
        APP_LOCK to settings.appLockEnabled.toString(),
        LOCK_TIMEOUT to settings.lockTimeoutMillis.toString(),
        INCOGNITO_DEFAULT to settings.incognitoByDefault.toString(),
        ONBOARDED to settings.onboarded.toString(),
        MEMORY_ENABLED to settings.memoryEnabled.toString(),
        SEARCH_PROVIDER to settings.searchProvider.name,
        SEARCH_BASE_URL to settings.searchBaseUrl,
        SEARCH_ROUTE to settings.searchRoute.name,
    )

    fun decode(values: Map<String, String>): AppSettings {
        val defaults = AppSettings()
        fun bool(key: String, default: Boolean) = values[key]?.toBooleanStrictOrNull() ?: default
        return AppSettings(
            themeMode = themeMode(values[THEME]),
            amoled = bool(AMOLED, defaults.amoled),
            dynamicColor = bool(DYNAMIC_COLOR, defaults.dynamicColor),
            networkMode = NetworkMode.decode(values[NETWORK_MODE]),
            allowLoopbackWhenOffline = bool(ALLOW_LOOPBACK, defaults.allowLoopbackWhenOffline),
            appLockEnabled = bool(APP_LOCK, defaults.appLockEnabled),
            lockTimeoutMillis = lockTimeout(values[LOCK_TIMEOUT]?.toLongOrNull()),
            incognitoByDefault = bool(INCOGNITO_DEFAULT, defaults.incognitoByDefault),
            onboarded = bool(ONBOARDED, defaults.onboarded),
            memoryEnabled = bool(MEMORY_ENABLED, defaults.memoryEnabled),
            searchProvider = searchProvider(values[SEARCH_PROVIDER]),
            searchBaseUrl = values[SEARCH_BASE_URL]?.trim().orEmpty(),
            searchRoute = searchRoute(values[SEARCH_ROUTE]),
        )
    }
}
