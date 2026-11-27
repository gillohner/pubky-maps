package to.pubky.maps.osm

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * The failures of Android's HttpURLConnection that come before any byte of the request goes out: the
 * name not found (no network, or no DNS), the connection refused or with no route, the TLS handshake
 * failed, or connecting timed out. Ktor's Android engine opens a note's connection (to write its form)
 * before it wraps timeouts, so a timeout of connecting or of the TLS handshake comes as a plain
 * [SocketTimeoutException]. Once the form went out, a plain one cannot come: [osmHttpClient] sets the
 * read timeout longer than the whole request may take, and the engine closes the connection when the
 * request times out. A reset after connecting is not one of them: the server may have the note.
 */
internal actual fun failedBeforeSending(e: Throwable): Boolean =
    e is UnknownHostException ||
        e is ConnectException ||
        e is NoRouteToHostException ||
        e is SSLHandshakeException ||
        e is SocketTimeoutException
