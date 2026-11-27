package to.pubky.maps.osm

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import to.pubky.maps.links.GeoPoint
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Notes on Ktor's real Android engine (the JDK's HttpURLConnection on this machine), against servers
 * on this machine: which failures leave the note on no server, so it may wait and go to the next one.
 */
class AndroidEngineOsmNotesTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private val pin = NoteTarget(GeoPoint(47.37689, 8.54169))
    private val closeAfter = mutableListOf<AutoCloseable>()

    @AfterTest
    fun cleanUp() {
        closeAfter.asReversed().forEach { runCatching { it.close() } }
    }

    private fun notes(vararg servers: String): HttpOsmNotes {
        val client = osmHttpClient("test", connectTimeoutMs = 500, requestTimeoutMs = 2_000).also(closeAfter::add)
        return HttpOsmNotes(client) { servers.toList() }
    }

    private fun url(port: Int) = "http://${loopback.hostAddress}:$port"

    /** A port nothing listens on: the connection is refused. */
    private fun closedPort(): Int = ServerSocket(0, 1, loopback).use { it.localPort }

    /** A server whose queue of connections is full, so a new connection is never taken and times out. */
    private fun fullPort(): Int {
        val server = ServerSocket(0, 1, loopback).also(closeAfter::add)
        val address = InetSocketAddress(loopback, server.localPort)
        repeat(16) {
            val socket = Socket().also(closeAfter::add)
            try {
                socket.connect(address, 200)
            } catch (e: SocketTimeoutException) {
                return server.localPort
            }
        }
        error("the queue of connections never filled")
    }

    /** A server that takes the connection and the request and never answers. */
    private fun silentPort(): Int {
        val server = ServerSocket(0, 50, loopback).also(closeAfter::add)
        thread(isDaemon = true) {
            runCatching {
                while (true) {
                    val socket = server.accept().also(closeAfter::add)
                    thread(isDaemon = true) { runCatching { socket.getInputStream().readBytes() } }
                }
            }
        }
        return server.localPort
    }

    /** The OSM API's answer to a new note. */
    private fun apiPort(): Int {
        val server = HttpServer.create(InetSocketAddress(loopback, 0), 0)
        server.createContext("/api/0.6/notes.json") { exchange ->
            exchange.requestBody.readBytes()
            val body = """{"type":"Feature","properties":{"id":1}}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        closeAfter.add(AutoCloseable { server.stop(0) })
        return server.address.port
    }

    @Test
    fun aRefusedConnectionReachedNoServer() {
        assertEquals(NoteAnswer.Unreachable, runBlocking { notes(url(closedPort())).create(pin, "A bench is missing here.") })
    }

    @Test
    fun aConnectTimeoutReachedNoServerAndHandsOverToTheNext() {
        val full = fullPort()
        assertEquals(NoteAnswer.Unreachable, runBlocking { notes(url(full)).create(pin, "A bench is missing here.") })
        assertEquals(NoteAnswer.Sent, runBlocking { notes(url(full), url(apiPort())).create(pin, "A bench is missing here.") })
    }

    @Test
    fun aServerThatTookTheNoteAndNeverAnsweredMayHaveIt() {
        assertEquals(
            NoteAnswer.NoAnswer,
            runBlocking { notes(url(silentPort()), url(apiPort())).create(pin, "A bench is missing here.") },
        )
    }
}
