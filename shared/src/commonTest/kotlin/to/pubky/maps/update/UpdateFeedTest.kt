package to.pubky.maps.update

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import to.pubky.maps.config.ServerDefaults
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Feed parsing, the checks on a feed, channel selection, downgrades, server fallback and the daily
 * check. The signature scheme here is a stand-in; real Ed25519 is tested in androidApp (testDirect).
 */
class UpdateFeedTest {
    private val key = ByteArray(32) { it.toByte() }
    private val otherKey = ByteArray(32) { (it + 1).toByte() }

    /** A deterministic stand-in for Ed25519: the signature is a digest of key and message. */
    private fun fakeSign(
        key: ByteArray,
        message: ByteArray,
    ): ByteArray {
        val h = message.contentHashCode() * 31 + key.contentHashCode()
        return ByteArray(64) { i -> (h ushr (i % 4) * 8).toByte() }
    }

    private val verifier = SignatureVerifier { k, m, s -> s.contentEquals(fakeSign(k, m)) }

    private val goodFeed =
        buildJsonObject {
            put("format", JsonPrimitive(1))
            put("app", JsonPrimitive("to.pubky.maps"))
            put("channel", JsonPrimitive("edge"))
            put("versionCode", JsonPrimitive(1_000_012))
            put("versionName", JsonPrimitive("0.1.0-edge.12"))
            put("minSdk", JsonPrimitive(26))
            put("published", JsonPrimitive("2026-11-25T03:00:00Z"))
            put(
                "apk",
                buildJsonObject {
                    put("url", JsonPrimitive("https://github.com/gillohner/pubky-maps/releases/download/edge/pubky-maps.apk"))
                    put("size", JsonPrimitive(14_200_000))
                    put("sha256", JsonPrimitive("a".repeat(64)))
                },
            )
        }

    private fun JsonObject.with(
        key: String,
        value: JsonPrimitive,
    ) = JsonObject(this + (key to value))

    private fun JsonObject.withApk(
        key: String,
        value: JsonPrimitive,
    ) = JsonObject(this + ("apk" to this.getValue("apk").jsonObject.with(key, value)))

    private fun signedFile(
        feed: String,
        signature: ByteArray = fakeSign(key, feed.encodeToByteArray()),
    ): ByteArray =
        buildJsonObject {
            put("feed", JsonPrimitive(feed))
            put("signature", JsonPrimitive(Base64.encode(signature)))
        }.toString().encodeToByteArray()

    private fun read(
        file: ByteArray,
        channel: UpdateChannel = UpdateChannel.Edge,
        keys: List<ByteArray> = listOf(key),
        allowLocalHttp: Boolean = false,
    ) = readSignedFeed(file, keys, verifier, "to.pubky.maps", channel, allowLocalHttp)

    private fun installed(
        versionCode: Long,
        sdk: Int = 35,
    ) = InstalledBuild("to.pubky.maps", versionCode, "0.1.0-edge.$versionCode", sdk, UpdateChannel.Edge)

    @Test
    fun readsASignedFeed() {
        val result = read(signedFile(goodFeed.toString()))
        val feed = assertIs<FeedReadResult.Valid>(result).feed
        assertEquals(1_000_012, feed.versionCode)
        assertEquals("0.1.0-edge.12", feed.versionName)
        assertEquals(14_200_000, feed.apk.size)
        assertEquals(26, feed.minSdk)
    }

    @Test
    fun acceptsFieldsItDoesNotKnow() {
        val feed = goodFeed.with("notes", JsonPrimitive("Faster search")).toString()
        assertIs<FeedReadResult.Valid>(read(signedFile(feed)))
    }

    @Test
    fun refusesAChangedFeed() {
        val signature = fakeSign(key, goodFeed.toString().encodeToByteArray())
        val changed = goodFeed.with("versionCode", JsonPrimitive(1_000_013)).toString()
        assertEquals(FeedReadResult.Invalid(FeedProblem.BadSignature), read(signedFile(changed, signature)))
    }

    @Test
    fun refusesAChangedApkHash() {
        val signature = fakeSign(key, goodFeed.toString().encodeToByteArray())
        val changed = goodFeed.withApk("sha256", JsonPrimitive("b".repeat(64))).toString()
        assertEquals(FeedReadResult.Invalid(FeedProblem.BadSignature), read(signedFile(changed, signature)))
    }

