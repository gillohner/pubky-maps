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
    // The default network as last validated. A default-network callback hears only about the
    // current default (Android calls it on one thread, in order), so a new default starts over.
    private var validated: Network? = null

    override fun onAvailable(network: Network) {
        validated = null
        onBack()
    }

    override fun onCapabilitiesChanged(
        network: Network,
        networkCapabilities: NetworkCapabilities,
    ) {
        if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            if (network == validated) return
            validated = network
            onBack()
        } else if (network == validated) {
            validated = null
        }
    }

    override fun onLost(network: Network) {
        if (network == validated) validated = null
    }
}
