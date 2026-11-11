package dev.ghar.watch.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Wear OS turns Wi-Fi off when nobody asks for it. Uploads must explicitly request a Wi-Fi network,
 * use it, then release it so the radio can sleep again (battery).
 */
object WifiNet {
    class Lease(val network: Network, private val release: () -> Unit) {
        fun close() = release()
    }

    suspend fun acquire(ctx: Context, timeoutMs: Long = 45_000): Lease? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        var callback: ConnectivityManager.NetworkCallback? = null
        val net = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Network> { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(network)
                    }
                }
                callback = cb
                cm.requestNetwork(req, cb)
            }
        }
        val cb = callback
        if (net == null) {
            if (cb != null) runCatching { cm.unregisterNetworkCallback(cb) }
            return null
        }
        return Lease(net) { if (cb != null) runCatching { cm.unregisterNetworkCallback(cb) } }
    }
}
