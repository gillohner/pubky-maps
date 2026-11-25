package to.pubky.maps.account

import to.pubky.maps.core.AuthCallback
import to.pubky.maps.core.AuthRequest
import to.pubky.maps.core.CoreConfig
import to.pubky.maps.core.CoreException
import to.pubky.maps.core.ListOptions
import to.pubky.maps.core.PubkyMapsCore
import to.pubky.maps.core.SecretValue
import to.pubky.maps.core.SessionInfo
import to.pubky.maps.core.SignInEvent
import to.pubky.maps.core.defaultCoreConfig
import to.pubky.maps.core.parseAuthCallback
import to.pubky.maps.core.use

/** Which servers the core uses, each list in the order to try (the user's server lists). */
data class CoreServers(
    val pkarrRelays: List<String>,
    val authRelays: List<String>,
)

/** Builds the core (its client is built on the core's own thread). Throws [CoreFailure]. */
suspend fun createUniffiPubkyCore(servers: CoreServers): PubkyCore {
    val config: CoreConfig = defaultCoreConfig()
    config.pkarrRelays = servers.pkarrRelays
    config.authRelayUrls = servers.authRelays
    return UniffiPubkyCore(mapped { PubkyMapsCore.create(config) })
}

/** [PubkyCore] on the Rust core's UniFFI bindings. */
class UniffiPubkyCore(
    private val core: PubkyMapsCore,
) : PubkyCore {
    override fun startSignIn(): SignInLink = mapped { core.startSignIn().toLink() }

    override fun resumeSignIn(pendingState: String): SignInLink =
        mapped { SecretValue(pendingState).use { core.resumeSignIn(it).toLink() } }

    override suspend fun awaitSignIn(): SignInStep =
        mapped {
            when (val event = core.awaitSignIn()) {
                is SignInEvent.SignedIn -> {
                    event.result.use { SignInStep.SignedIn(it.info.toSession(), it.secret.expose()) }
                }

                is SignInEvent.RelayChanged -> {
                    SignInStep.RelayChanged(event.request.toLink())
                }

                // Only pollSignIn reports Waiting.
                SignInEvent.Waiting -> {
                    throw CoreFailure.Other("the core reported Waiting from awaitSignIn")
                }
            }
        }

    override fun cancelSignIn() = core.cancelSignIn()

    override fun ringCallback(
        url: String,
        nonce: String,
    ): RingCallback? =
        try {
            when (parseAuthCallback(url, nonce)) {
                AuthCallback.SUCCESS -> RingCallback.Success
                AuthCallback.ERROR -> RingCallback.Error
                AuthCallback.CANCEL -> RingCallback.Cancel
            }
        } catch (_: CoreException) {
            null
        }

    override suspend fun installedSession(): SignInStep.SignedIn? =
        mapped {
            val info = core.currentSession() ?: return@mapped null
            val secret = core.currentSessionSecret() ?: return@mapped null
            SignInStep.SignedIn(info.toSession(), secret.use { it.expose() })
        }

    override suspend fun restoreSession(secret: String): AccountSession =
        mapped { SecretValue(secret).use { core.restoreSession(it).toSession() } }

    override suspend fun signOut(): Boolean =
        mapped {
            val hadSession = core.currentSession() != null
            core.signOut()
            hadSession
        }

    override fun forgetSession() = core.forgetSession()

    override suspend fun publicGet(uri: String): ByteArray = mapped { core.publicGet(uri) }

    override suspend fun get(path: String): ByteArray = mapped { core.get(path) }

    override suspend fun list(
        dir: String,
        cursor: String?,
    ): Page<String> =
        mapped {
            val page = core.list(dir, ListOptions(cursor = cursor))
            Page(page.entries.map { it.path }, page.nextCursor)
        }

    override suspend fun listBookmarks(cursor: String?): Page<SavedPlace> =
        mapped {
            val page = core.listBookmarks(ListOptions(cursor = cursor))
            Page(page.places.map { SavedPlace(place = it.place, path = it.path) }, page.nextCursor)
        }
}

private fun AuthRequest.toLink(): SignInLink =
    use { request ->
        SignInLink(
            url = request.authorizationUrl(),
            nonce = request.callbackNonce(),
            pendingState = request.pendingState().use { it.expose() },
        )
    }

private fun SessionInfo.toSession() = AccountSession(publicKey = publicKey, homeserver = homeserver)

/** Runs a core call with its errors as [CoreFailure]. */
private inline fun <T> mapped(call: () -> T): T =
    try {
        call()
    } catch (e: CoreException) {
        throw e.toFailure()
    }

private fun CoreException.toFailure(): CoreFailure =
    when (this) {
        is CoreException.Network -> CoreFailure.Network(reason)
        is CoreException.RateLimited -> CoreFailure.Network(reason)
        is CoreException.Server -> if (status.toInt() >= 500) CoreFailure.Network(reason) else CoreFailure.Other(reason)
        is CoreException.Auth -> CoreFailure.Auth(reason)
        is CoreException.NotSignedIn -> CoreFailure.Auth("not signed in")
        is CoreException.NotFound -> CoreFailure.NotFound(reason)
        is CoreException.Cancelled -> CoreFailure.Cancelled("cancelled")
        is CoreException.Validation -> CoreFailure.Other(reason)
        is CoreException.Internal -> CoreFailure.Other(reason)
    }
