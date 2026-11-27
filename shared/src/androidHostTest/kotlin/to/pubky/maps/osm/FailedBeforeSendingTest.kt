package to.pubky.maps.osm

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which failures of Android's HTTP stack leave a note on no server, so it may wait and go again. */
class FailedBeforeSendingTest {
    @Test
    fun noNameNoRouteARefusedConnectionOrAFailedHandshakeSentNothing() {
        assertTrue(failedBeforeSending(UnknownHostException("api.openstreetmap.org")))
        assertTrue(failedBeforeSending(ConnectException("refused")))
        assertTrue(failedBeforeSending(NoRouteToHostException("no route")))
        assertTrue(failedBeforeSending(SSLHandshakeException("bad certificate")))
    }

    @Test
    fun aPlainSocketTimeoutIsTheConnectTimeoutOfANote() {
        // Ktor's Android engine connects a request with a body before it wraps timeouts.
        assertTrue(failedBeforeSending(SocketTimeoutException("failed to connect after 5000ms")))
    }

    @Test
    fun aConnectionThatBrokeAfterConnectingMayHaveSentTheNote() {
        assertFalse(failedBeforeSending(SocketException("Connection reset")))
        assertFalse(failedBeforeSending(IOException("unexpected end of stream")))
    }
}
