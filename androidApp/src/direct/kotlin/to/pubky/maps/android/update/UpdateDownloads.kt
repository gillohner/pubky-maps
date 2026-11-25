package to.pubky.maps.android.update

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Timer
import kotlin.concurrent.timerTask

private const val TIMEOUT_MS = 30_000
private const val MAX_FEED_BYTES = 64 * 1024
private const val FEED_DEADLINE_MS = 60_000L

/**
 * GETs a small document (an update feed), giving up after [FEED_DEADLINE_MS] in all: connecting,
 * waiting for the answer and reading it. A watchdog closes the connection at the deadline, so neither
 * a server that never answers nor one that sends one byte at a time can hold the check longer.
 * Blocking: call it off the main thread.
 */
fun fetchFeed(url: String): ByteArray {
    val deadline = System.nanoTime() + FEED_DEADLINE_MS * 1_000_000
    val connection = openGet(url)
    val watchdog = Timer("update-feed-deadline", true)
    watchdog.schedule(timerTask { connection.disconnect() }, FEED_DEADLINE_MS)
    try {
        return connection.useBody { input ->
            val bytes = input.readNBytesCompat(MAX_FEED_BYTES + 1, deadline)
            if (bytes.size > MAX_FEED_BYTES) throw IOException("the update feed is larger than $MAX_FEED_BYTES bytes")
            bytes
        }
    } catch (e: IOException) {
        if (System.nanoTime() > deadline) throw IOException("the update server is too slow", e)
        throw e
    } finally {
        watchdog.cancel()
    }
}

/**
 * Downloads [url] to [target], reading at most [expectedSize] + 1 bytes, and reports progress.
 * Returns the bytes written. Blocking: call it off the main thread, in `runInterruptible` so that
 * cancelling stops it (it throws [InterruptedException]).
 */
fun downloadTo(
    url: String,
    target: File,
    expectedSize: Long,
    onProgress: (Long) -> Unit,
): Long =
    openGet(url).useBody { input ->
        var total = 0L
        target.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            var reported = 0L
            while (total <= expectedSize) {
                if (Thread.interrupted()) throw InterruptedException("download cancelled")
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                total += n
                if (total - reported >= 256 * 1024) {
                    reported = total
                    onProgress(total)
                }
            }
        }
        onProgress(total)
        total
    }

/** Why a downloaded APK is not installed, or null when it is the file the signed feed names. */
fun checkDownload(
    file: File,
    expectedSize: Long,
    expectedSha256: String,
): String? {
    val size = file.length()
    if (size != expectedSize) {
        return "The downloaded file has the wrong size ($size instead of $expectedSize bytes). Nothing was installed."
    }
    if (sha256Hex(file) != expectedSha256) {
        return "The downloaded file is not the one the signed update information names (its SHA-256 differs). " +
            "Nothing was installed."
    }
    return null
}

fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun openGet(url: String): HttpURLConnection {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = TIMEOUT_MS
    connection.readTimeout = TIMEOUT_MS
    connection.instanceFollowRedirects = true
    connection.setRequestProperty("Accept", "application/json, application/vnd.android.package-archive, */*")
    return connection
}

private fun <T> HttpURLConnection.useBody(read: (InputStream) -> T): T =
    try {
        val code = responseCode
        if (code !in 200..299) throw IOException("HTTP $code from ${url.host}")
        inputStream.use(read)
    } finally {
        disconnect()
    }

/** Reads up to [limit] bytes (InputStream.readNBytes is API 33), failing once [deadlineNanos] has passed. */
private fun InputStream.readNBytesCompat(
    limit: Int,
    deadlineNanos: Long,
): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (out.size() < limit) {
        if (System.nanoTime() > deadlineNanos) throw IOException("the update server is too slow")
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
