package dev.wckdboy.autobot.core.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.core.data.di.ApplicationScope
import dev.wckdboy.autobot.core.network.NetworkMode
import dev.wckdboy.autobot.core.network.NetworkPolicy
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The device's default network as the download job sees it (no traffic: callbacks only). */
data class NetworkState(val connected: Boolean, val metered: Boolean)

/**
 * What the download queue depends on: the kill switch ([NetworkPolicy]), the default network
 * (connected, metered) and the "Wi-Fi only" preference.
 */
@Singleton
class DownloadEnvironmentSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val policy: NetworkPolicy,
    private val dataStore: DataStore<Preferences>,
    @ApplicationScope scope: CoroutineScope,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    val wifiOnly: Flow<Boolean> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[WIFI_ONLY] ?: false }
        .distinctUntilChanged()

    val network: StateFlow<NetworkState> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.state())
            }

            override fun onLost(network: Network) {
                trySend(NetworkState(connected = false, metered = false))
            }
        }
        trySend(current())
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(5_000), current())

    val environment: Flow<DownloadEnvironment> = combine(policy.mode, network, wifiOnly) { mode, net, wifi ->
        DownloadEnvironment(networkOff = mode == NetworkMode.Offline, connected = net.connected, metered = net.metered, wifiOnly = wifi)
    }.distinctUntilChanged()

    suspend fun wifiOnlyNow(): Boolean = wifiOnly.first()

    suspend fun setWifiOnly(enabled: Boolean) {
        dataStore.edit { it[WIFI_ONLY] = enabled }
    }

    fun current(): NetworkState =
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.state() ?: NetworkState(connected = false, metered = false)

    suspend fun now(): DownloadEnvironment {
        val net = current()
        return DownloadEnvironment(policy.mode.value == NetworkMode.Offline, net.connected, net.metered, wifiOnlyNow())
    }

    private fun NetworkCapabilities.state() = NetworkState(
        connected = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        metered = !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
    )

    private companion object {
        val WIFI_ONLY = booleanPreferencesKey("downloads_wifi_only")
    }
}
