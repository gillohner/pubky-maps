package to.pubky.maps.android.update

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import to.pubky.maps.update.CheckResult
import to.pubky.maps.update.FeedProblem
import to.pubky.maps.update.FeedReadResult
import to.pubky.maps.update.InstalledBuild
import to.pubky.maps.update.UpdateChannel
import to.pubky.maps.update.decide
import to.pubky.maps.update.decodePublicKey
import to.pubky.maps.update.readSignedFeed
import java.io.File
import java.security.SecureRandom

/**
 * The real signature check: a feed written and signed by scripts/release/update-feed.py (OpenSSL,
 * throwaway key; resources/update/) verified with Bouncy Castle as the app does it, and the check on
 * the downloaded APK.
 */
class SignedFeedTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/update/$name")!!.readBytes()

    private val feedFile = resource("update-edge.json")
    private val fixtureKey = decodePublicKey(resource("fixture-key.pub").decodeToString())
    private val appKey = decodePublicKey(File("../release/update-feed-key.pub").readText())

    private fun read(
        file: ByteArray,
        keys: List<ByteArray> = listOf(fixtureKey),
    ) = readSignedFeed(file, keys, Ed25519Verifier, "to.pubky.maps", UpdateChannel.Edge)

    /** The feed file with its feed document edited and the signature kept. */
    private fun tampered(edit: (String) -> String): ByteArray {
        val text = feedFile.decodeToString()
        val feed = Regex("\"feed\": \"(.*)\",\n").find(text)!!.groupValues[1]
        return text.replace(feed, edit(feed)).encodeToByteArray()
    }

    @Test
    fun acceptsTheFeedSignedByTheReleaseTool() {
        val feed = (read(feedFile) as FeedReadResult.Valid).feed
        assertEquals(1_000_012L, feed.versionCode)
        assertEquals("0.1.0-edge.12", feed.versionName)
        assertEquals(43L, feed.apk.size)
    }

    @Test
    fun refusesATamperedFeed() {
        val file = tampered { it.replace("0.1.0-edge.12", "0.1.0-edge.99") }
        assertEquals(FeedReadResult.Invalid(FeedProblem.BadSignature), read(file))
    }

    @Test
    fun refusesATamperedApkHash() {
        val file = tampered { it.replace(Regex("sha256\\\\\":\\\\\"[0-9a-f]{64}"), "sha256\\\\\":\\\\\"${"0".repeat(64)}") }
        assertTrue(file.decodeToString().contains("0".repeat(64)))
        assertEquals(FeedReadResult.Invalid(FeedProblem.BadSignature), read(file))
    }

    @Test
    fun refusesTheWrongKey() {
        // The app's real key did not sign the fixture.
        assertEquals(FeedReadResult.Invalid(FeedProblem.BadSignature), read(feedFile, keys = listOf(appKey)))
        assertEquals(32, appKey.size)
    }

    @Test
    fun refusesADowngrade() {
        val feed = (read(feedFile) as FeedReadResult.Valid).feed
        val newer = InstalledBuild("to.pubky.maps", 1_000_020, "0.1.0-edge.20", 35, UpdateChannel.Edge)
        assertEquals(CheckResult.UpToDate("0.1.0-edge.12"), decide(feed, newer, feedFile.decodeToString()))
    }

    @Test
    fun verifiesBouncyCastleSignaturesAndNothingElse() {
        val private = Ed25519PrivateKeyParameters(SecureRandom())
        val public = private.generatePublicKey().encoded
        val message = "the exact bytes".encodeToByteArray()
        val signature =
            Ed25519Signer().run {
                init(true, private)
                update(message, 0, message.size)
                generateSignature()
            }
        assertTrue(Ed25519Verifier.verify(public, message, signature))
        assertFalse(Ed25519Verifier.verify(public, "the exact bytes!".encodeToByteArray(), signature))
        assertFalse(Ed25519Verifier.verify(fixtureKey, message, signature))
        assertFalse(Ed25519Verifier.verify(public, message, signature.copyOf(63)))
        assertFalse(Ed25519Verifier.verify(public.copyOf(31), message, signature))
        val flipped = signature.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertFalse(Ed25519Verifier.verify(public, message, flipped))
    }

    @Test
    fun checksTheDownloadedApk() {
        val apk = resource("fixture-apk.bin")
        val feed = (read(feedFile) as FeedReadResult.Valid).feed
        val good = tmp.newFile("good.apk").apply { writeBytes(apk) }
        assertNull(checkDownload(good, feed.apk.size, feed.apk.sha256))

        val flipped = apk.copyOf().also { it[apk.size / 2] = (it[apk.size / 2].toInt() xor 0xFF).toByte() }
        val tamperedApk = tmp.newFile("tampered.apk").apply { writeBytes(flipped) }
        assertTrue(checkDownload(tamperedApk, feed.apk.size, feed.apk.sha256)!!.contains("SHA-256"))

        val short = tmp.newFile("short.apk").apply { writeBytes(apk.copyOf(apk.size - 1)) }
        assertTrue(checkDownload(short, feed.apk.size, feed.apk.sha256)!!.contains("wrong size"))
    }
}
