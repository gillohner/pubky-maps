package to.pubky.maps.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.pubky.maps.cache.AccountCache
import to.pubky.maps.cache.CachedAccount
import to.pubky.maps.status.ServiceHealth
import to.pubky.maps.status.ServicePart
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The signed-in user as the app shows them. */
data class AccountUser(
    /** z-base-32, 52 characters. */
    val publicKey: String,
    /** From their profile, null when there is none (or it was not read yet). */
    val name: String?,
)

/** Whether the app reaches the user's homeserver right now. */
enum class Connection { Connecting, Online, Offline }

sealed interface AccountState {
    /** Reading what this phone stored, or restoring a session nothing was cached for. */
    data object Loading : AccountState

    /** Not signed in. [notice] says how the last attempt ended, when it did not sign in. */
    data class SignedOut(
        val notice: String? = null,
    ) : AccountState

    /**
     * A sign-in waits for Pubky Ring. [link] is the `pubkyauth://` link (for Ring on this phone and
     * the QR code); it holds a secret of the flow, so it is shown but never logged. [returned] once
     * Ring sent the user back. [relayChanged] when the link changed because a relay failed (Ring on
     * this phone must open the new one).
     */
    data class SigningIn(
        val link: String,
        val returned: Boolean = false,
        val relayChanged: Boolean = false,
    ) : AccountState {
        override fun toString(): String = "SigningIn(link=<redacted>, returned=$returned, relayChanged=$relayChanged)"
    }

    /** Signed in. [cached] is what this phone has of the user's data; [refreshing] while it is read again. */
    data class SignedIn(
        val user: AccountUser,
        val cached: CachedAccount,
        val connection: Connection,
        val refreshing: Boolean = false,
        val signingOut: Boolean = false,
    ) : AccountState

    /** Sign-in cannot work on this phone (the Pubky library did not load). */
    data class Unavailable(
        val reason: String,
    ) : AccountState
}

private const val SESSION_ENDED = "Your sign-in has ended. Please sign in again."

/** After a sign-out that could not reach the homeserver. */
private const val NOT_REVOKED =
    "Signed out on this phone. The sign-in could not be revoked now; Pubky Ring still lists it, and you can revoke it there."

/**
 * The account: Pubky Ring sign-in, the stored session, the user's cached data and sign-out.
 *
 * Every change runs in order on one queue, so a start-up restore, Ring's return link and a tap on
 * Cancel never interleave. Network calls (waiting for Ring, restoring the session, reading the data)
 * run beside the queue and hand their results back to it; nothing here blocks the caller or the map.
 *
 * A sign-in's state is stored (encrypted) while it runs, so it continues after the process was
 * killed. Ring's approval is waited for only while the app is in the foreground ([onForeground]):
 * Android cuts a backgrounded app's network, and the core keeps an approval that arrives meanwhile.
 */
