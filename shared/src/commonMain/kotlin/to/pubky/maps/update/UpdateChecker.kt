package to.pubky.maps.update

import kotlinx.coroutines.CancellationException

/** The installed build, as far as updates are concerned. */
data class InstalledBuild(
    val appId: String,
    val versionCode: Long,
    val versionName: String,
    val sdkInt: Int,
    val channel: UpdateChannel,
)

/** What a check found. */
sealed interface CheckResult {
    /**
     * A newer build for this phone. [signedFile] is the signed feed file it was read from, which the app
     * keeps to offer the build again after a restart ([UpdateChecker.reread] checks it again).
     */
    data class Available(
        val feed: UpdateFeed,
        val signedFile: String,
    ) : CheckResult

    /** Nothing newer: the feed names this build or an older one (never offered: no downgrades). */
    data class UpToDate(
        val newestVersionName: String,
    ) : CheckResult

    /** A newer build exists, but it needs a newer Android than this phone has. */
    data class NeedsNewerAndroid(
        val feed: UpdateFeed,
    ) : CheckResult

    /** Every server answered with a feed that was not accepted; nothing is offered. */
    data class Refused(
        val problem: FeedProblem,
    ) : CheckResult

    /** No server could be reached. */
    data object Unreachable : CheckResult
}

/**
 * Looks for an update: asks every feed server and takes the newest feed that is signed with a trusted
 * key and valid for this build, so a stale mirror early in the list cannot hide a newer build. A server
 * that cannot be reached or serves a feed that is not accepted is skipped.
 */
class UpdateChecker(
    private val fetch: suspend (url: String) -> ByteArray,
    private val trustedKeys: List<ByteArray>,
    private val verifier: SignatureVerifier,
    private val allowLocalHttp: Boolean = false,
) {
    suspend fun check(
        feedUrls: List<String>,
        installed: InstalledBuild,
    ): CheckResult {
        var newest: UpdateFeed? = null
        var newestFile = ""
        var refusal: FeedProblem? = null
        for (url in feedUrls) {
            val bytes =
                try {
                    fetch(url)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
            val read = readSignedFeed(bytes, trustedKeys, verifier, installed.appId, installed.channel, allowLocalHttp)
            when (read) {
                is FeedReadResult.Valid -> {
                    if (newest == null || read.feed.versionCode > newest.versionCode) {
                        newest = read.feed
                        newestFile = bytes.decodeToString()
                    }
                }

                is FeedReadResult.Invalid -> {
                    refusal = refusal ?: read.problem
                }
            }
        }
        return newest?.let { decide(it, installed, newestFile) }
            ?: refusal?.let { CheckResult.Refused(it) }
            ?: CheckResult.Unreachable
    }

    /**
     * Reads a signed feed file a check accepted before ([CheckResult.Available.signedFile]) again, without
     * the network: with the same keys and rules as a fresh one, and decided against the build installed
     * now, so a kept update is offered only while it is still validly signed and newer than this build.
     */
    fun reread(
        signedFile: String,
        installed: InstalledBuild,
    ): CheckResult =
        when (
            val read =
                readSignedFeed(
                    signedFile.encodeToByteArray(),
                    trustedKeys,
                    verifier,
                    installed.appId,
                    installed.channel,
                    allowLocalHttp,
                )
        ) {
            is FeedReadResult.Valid -> decide(read.feed, installed, signedFile)
            is FeedReadResult.Invalid -> CheckResult.Refused(read.problem)
        }
}

/** What a valid feed, read from [signedFile], means for the installed build. */
fun decide(
    feed: UpdateFeed,
    installed: InstalledBuild,
    signedFile: String,
): CheckResult =
    when {
        feed.versionCode <= installed.versionCode -> CheckResult.UpToDate(feed.versionName)
        feed.minSdk > installed.sdkInt -> CheckResult.NeedsNewerAndroid(feed)
        else -> CheckResult.Available(feed, signedFile)
    }

/** Automatic checks run at most once a day. */
const val AUTO_CHECK_INTERVAL_MS: Long = 24L * 60 * 60 * 1000

/**
 * Whether the start-up check is due: automatic checks are on, and the last check that reached a
 * server is a day old, or never happened, or lies in the future (the clock was moved back).
 */
fun isAutoCheckDue(
    enabled: Boolean,
    lastCheckMs: Long?,
    nowMs: Long,
): Boolean = enabled && (lastCheckMs == null || nowMs < lastCheckMs || nowMs - lastCheckMs >= AUTO_CHECK_INTERVAL_MS)
