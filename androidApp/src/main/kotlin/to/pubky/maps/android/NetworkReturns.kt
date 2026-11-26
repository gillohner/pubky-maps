package to.pubky.maps.android

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Calls [onBack] when the phone's default network may reach the internet again: when a network
 * becomes the default ([onAvailable]), and when Android has checked that one reaches the internet
 * ([NetworkCapabilities.NET_CAPABILITY_VALIDATED], which often comes a moment after it is the
 * default, so a send tried at [onAvailable] can still fail).
 *
 * Only the change to validated counts: Android reports the capabilities again on every change of
 * signal strength or bandwidth, and each report must not send the writes again.
 */
internal class NetworkReturns(
    private val onBack: () -> Unit,
) : ConnectivityManager.NetworkCallback() {
    // Android calls a callback on one thread, in order.
    private var validated: Set<Network> = emptySet()

    override fun onAvailable(network: Network) = onBack()

    override fun onCapabilitiesChanged(
        network: Network,
        networkCapabilities: NetworkCapabilities,
    ) {
        if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            if (network in validated) return
            validated = validated + network
            onBack()
        } else {
            validated = validated - network
        }
    }

    override fun onLost(network: Network) {
        validated = validated - network
    }
}
