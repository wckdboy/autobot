package dev.wckdboy.autobot.core.data.settings

import dev.wckdboy.autobot.core.network.RouteOverride
import dev.wckdboy.autobot.core.security.Secret
import dev.wckdboy.autobot.core.security.SecretStore
import dev.wckdboy.autobot.shared.search.SearchProvider
import dev.wckdboy.autobot.shared.search.WebSearchConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * The `web_search` configuration: provider, URL and route in settings, the Brave API key in the
 * encrypted [SecretStore] (never in settings, never exported). [config] is read per search, so
 * the key is only decrypted while a search runs.
 */
@Singleton
class WebSearchRepository @Inject constructor(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
) {
    suspend fun config(): WebSearchConfig? {
        val s = settings.settings.first()
        if (s.searchProvider == SearchProvider.NONE) return null
        val key = if (s.searchProvider == SearchProvider.BRAVE) secrets.get(AppSettings.SEARCH_API_KEY_REF)?.reveal() else null
        return WebSearchConfig(s.searchProvider, s.searchBaseUrl, key, s.searchRoute.name)
    }

    suspend fun hasApiKey(): Boolean = secrets.contains(AppSettings.SEARCH_API_KEY_REF)

    /** Saves the settings; a non-blank [apiKey] replaces the stored key, [clearKey] deletes it. */
    suspend fun save(provider: SearchProvider, baseUrl: String, route: RouteOverride, apiKey: Secret?, clearKey: Boolean) {
        when {
            clearKey -> secrets.delete(AppSettings.SEARCH_API_KEY_REF)
            apiKey != null && apiKey.reveal().isNotBlank() -> secrets.put(AppSettings.SEARCH_API_KEY_REF, Secret(apiKey.reveal().trim()))
        }
        settings.setWebSearch(provider, baseUrl, route)
    }
}
