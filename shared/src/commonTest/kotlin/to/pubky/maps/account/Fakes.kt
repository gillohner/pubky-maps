package to.pubky.maps.account

import kotlinx.coroutines.channels.Channel
import to.pubky.maps.cache.AccountCache
import to.pubky.maps.cache.CachedAccount

const val USER = "8pinxxgqs41n4aididenw5apqp1urfmzdztr8jt4abrkdn435ewo"
const val OTHER_USER = "o1gg96ewuojmopcjbz8895478wdtxtzzuxnfjjz8o8e77csa1ngo"

/** A [PubkyCore] whose answers the test gives. */
class FakePubkyCore : PubkyCore {
    private var flows = 0
    var current: SignInLink? = null
        private set
    val cancelled = mutableListOf<String>()

    /** What the next [awaitSignIn] returns or throws; awaited calls queue here. */
    val signInSteps = Channel<Result<SignInStep>>(Channel.UNLIMITED)
    var awaiting = 0
        private set
    var installed: SignInStep.SignedIn? = null

    var restore: suspend (String) -> AccountSession = { throw CoreFailure.Network("unreachable") }
    val restored = mutableListOf<String>()
    var signOutResult: suspend () -> Boolean = { true }
    var forgotten = 0
    val files = mutableMapOf<String, ByteArray>()
    var tagPaths = listOf<String>()
    var bookmarks = listOf<SavedPlace>()
    var failReads: CoreFailure? = null

    fun link(n: Int) = SignInLink(url = "pubkyauth://signin_grant?flow=$n&secret=s$n", nonce = "nonce$n", pendingState = "state$n")

    override fun startSignIn(): SignInLink = link(++flows).also { current = it }

    override fun resumeSignIn(pendingState: String): SignInLink {
        val n = pendingState.removePrefix("state").toIntOrNull() ?: throw CoreFailure.Other("bad state")
        return link(n).also { current = it }
    }

    override suspend fun awaitSignIn(): SignInStep {
        awaiting++
        try {
            return signInSteps.receive().getOrThrow()
        } finally {
            awaiting--
        }
    }

    override fun cancelSignIn() {
        current?.let { cancelled += it.nonce }
        current = null
    }

    override fun ringCallback(
        url: String,
        nonce: String,
    ): RingCallback? {
        val prefix = "pubkymaps://auth/"
        if (!url.startsWith(prefix) || !url.endsWith("?nonce=$nonce")) return null
        return when (url.removePrefix(prefix).substringBefore("?")) {
            "success" -> RingCallback.Success
            "error" -> RingCallback.Error
            "cancel" -> RingCallback.Cancel
            else -> null
        }
    }

    override suspend fun installedSession(): SignInStep.SignedIn? = installed

    override suspend fun restoreSession(secret: String): AccountSession {
        restored += secret
        return restore(secret)
    }

    override suspend fun signOut(): Boolean = signOutResult()

    override fun forgetSession() {
        forgotten++
    }

    override suspend fun publicGet(uri: String): ByteArray {
        failReads?.let { throw it }
        return files[uri] ?: throw CoreFailure.NotFound(uri)
    }

    override suspend fun get(path: String): ByteArray {
        failReads?.let { throw it }
        return files[path] ?: throw CoreFailure.NotFound(path)
    }

    override suspend fun list(
        dir: String,
        cursor: String?,
    ): Page<String> {
        failReads?.let { throw it }
        // Pages of two, the cursor being the index of the next item.
        val from = cursor?.toInt() ?: 0
        val page = tagPaths.filter { it.startsWith(dir) }.drop(from).take(2)
        return Page(page, if (page.isEmpty()) null else (from + page.size).toString())
    }

    override suspend fun listBookmarks(cursor: String?): Page<SavedPlace> {
        failReads?.let { throw it }
        return Page(if (cursor == null) bookmarks else emptyList(), if (cursor == null && bookmarks.isNotEmpty()) "end" else null)
    }
}

class MemorySecretStore : SecretStore {
    val values = mutableMapOf<String, String>()

    override suspend fun read(name: String): String? = values[name]

    override suspend fun write(
        name: String,
        value: String,
    ) {
        values[name] = value
    }

    override suspend fun delete(name: String) {
        values.remove(name)
    }
}

class MemoryAccountCache : AccountCache {
    var stored: CachedAccount? = null

    override fun read(): CachedAccount? = stored

    override fun begin(publicKey: String) {
        if (stored?.publicKey != publicKey) stored = CachedAccount(publicKey, null, null, emptyList(), emptyList())
    }

    override fun replace(account: CachedAccount) {
        stored = account
    }

    override fun clear() {
        stored = null
    }
}