    @Test
    fun refusesAFeedSignedWithAnotherKey() {
        val feed = goodFeed.toString()
        val file = signedFile(feed, fakeSign(otherKey, feed.encodeToByteArray()))
        assertEquals(FeedReadResult.Invalid(FeedProblem.BadSignature), read(file))
        // ...unless the app trusts that key too (a key change ships both keys for a while).
        assertIs<FeedReadResult.Valid>(read(file, keys = listOf(key, otherKey)))
    }

    @Test
    fun refusesUnreadableFiles() {
        assertEquals(FeedReadResult.Invalid(FeedProblem.Unreadable), read("not json".encodeToByteArray()))
        assertEquals(FeedReadResult.Invalid(FeedProblem.Unreadable), read("""{"feed":"x"}""".encodeToByteArray()))
        val badBase64 = """{"feed":"{}","signature":"%%%"}""".encodeToByteArray()
        assertEquals(FeedReadResult.Invalid(FeedProblem.BadSignature), read(badBase64))
        // Signed, but not a feed.
        assertEquals(FeedReadResult.Invalid(FeedProblem.Unreadable), read(signedFile("""{"format":1}""")))
    }

    @Test
    fun selectsTheBuildsChannel() {
        val edgeFeed = signedFile(goodFeed.toString())
        assertIs<FeedReadResult.Valid>(read(edgeFeed, UpdateChannel.Edge))
        assertEquals(FeedReadResult.Invalid(FeedProblem.WrongChannel), read(edgeFeed, UpdateChannel.Stable))
        assertEquals(FeedReadResult.Invalid(FeedProblem.WrongChannel), read(edgeFeed, UpdateChannel.Dev))
        val stableFeed = signedFile(goodFeed.with("channel", JsonPrimitive("stable")).toString())
        assertIs<FeedReadResult.Valid>(read(stableFeed, UpdateChannel.Stable))
    }

    @Test
    fun channelIdsAndFeedServers() {
        assertEquals(UpdateChannel.Edge, UpdateChannel.fromId("edge"))
        assertEquals(UpdateChannel.Stable, UpdateChannel.fromId("stable"))
        assertEquals(UpdateChannel.Dev, UpdateChannel.fromId("dev"))
        assertEquals(null, UpdateChannel.fromId(""))
        val feeds = ServerDefaults.updateFeeds
        assertEquals(
            "https://github.com/gillohner/pubky-maps/releases/download/edge/update-edge.json",
            feeds.forChannel(UpdateChannel.Edge).first(),
        )
        assertEquals(
            "https://github.com/gillohner/pubky-maps/releases/latest/download/update.json",
            feeds.forChannel(UpdateChannel.Stable).first(),
        )
        assertTrue(feeds.forChannel(UpdateChannel.Dev).first().endsWith("/download/dev/update-dev.json"))
        val mirrored = feeds.withChannel(UpdateChannel.Edge, listOf("https://mirror.example/update-edge.json"))
        assertEquals(listOf("https://mirror.example/update-edge.json"), mirrored.forChannel(UpdateChannel.Edge))
        assertEquals(feeds.stable, mirrored.stable)
    }

    @Test
    fun refusesFeedsForOtherAppsAndFormats() {
        val otherApp = signedFile(goodFeed.with("app", JsonPrimitive("to.pubky.other")).toString())
        assertEquals(FeedReadResult.Invalid(FeedProblem.WrongApp), read(otherApp))
        val newer = signedFile(goodFeed.with("format", JsonPrimitive(2)).toString())
        assertEquals(FeedReadResult.Invalid(FeedProblem.UnknownFormat), read(newer))
    }

    @Test
    fun refusesUnusableDownloads() {
        val cases =
            listOf(
                goodFeed.withApk("url", JsonPrimitive("http://example.org/pubky-maps.apk")),
                goodFeed.withApk("url", JsonPrimitive("file:///sdcard/pubky-maps.apk")),
                goodFeed.withApk("size", JsonPrimitive(0)),
                goodFeed.withApk("sha256", JsonPrimitive("A".repeat(64))),
                goodFeed.withApk("sha256", JsonPrimitive("a".repeat(63))),
                goodFeed.with("versionCode", JsonPrimitive(0)),
            )
        for (feed in cases) {
            assertEquals(FeedReadResult.Invalid(FeedProblem.BadApk), read(signedFile(feed.toString())), feed.toString())
        }
        // The update probe's fixture server on the phone itself, only in the probe's build.
        val local = signedFile(goodFeed.withApk("url", JsonPrimitive("http://127.0.0.1:18480/pubky-maps.apk")).toString())
        assertEquals(FeedReadResult.Invalid(FeedProblem.BadApk), read(local))
        assertIs<FeedReadResult.Valid>(read(local, allowLocalHttp = true))
    }

