package to.pubky.maps.android

import android.app.Application
import android.net.NetworkCapabilities
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/** The writes are sent again when a network returns, not on every report about the same network. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NetworkReturnsTest {
    private var calls = 0
    private val returns = NetworkReturns { calls++ }
    private val wifi = ShadowNetwork.newInstance(100)
    private val mobile = ShadowNetwork.newInstance(101)

    private fun capabilities(validated: Boolean): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also { capabilities ->
            shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (validated) shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

    @Test
    fun aNewDefaultNetworkAndItsValidationEachSendAgain() {
        returns.onAvailable(wifi)
        assertEquals(1, calls)

        returns.onCapabilitiesChanged(wifi, capabilities(validated = false))
        assertEquals("not yet checked by Android: nothing to send on", 1, calls)

        returns.onCapabilitiesChanged(wifi, capabilities(validated = true))
        assertEquals("Android found it reaches the internet", 2, calls)
    }

    @Test
    fun moreReportsAboutAValidatedNetworkSendNothing() {
        returns.onAvailable(wifi)
        returns.onCapabilitiesChanged(wifi, capabilities(validated = true))
        assertEquals(2, calls)

        repeat(5) { returns.onCapabilitiesChanged(wifi, capabilities(validated = true)) }
        assertEquals("signal strength and bandwidth reports", 2, calls)
    }

    @Test
    fun aNetworkThatLostItsInternetAndGotItBackSendsAgain() {
        returns.onCapabilitiesChanged(wifi, capabilities(validated = true))
        returns.onCapabilitiesChanged(wifi, capabilities(validated = false))
        returns.onCapabilitiesChanged(wifi, capabilities(validated = true))
        assertEquals(2, calls)
    }

    @Test
    fun aNetworkThatIsLostAndComesBackSendsAgain() {
        returns.onCapabilitiesChanged(wifi, capabilities(validated = true))
        returns.onLost(wifi)
        returns.onCapabilitiesChanged(wifi, capabilities(validated = true))
        assertEquals(2, calls)
    }

    @Test
    fun aSwitchToAnotherNetworkSendsAgain() {
        returns.onCapabilitiesChanged(wifi, capabilities(validated = true))
        returns.onAvailable(mobile)
        returns.onCapabilitiesChanged(mobile, capabilities(validated = true))
        assertEquals(3, calls)
    }

    @Test
    fun aNetworkThatIsTheDefaultAgainSendsWhenItIsValidatedAgain() {
        // Wi-Fi validated, then mobile data is the default; Wi-Fi may lose its internet meanwhile
        // without this callback hearing of it, and is checked again when it is the default again.
        returns.onCapabilitiesChanged(wifi, capabilities(validated = true))
        returns.onAvailable(mobile)
        returns.onCapabilitiesChanged(mobile, capabilities(validated = true))
        returns.onAvailable(wifi)
        returns.onCapabilitiesChanged(wifi, capabilities(validated = true))
        assertEquals(5, calls)
    }
}
