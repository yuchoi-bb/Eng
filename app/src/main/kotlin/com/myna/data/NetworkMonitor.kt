package com.myna.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 회선이 살아 있는가.
 *
 * 유튜브 경로는 임베드 재생이라 회선이 없으면 아무것도 들려줄 수 없다. 비행기나 로밍 없는
 * 해외처럼 정작 영어가 필요한 자리에서 이 상태가 된다. 앱이 먼저 알아채고 오프라인 세션을
 * 권해야 한다.
 */
internal class NetworkMonitor(private val context: Context) {

    private val manager: ConnectivityManager?
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** 지금 인터넷에 닿는가. 판단할 수 없으면 닿는다고 본다 — 멀쩡한 회선을 막지 않기 위해. */
    fun isOnline(): Boolean {
        val connectivity = manager ?: return true
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun observe(): Flow<Boolean> = callbackFlow {
        val connectivity = manager
        if (connectivity == null) {
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }

        trySend(isOnline())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(isOnline())
            }

            override fun onLost(network: Network) {
                trySend(isOnline())
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(isOnline())
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity.registerNetworkCallback(request, callback)
        awaitClose { runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
}