class AccountController(
    private val createCore: suspend () -> PubkyCore,
    private val secrets: SecretStore,
    private val cache: AccountCache,
    private val health: ServiceHealth,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val now: () -> Long,
    /** Waits between restore attempts while the homeserver is unreachable; the last one repeats. */
    private val retryDelays: List<Duration> = listOf(15.seconds, 30.seconds, 60.seconds, 120.seconds, 300.seconds),
) {
    private val mutableState = MutableStateFlow<AccountState>(AccountState.Loading)
    val state: StateFlow<AccountState> = mutableState.asStateFlow()

    private val actions = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private var core: PubkyCore? = null
    private var foreground = false
    private var link: SignInLink? = null
    private var waitJob: Job? = null
    private var restoreJob: Job? = null
    private var refreshJob: Job? = null
    private var retryJob: Job? = null
    private var revokeJob: Job? = null
    private var retries = 0

    /**
     * Counts session changes (signed in, restored, signing out, signed out). A restore, refresh or
     * revoke result is applied only while the session it was made for is still the current one.
     */
    private var session = 0

    /** True once the core holds a session for the user (restored or signed in here). */
    private var sessionInstalled = false

    init {
        scope.launch {
            for (action in actions) {
                try {
                    action()
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    // Storage, Keystore or SQLite failures must not stop the queue or the app.
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    failed(e)
                }
            }
        }
    }

    private fun enqueue(action: suspend () -> Unit) {
        actions.trySend(action)
    }

    /** Jobs beside the queue hand an unexpected exception to the queue instead of crashing the app. */
    private val guard =
        CoroutineExceptionHandler { _, e ->
            if (e is Exception) enqueue { failed(e) } else throw e
        }

    private suspend fun <T> io(block: () -> T): T = withContext(io) { block() }

    /** Runs a core call beside the queue; any failure becomes a [CoreFailure]. */
    private suspend fun <T> call(block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CoreFailure) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            throw CoreFailure.Other(e::class.simpleName ?: "unexpected error")
        }

    /** An action failed outside the core (storage on this phone): end what it was doing, keep the rest. */
    private fun failed(e: Exception) {
        val what = e::class.simpleName ?: "error"
        when (state.value) {
            AccountState.Loading -> {
                mutableState.value =
                    AccountState.SignedOut(
                        notice = "The sign-in stored on this phone could not be read ($what). Restart the app to try again.",
                    )
            }

            is AccountState.SigningIn, is AccountState.SignedOut -> {
                waitJob?.cancel()
                waitJob = null
                link = null
                core?.cancelSignIn()
                mutableState.value =
                    AccountState.SignedOut(notice = "The sign-in could not be stored on this phone ($what). Please try again.")
            }

            is AccountState.SignedIn -> {
                val signedIn = state.value as AccountState.SignedIn
                mutableState.value =
                    if (signedIn.signingOut) {
                        // The session is already forgotten; what is left on the phone goes at the next
                        // start (it clears the cache when no session is stored). Nobody is signed in, so
                        // no homeserver is missing.
                        health.recovered(ServicePart.Homeserver)
                        AccountState.SignedOut(notice = "Signed out, but this phone could not delete everything yet ($what).")
                    } else {
                        // Signed in still; only the step that failed ends (a cache that could not be written).
                        signedIn.copy(refreshing = false)
                    }
            }

            else -> {}
        }
    }

    /**
     * Reads what this phone stored: restores the session or continues a running sign-in. [prepare]
     * runs first, on the same queue, so everything enqueued after start sees its result.
     */
    fun start(prepare: suspend () -> Unit = {}) =
        enqueue {
            prepare()
            startNow()
        }

    /** The app came to the foreground: wait for Ring again, retry an unreachable homeserver now. */
    fun onForeground() =
        enqueue {
            foreground = true
            if (link != null) waitForRing()
            retryNow()
        }

    /**
     * The phone has a connection again: retry an unreachable homeserver now, so writes waiting for
     * it go out within seconds instead of after the next wait (up to 5 minutes). In the background
     * this is one attempt; the retries on a timer run only in the foreground.
     */
    fun onNetworkAvailable() = enqueue { retryNow() }

    /** Tries an unreachable homeserver again now, and the waits between retries start over. */
    private fun retryNow() {
        if (!health.missing.value.contains(ServicePart.Homeserver)) return
        retries = 0
        retryJob?.cancel()
        retryJob = null
        reconnect()
    }

    /** The app left the foreground: stop waiting for Ring and retrying (the network may be cut). */
    fun onBackground() =
        enqueue {
            foreground = false
            waitJob?.cancel()
            waitJob = null
            retryJob?.cancel()
            retryJob = null
        }

    /** Starts a sign-in and returns its link, to open in Pubky Ring; null when it cannot start now. */
    suspend fun signIn(): String? {
        val result = CompletableDeferred<String?>()
        enqueue {
            try {
                result.complete(startSignIn())
            } finally {
                result.complete(null)
            }
        }
        return result.await()
    }

    /** Ring's return link (`pubkymaps://auth/…`) opened the app. */
    fun onRingCallback(url: String) = enqueue { ringCallback(url) }

    fun cancelSignIn() = enqueue { endSignIn(notice = null) }

    /** Reads the user's data from the homeserver again. */
    fun refresh() =
        enqueue {
            val signedIn = state.value as? AccountState.SignedIn ?: return@enqueue
            if (!signedIn.signingOut) reconnect()
        }

    /**
     * Applies [change] to the signed-in user's cached data and stores it: a write the homeserver took
     * shows at once, before the next refresh reads it back. Nothing happens while signed out.
     */
    fun updateCached(change: (CachedAccount) -> CachedAccount) =
        enqueue {
            val signedIn = state.value as? AccountState.SignedIn ?: return@enqueue
            if (signedIn.signingOut) return@enqueue
            val updated = change(signedIn.cached)
            if (updated == signedIn.cached) return@enqueue
            io { cache.replace(updated) }
            mutableState.value = signedIn.copy(cached = updated)
        }

    /** Revokes the session (when the homeserver answers) and deletes the account's data from this phone. */
    fun signOut() =
        enqueue {
            val signedIn = state.value as? AccountState.SignedIn ?: return@enqueue
            if (signedIn.signingOut) return@enqueue
            val core = core ?: return@enqueue
            // Nothing that was running for this session may land after the sign-out.
            stopSessionJobs()
            val signingOut = ++session
            mutableState.value = signedIn.copy(signingOut = true, refreshing = false)
            val installed = sessionInstalled
            scope.launch(guard) {
                val revoked =
                    try {
                        installed && call { core.signOut() }
                    } catch (_: CoreFailure) {
                        false
                    }
                enqueue { if (session == signingOut) signedOut(notice = if (revoked) null else NOT_REVOKED) }
            }
        }

    private suspend fun stopSessionJobs() {
        retryJob?.cancel()
        retryJob = null
        refreshJob?.cancelAndJoin()
        refreshJob = null
        // Joined, so a restore that was just finishing cannot install its session after this.
        restoreJob?.cancelAndJoin()
        restoreJob = null
    }

    private suspend fun startNow() {
        val created =
            try {
                createCore()
            } catch (e: CancellationException) {
                throw e
            } catch (
                // A missing or broken native library is an Error, not an Exception.
                @Suppress("TooGenericExceptionCaught") e: Throwable,
            ) {
                mutableState.value = AccountState.Unavailable("Sign-in is not available on this phone: ${e.message ?: e::class.simpleName}")
                return
            }
        core = created
        val secret = secrets.read(SecretStore.SESSION)
        val pending = secrets.read(SecretStore.PENDING_SIGN_IN)
        if (secret != null) {
            // A sign-in can only start while signed out; a leftover one is stale.
            if (pending != null) secrets.delete(SecretStore.PENDING_SIGN_IN)
            val cached = io { cache.read() }
            mutableState.value =
                if (cached != null) {
                    AccountState.SignedIn(AccountUser(cached.publicKey, cached.name), cached, Connection.Connecting)
                } else {
                    AccountState.Loading
                }
            restore()
            return
        }
        io { cache.clear() }
        mutableState.value = AccountState.SignedOut()
        if (pending != null) {
            try {
                showLink(created.resumeSignIn(pending))
            } catch (_: CoreFailure) {
                secrets.delete(SecretStore.PENDING_SIGN_IN)
                mutableState.value = AccountState.SignedOut(notice = "The sign-in could not continue. Please start again.")
            }
        }
    }

    private suspend fun startSignIn(): String? {
        val core = core ?: return null
        when (val state = state.value) {
            is AccountState.SigningIn -> {
                return state.link
            }

            !is AccountState.SignedOut -> {
                return null
            }

            else -> {}
        }
        // A session left from a sign-in whose result came too late is being revoked: wait for that,
        // so the new sign-in cannot pick it up.
        revokeJob?.join()
        val started =
            try {
                core.startSignIn()
            } catch (e: CoreFailure) {
                mutableState.value = AccountState.SignedOut(notice = "The sign-in could not start: ${e.reason}")
                return null
            }
        showLink(started)
        return started.url
    }

    private suspend fun showLink(
        started: SignInLink,
        relayChanged: Boolean = false,
    ) {
        secrets.write(SecretStore.PENDING_SIGN_IN, started.pendingState)
        link = started
        mutableState.value = AccountState.SigningIn(link = started.url, relayChanged = relayChanged)
        waitForRing()
    }

    /** Waits for Ring's approval while in the foreground; one wait at a time. */
    private fun waitForRing() {
        if (!foreground || waitJob?.isActive == true) return
        val core = core ?: return
        val flow = link ?: return
        waitJob =
            scope.launch(guard) {
                val wait = coroutineContext[Job]
                // A result handed out while the last wait was being cancelled installed its session
                // without reaching us: take it from the core.
                val step =
                    try {
                        call { core.installedSession() ?: core.awaitSignIn() }
                    } catch (e: CoreFailure) {
                        enqueue { if (waitEnded(wait, flow)) signInFailed(e) }
                        return@launch
                    }
                enqueue {
                    when {
                        waitEnded(wait, flow) -> {
                            when (step) {
                                is SignInStep.SignedIn -> signedIn(step)
                                is SignInStep.RelayChanged -> relayChanged(step.link)
                            }
                        }

                        // The result of a sign-in that is no longer running (cancelled just as Ring
                        // approved it): its session is in the core; revoke it.
                        step is SignInStep.SignedIn && link !== flow -> {
                            revokeStale(core)
                        }
                    }
                }
            }
    }

    /**
     * A wait for [flow] ended. True when [flow] is still the running sign-in; then no other wait for it
     * is left running.
     */
    private fun waitEnded(
        wait: Job?,
        flow: SignInLink,
    ): Boolean {
        if (link !== flow) return false
        if (waitJob !== wait) waitJob?.cancel()
        waitJob = null
        return true
    }

    private fun revokeStale(core: PubkyCore) {
        if (state.value is AccountState.SignedIn) return
        revokeJob =
            scope.launch(guard) {
                try {
                    call { core.signOut() }
                } catch (_: CoreFailure) {
                    core.forgetSession()
                }
            }
    }

    private suspend fun relayChanged(next: SignInLink) {
        showLink(next, relayChanged = true)
    }

    private suspend fun ringCallback(url: String) {
        val flow = link ?: return
        val core = core ?: return
        when (core.ringCallback(url, flow.nonce)) {
            // Ring approved: the core's wait picks the approval up from the relay.
            RingCallback.Success -> {
                (state.value as? AccountState.SigningIn)?.let { mutableState.value = it.copy(returned = true) }
            }

            RingCallback.Cancel -> {
                endSignIn(notice = "The sign-in was cancelled in Pubky Ring.")
            }

            RingCallback.Error -> {
                endSignIn(notice = "Pubky Ring could not sign you in. Please try again.")
            }

            // Not this sign-in's link (an old or a foreign one): ignored.
            null -> {}
        }
    }

    private suspend fun endSignIn(notice: String?) {
        if (link == null) return
        core?.cancelSignIn()
        waitJob?.cancel()
        waitJob = null
        link = null
        secrets.delete(SecretStore.PENDING_SIGN_IN)
        mutableState.value = AccountState.SignedOut(notice = notice)
    }

    private suspend fun signInFailed(failure: CoreFailure) {
        if (failure is CoreFailure.Cancelled) return
        val notice =
            when (failure) {
                is CoreFailure.Network -> "Can't reach the sign-in relay. Check your connection and try again."
                is CoreFailure.Auth -> "The sign-in link expired. Please start again."
                else -> "The sign-in failed: ${failure.reason}"
            }
        endSignIn(notice)
    }

    private suspend fun signedIn(step: SignInStep.SignedIn) {
        link = null
        session++
        val publicKey = step.session.publicKey
        // The account's row first: a stored session always has a cached user to show.
        io { cache.begin(publicKey) }
        secrets.write(SecretStore.SESSION, step.secret)
        secrets.delete(SecretStore.PENDING_SIGN_IN)
        sessionInstalled = true
        val cached = io { cache.read() } ?: CachedAccount(publicKey, null, null, emptyList(), emptyList())
        mutableState.value = AccountState.SignedIn(AccountUser(publicKey, cached.name), cached, Connection.Online)
        health.recovered(ServicePart.Homeserver)
        refreshData()
    }

    private fun restore() {
        if (restoreJob?.isActive == true) return
        val core = core ?: return
        val current = session
        restoreJob =
            scope.launch(guard) {
                try {
                    // A secret store that fails now (Keystore busy, disk error) is retried like a
                    // homeserver that does not answer.
                    val secret = call { secrets.read(SecretStore.SESSION) } ?: return@launch
                    val restoredSession = call { core.restoreSession(secret) }
                    enqueue { if (session == current) restored(restoredSession) }
                } catch (_: CoreFailure.Auth) {
                    enqueue { if (session == current) signedOut(notice = SESSION_ENDED) }
                } catch (_: CoreFailure) {
                    enqueue { if (session == current) offline() }
                }
            }
    }

    private suspend fun restored(restoredSession: AccountSession) {
        if (state.value !is AccountState.SignedIn && state.value != AccountState.Loading) return
        sessionInstalled = true
        retries = 0
        io { cache.begin(restoredSession.publicKey) }
        val cached = io { cache.read() } ?: CachedAccount(restoredSession.publicKey, null, null, emptyList(), emptyList())
        mutableState.value = AccountState.SignedIn(AccountUser(cached.publicKey, cached.name), cached, Connection.Online)
        health.recovered(ServicePart.Homeserver)
        refreshData()
    }

    /** The homeserver did not answer: show what is cached and try again later (in the foreground). */
    private fun offline() {
        val state = state.value
        when {
            state is AccountState.SignedIn && !state.signingOut -> {
                mutableState.value = state.copy(connection = Connection.Offline, refreshing = false)
            }

            // A stored session nothing was cached for: keep trying.
            state == AccountState.Loading -> {}

            else -> {
                return
            }
        }
        health.failed(ServicePart.Homeserver)
        if (!foreground) return
        val wait = retryDelays[minOf(retries, retryDelays.lastIndex)]
        retries++
        retryJob?.cancel()
        retryJob =
            scope.launch(guard) {
                delay(wait)
                enqueue { reconnect() }
            }
    }

    /** Restores the session, or (when it is restored) reads the data again. */
    private fun reconnect() {
        if (sessionInstalled) refreshData() else restore()
    }

    private fun refreshData() {
        if (refreshJob?.isActive == true) return
        val core = core ?: return
        val signedIn = state.value as? AccountState.SignedIn ?: return
        if (signedIn.signingOut) return
        mutableState.value = signedIn.copy(refreshing = true)
        val publicKey = signedIn.user.publicKey
        val current = session
        refreshJob =
            scope.launch(guard) {
                try {
                    val loaded = call { AccountLoader(core, now).load(publicKey) }
                    enqueue { if (session == current) refreshed(loaded) }
                } catch (_: CoreFailure.Auth) {
                    enqueue { if (session == current) signedOut(notice = SESSION_ENDED) }
                } catch (_: CoreFailure.Network) {
                    enqueue { if (session == current) offline() }
                } catch (_: CoreFailure) {
                    // The homeserver answered, with something this version cannot read: keep what is cached.
                    enqueue { if (session == current) refreshDone() }
                }
            }
    }

    private suspend fun refreshed(loaded: CachedAccount) {
        val signedIn = state.value as? AccountState.SignedIn ?: return
        if (signedIn.user.publicKey != loaded.publicKey) return
        io { cache.replace(loaded) }
        retries = 0
        health.recovered(ServicePart.Homeserver)
        mutableState.value =
            signedIn.copy(
                user = AccountUser(loaded.publicKey, loaded.name),
                cached = loaded,
                connection = Connection.Online,
                refreshing = false,
            )
    }

    private fun refreshDone() {
        val signedIn = state.value as? AccountState.SignedIn ?: return
        health.recovered(ServicePart.Homeserver)
        mutableState.value = signedIn.copy(connection = Connection.Online, refreshing = false)
    }

    private suspend fun signedOut(notice: String?) {
        stopSessionJobs()
        session++
        core?.forgetSession()
        sessionInstalled = false
        // Before the steps that can fail: once signed out, no homeserver is missing.
        health.recovered(ServicePart.Homeserver)
        secrets.delete(SecretStore.SESSION)
        io { cache.clear() }
        mutableState.value = AccountState.SignedOut(notice = notice)
    }
}
