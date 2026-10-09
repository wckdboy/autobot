package dev.wckdboy.autobot.core.models

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.wckdboy.autobot.core.security.Secret
import dev.wckdboy.autobot.core.security.SecretStore
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Civitai web/API host. `.red` is the domain that includes mature content (split 2026-04-15). */
enum class CivitaiHost(val host: String) { COM("civitai.com"), RED("civitai.red") }

data class AccountState(
    val huggingFaceUser: String? = null,
    val civitaiUser: String? = null,
    val civitaiHost: CivitaiHost = CivitaiHost.COM,
    /** Mature content shown in Civitai results; requires an explicit 18+ confirmation. */
    val showMature: Boolean = false,
)

/**
 * Model-hub credentials. Tokens are sealed in the Keystore-backed [SecretStore]; only the
 * display names (returned by the hubs) are kept in plain preferences. A token is only ever sent
 * to its own hub's host — downloads strip it on redirects to CDNs.
 */
@Singleton
class ModelAccounts @Inject constructor(
    private val secrets: SecretStore,
    private val dataStore: DataStore<Preferences>,
) {
    val state: Flow<AccountState> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            AccountState(
                huggingFaceUser = p[HF_USER],
                civitaiUser = p[CIVITAI_USER],
                civitaiHost = p[CIVITAI_HOST]?.let { runCatching { CivitaiHost.valueOf(it) }.getOrNull() } ?: CivitaiHost.COM,
                showMature = p[SHOW_MATURE] ?: false,
            )
        }
        .distinctUntilChanged()

    suspend fun huggingFaceToken(): Secret? = secrets.get(REF_HF)
    suspend fun civitaiKey(): Secret? = secrets.get(REF_CIVITAI)

    suspend fun setHuggingFace(token: Secret, user: String) {
        secrets.put(REF_HF, token)
        dataStore.edit { it[HF_USER] = user }
    }

    suspend fun clearHuggingFace() {
        secrets.delete(REF_HF)
        dataStore.edit { it.remove(HF_USER) }
    }

    suspend fun setCivitai(key: Secret, user: String) {
        secrets.put(REF_CIVITAI, key)
        dataStore.edit { it[CIVITAI_USER] = user }
    }

    suspend fun clearCivitai() {
        secrets.delete(REF_CIVITAI)
        dataStore.edit { it.remove(CIVITAI_USER) }
    }

    suspend fun setCivitaiHost(host: CivitaiHost) {
        dataStore.edit { it[CIVITAI_HOST] = host.name }
    }

    suspend fun setShowMature(show: Boolean) {
        dataStore.edit { it[SHOW_MATURE] = show }
    }

    suspend fun current(): AccountState = state.first()

    private companion object {
        const val REF_HF = "account-huggingface"
        const val REF_CIVITAI = "account-civitai"
        val HF_USER = stringPreferencesKey("account_hf_user")
        val CIVITAI_USER = stringPreferencesKey("account_civitai_user")
        val CIVITAI_HOST = stringPreferencesKey("civitai_host")
        val SHOW_MATURE = booleanPreferencesKey("civitai_show_mature")
    }
}
