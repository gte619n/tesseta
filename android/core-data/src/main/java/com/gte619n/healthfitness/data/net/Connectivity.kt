package com.gte619n.healthfitness.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPL-AND-20 (Phase 6) — a reactive online/offline signal (D11, D17).
 *
 * Drives two things:
 *  - the global [SyncStatusBar] offline pill, and
 *  - the **offline AI affordance** (D17): the online-only AI entry points
 *    (DEXA/blood-report PDF upload, meal-photo capture, drug lookup, goals chat)
 *    are disabled when [isOnline] is false, with a "needs connection" message —
 *    nothing is queued.
 *
 * [isOnline] is a hot [StateFlow] backed by a [ConnectivityManager] network
 * callback (the same internet-capable + validated constraint WorkManager uses for
 * its CONNECTED sync triggers), seeded with the current state so a late collector
 * gets an immediate value.
 */
@Singleton
class Connectivity @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True when the device has a validated internet-capable network. */
    val isOnline: StateFlow<Boolean> = callbackFlow {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(currentOnline()) }
            override fun onLost(network: Network) { trySend(currentOnline()) }
            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities,
            ) { trySend(currentOnline()) }
        }

        trySend(currentOnline())
        cm?.registerNetworkCallback(request, callback)
        awaitClose { cm?.unregisterNetworkCallback(callback) }
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, currentOnline())

    private fun currentOnline(): Boolean {
        val network = cm?.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