    @Test
    fun offersOnlyNewerBuilds() {
        val feed = (read(signedFile(goodFeed.toString())) as FeedReadResult.Valid).feed
        assertIs<CheckResult.Available>(decide(feed, installed(1_000_011)))
        assertEquals(CheckResult.UpToDate("0.1.0-edge.12"), decide(feed, installed(1_000_012)))
        // A feed older than the installed build is never offered: no downgrades.
        assertEquals(CheckResult.UpToDate("0.1.0-edge.12"), decide(feed, installed(1_000_013)))
        assertIs<CheckResult.NeedsNewerAndroid>(decide(feed.copy(minSdk = 36), installed(1_000_011, sdk = 35)))
    }

    @Test
    fun fallsBackToTheNextServer() =
        runTest {
            val good = signedFile(goodFeed.toString())
            val forged = signedFile(goodFeed.toString(), ByteArray(64))
            val responses =
                mapOf(
                    "https://down.example/f" to null,
                    "https://forged.example/f" to forged,
                    "https://good.example/f" to good,
                )
            val checker =
                UpdateChecker(
                    fetch = { url -> responses.getValue(url) ?: throw IllegalStateException("unreachable") },
                    trustedKeys = listOf(key),
                    verifier = verifier,
                )
            val all = listOf("https://down.example/f", "https://forged.example/f", "https://good.example/f")
            assertIs<CheckResult.Available>(checker.check(all, installed(1)))
            assertEquals(
                CheckResult.Refused(FeedProblem.BadSignature),
                checker.check(listOf("https://down.example/f", "https://forged.example/f"), installed(1)),
            )
            assertEquals(CheckResult.Unreachable, checker.check(listOf("https://down.example/f"), installed(1)))
            assertEquals(CheckResult.Unreachable, checker.check(emptyList(), installed(1)))
        }

    @Test
    fun takesTheNewestFeedOfAllServers() =
        runTest {
            val stale = signedFile(goodFeed.with("versionCode", JsonPrimitive(1_000_005)).toString())
            val newest = signedFile(goodFeed.toString())
            val responses = mapOf("https://stale.example/f" to stale, "https://new.example/f" to newest)
            val checker = UpdateChecker(fetch = { responses.getValue(it) }, trustedKeys = listOf(key), verifier = verifier)
            val result = checker.check(listOf("https://stale.example/f", "https://new.example/f"), installed(1_000_010))
            assertEquals(1_000_012, assertIs<CheckResult.Available>(result).feed.versionCode)
            // A server that hangs or fails does not hide the others either.
            val failing = UpdateChecker(fetch = { if (it.contains("stale")) error("timed out") else newest }, listOf(key), verifier)
            assertIs<CheckResult.Available>(failing.check(listOf("https://stale.example/f", "https://new.example/f"), installed(1)))
        }

    @Test
    fun checksAutomaticallyAtMostOnceADay() {
        val day = AUTO_CHECK_INTERVAL_MS
        val now = 100 * day
        assertTrue(isAutoCheckDue(enabled = true, lastCheckMs = null, nowMs = now))
        assertFalse(isAutoCheckDue(enabled = true, lastCheckMs = now - day + 1, nowMs = now))
        assertTrue(isAutoCheckDue(enabled = true, lastCheckMs = now - day, nowMs = now))
        assertTrue(isAutoCheckDue(enabled = true, lastCheckMs = now + 1000, nowMs = now))
        assertFalse(isAutoCheckDue(enabled = false, lastCheckMs = null, nowMs = now))
    }

    @Test
    fun statusWords() {
        assertEquals("14.2 MB", formatMegabytes(14_200_000))
        assertEquals("0.0 MB", formatMegabytes(0))
        assertEquals("1.0 MB", formatMegabytes(999_999))
        assertEquals(
            UpdateStatus.Failed(FeedProblem.BadSignature.message),
            CheckResult.Refused(FeedProblem.BadSignature).toStatus(),
        )
        val feed = Json { ignoreUnknownKeys = true }.decodeFromString<UpdateFeed>(goodFeed.toString())
        assertEquals(
            UpdateStatus.Available(AvailableUpdate(1_000_012, "0.1.0-edge.12", 14_200_000)),
            CheckResult.Available(feed).toStatus(),
        )
    }
}
