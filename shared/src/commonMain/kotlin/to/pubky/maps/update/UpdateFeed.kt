package to.pubky.maps.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/**
 * The update channels. A build follows the channel it was released on: `edge` builds (every change
 * on main) follow the edge feed, versioned releases the stable feed, manual test builds the dev feed.
 */
enum class UpdateChannel(
    val id: String,
    val label: String,
) {
    Edge("edge", "Edge"),
    Stable("stable", "Stable"),
    Dev("dev", "Dev"),
    ;

    companion object {
        /** The channel with this id, or null for a build that does not update itself (""). */
        fun fromId(id: String): UpdateChannel? = entries.firstOrNull { it.id == id }
    }
}

/** The APK an update feed offers. */
@Serializable
data class FeedApk(
    val url: String,
    val size: Long,
    val sha256: String,
)

/**
 * What an update feed says, exactly as signed. Published next to each release as
 * `update.json` (stable), `update-edge.json` or `update-dev.json` by scripts/release/update-feed.py.
 */
@Serializable
data class UpdateFeed(
    val format: Int,
    val app: String,
    val channel: String,
    val versionCode: Long,
    val versionName: String,
    /** The oldest Android (API level) the APK installs on. */
    val minSdk: Int,
    val apk: FeedApk,
)

/** The file on the server: the feed document as a string and an Ed25519 signature over its UTF-8 bytes. */
@Serializable
private data class SignedFeedFile(
    val feed: String,
    val signature: String,
)

/** Checks an Ed25519 signature; the platform provides it. */
fun interface SignatureVerifier {
    fun verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean
}

/** Why a feed was not accepted. Each one tells the user what happened in plain words. */
enum class FeedProblem(
    val message: String,
) {
    Unreadable("The update information could not be read."),
    BadSignature("The update information is not signed with the Pubky Maps key, so it was ignored."),
    WrongApp("The update information is for another app, so it was ignored."),
    WrongChannel("The update information is for another update channel, so it was ignored."),
    UnknownFormat("The update information is in a newer format. Install the newest version by hand."),
    BadApk("The update information names an unusable download, so it was ignored."),
}

/** The result of reading one feed file. */
sealed interface FeedReadResult {
    data class Valid(
        val feed: UpdateFeed,
    ) : FeedReadResult

    data class Invalid(
        val problem: FeedProblem,
    ) : FeedReadResult
}

const val FEED_FORMAT = 1
private const val MAX_APK_BYTES = 500L * 1024 * 1024
private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
private val json = Json { ignoreUnknownKeys = true }

/**
 * Reads a signed feed file: the signature must verify with one of [trustedKeys] over the exact bytes
 * of the feed document, and the feed must be for [appId] on [channel], in a known format, and name an
 * HTTPS download with a size and a SHA-256. [allowLocalHttp] also accepts plain HTTP to the phone
 * itself (127.0.0.1); only the update probe's build sets it.
 */
fun readSignedFeed(
    file: ByteArray,
    trustedKeys: List<ByteArray>,
    verifier: SignatureVerifier,
    appId: String,
    channel: UpdateChannel,
    allowLocalHttp: Boolean = false,
): FeedReadResult {
    val signed =
        try {
            json.decodeFromString<SignedFeedFile>(file.decodeToString())
        } catch (_: SerializationException) {
            return FeedReadResult.Invalid(FeedProblem.Unreadable)
        } catch (_: IllegalArgumentException) {
            return FeedReadResult.Invalid(FeedProblem.Unreadable)
        }
    val signature =
        try {
            Base64.decode(signed.signature)
        } catch (_: IllegalArgumentException) {
            return FeedReadResult.Invalid(FeedProblem.BadSignature)
        }
    val message = signed.feed.encodeToByteArray()
    val signedByUs = signature.size == 64 && trustedKeys.any { verifier.verify(it, message, signature) }
    if (!signedByUs) return FeedReadResult.Invalid(FeedProblem.BadSignature)

    val feed =
        try {
            json.decodeFromString<UpdateFeed>(signed.feed)
        } catch (_: SerializationException) {
            return FeedReadResult.Invalid(FeedProblem.Unreadable)
        } catch (_: IllegalArgumentException) {
            return FeedReadResult.Invalid(FeedProblem.Unreadable)
        }
    val problem =
        when {
            feed.format != FEED_FORMAT -> FeedProblem.UnknownFormat
            feed.app != appId -> FeedProblem.WrongApp
            feed.channel != channel.id -> FeedProblem.WrongChannel
            feed.versionCode <= 0 || !isAllowedDownload(feed.apk.url, allowLocalHttp) -> FeedProblem.BadApk
            feed.apk.size !in 1..MAX_APK_BYTES || !SHA256_HEX.matches(feed.apk.sha256) -> FeedProblem.BadApk
            else -> null
        }
    return if (problem == null) FeedReadResult.Valid(feed) else FeedReadResult.Invalid(problem)
}

private fun isAllowedDownload(
    url: String,
    allowLocalHttp: Boolean,
): Boolean = url.startsWith("https://") || (allowLocalHttp && url.startsWith("http://127.0.0.1:"))

/** Decodes a base64 public key; the app embeds them as text. */
fun decodePublicKey(base64: String): ByteArray {
    val key = Base64.decode(base64.trim())
    require(key.size == 32) { "an Ed25519 public key has 32 bytes, not ${key.size}" }
    return key
}
