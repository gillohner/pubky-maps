package to.pubky.maps.account

/**
 * The Rust core (Pubky SDK) as the account code uses it. On Android it wraps the UniFFI bindings
 * ([UniffiPubkyCore]); tests use a fake. Every `suspend` function may do network calls; the others
 * never do.
 */
interface PubkyCore {
    /** Starts a Pubky Ring sign-in and returns its link. A running sign-in is replaced. */
    fun startSignIn(): SignInLink

    /** Continues a sign-in after the process was killed, from [SignInLink.pendingState]. */
    fun resumeSignIn(pendingState: String): SignInLink

    /**
     * Waits for Ring's approval: [SignInStep.SignedIn], or [SignInStep.RelayChanged] when the sign-in
     * moved to the next relay with a new link. Throws [CoreFailure]; [CoreFailure.Cancelled] after
     * [cancelSignIn]. Cancelling the coroutine is safe: an approval that arrived is kept for the next
     * call.
     */
    suspend fun awaitSignIn(): SignInStep

    fun cancelSignIn()

    /** What Ring's return link [url] says about the sign-in with [nonce], or null when it is not one. */
    fun ringCallback(
        url: String,
        nonce: String,
    ): RingCallback?

    /** The session a sign-in installed although its result never arrived here, with its secret. */
    suspend fun installedSession(): SignInStep.SignedIn?

    /** Restores the session of a stored secret. [CoreFailure.Network]: retry later; [CoreFailure.Auth]: it ended. */
    suspend fun restoreSession(secret: String): AccountSession

    /** Revokes the session on the homeserver. True when it was revoked, false when there was none here. */
    suspend fun signOut(): Boolean

    /** Drops the session on this phone only. */
    fun forgetSession()

    /** A public file of any user, `pubky://<key>/pub/...`. */
    suspend fun publicGet(uri: String): ByteArray

    /** A file of the signed-in user, `/pub/...` or `/priv/...`. */
    suspend fun get(path: String): ByteArray

    /** One page of the signed-in user's folder [dir]; the last page has no [Page.nextCursor]. */
    suspend fun list(
        dir: String,
        cursor: String?,
    ): Page<String>

    /** One page of the signed-in user's saved places (`/priv/social/v1/bookmarks/`). */
    suspend fun listBookmarks(cursor: String?): Page<SavedPlace>
}

/**
 * A running sign-in. [url] is the `pubkyauth://` link for Ring and the QR code; it and
 * [pendingState] hold secrets of the flow, so neither is ever logged ([toString] hides them).
 */
class SignInLink(
    val url: String,
    val nonce: String,
    val pendingState: String,
) {
    override fun toString(): String = "SignInLink(<redacted>)"
}

sealed interface SignInStep {
    class RelayChanged(
        val link: SignInLink,
    ) : SignInStep

    /** Signed in. [secret] is the session's bearer credential: store it encrypted, never log it. */
    class SignedIn(
        val session: AccountSession,
        val secret: String,
    ) : SignInStep {
        override fun toString(): String = "SignedIn($session, secret=<redacted>)"
    }
}

/** What Ring's return link reports. */
enum class RingCallback { Success, Error, Cancel }

/** A signed-in session: the user's public key (z-base-32) and their homeserver's. */
data class AccountSession(
    val publicKey: String,
    val homeserver: String?,
)

data class Page<T>(
    val items: List<T>,
    val nextCursor: String?,
)

/** A saved place: the place's URI and the bookmark's path on the homeserver. */
data class SavedPlace(
    val place: String,
    val path: String,
)

/** Why a core call failed. [reason] never holds a secret (the core redacts them). */
sealed class CoreFailure(
    val reason: String,
) : Exception(reason) {
    /**
     * A server could not be reached, or answered with a temporary error: retry later. [busy] when it
     * was reached and is throttling or failing (429, 5xx), so the phone is not offline.
     */
    class Network(
        reason: String,
        val busy: Boolean = false,
    ) : CoreFailure(reason)

    /** The session or sign-in is no longer valid: sign in again. */
    class Auth(
        reason: String,
    ) : CoreFailure(reason)

    class NotFound(
        reason: String,
    ) : CoreFailure(reason)

    /** The sign-in was cancelled or replaced. */
    class Cancelled(
        reason: String,
    ) : CoreFailure(reason)

    class Other(
        reason: String,
    ) : CoreFailure(reason)
}
