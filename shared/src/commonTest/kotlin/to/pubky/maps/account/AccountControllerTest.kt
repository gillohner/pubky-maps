package to.pubky.maps.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import to.pubky.maps.cache.CachedAccount
import to.pubky.maps.cache.CachedTag
import to.pubky.maps.status.ServiceHealth
import to.pubky.maps.status.ServicePart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AccountControllerTest {
    private val core = FakePubkyCore()
    private val secrets = MemorySecretStore()
    private val cache = MemoryAccountCache()
    private val health = ServiceHealth()

    private fun TestScope.controller(createCore: suspend () -> PubkyCore = { core }): AccountController =
        AccountController(
            createCore = createCore,
            secrets = secrets,
            cache = cache,
            health = health,
            scope = backgroundScope,
            io = StandardTestDispatcher(testScheduler),
            now = { 1_000L },
        )

    private fun profile(
        publicKey: String,
        name: String,
    ) {
        core.files["pubky://$publicKey/pub/social/v1/profile.json"] = """{"name":"$name","bio":null}""".encodeToByteArray()
    }

    private suspend fun TestScope.signIn(account: AccountController): String {
        val link = async { account.signIn() }
        runCurrent()
        return link.await()!!
    }

    private fun approve(
        publicKey: String = USER,
        secret: String = "grant-secret",
    ) {
        core.signInSteps.trySend(Result.success(SignInStep.SignedIn(AccountSession(publicKey, "homeserver"), secret)))
    }

    @Test
    fun withNothingStoredTheAccountIsSignedOut() =
        runTest {
            val account = controller()
            account.start()
            runCurrent()

            assertEquals(AccountState.SignedOut(), account.state.value)
        }

    @Test
    fun signInShowsTheLinkKeepsItsStateAndWaitsForRingOnlyInTheForeground() =
        runTest {
            val account = controller()
            account.start()
            val link = signIn(account)

            assertEquals(core.link(1).url, link)
            assertEquals(AccountState.SigningIn(link), account.state.value)
            assertEquals("state1", secrets.values[SecretStore.PENDING_SIGN_IN])
            assertEquals(0, core.awaiting, "no wait in the background")

            account.onForeground()
            runCurrent()
            assertEquals(1, core.awaiting)

            account.onBackground()
            runCurrent()
            assertEquals(0, core.awaiting, "the wait stops when the app leaves the screen")
        }

    @Test
    fun ringsApprovalSignsInStoresTheSecretAndReadsTheProfile() =
        runTest {
            profile(USER, "Ada")
            core.tagPaths = listOf("/pub/social/v1/tags/a.json")
            core.files["/pub/social/v1/tags/a.json"] =
                """{"uri":"https://www.openstreetmap.org/node/1","label":"coffee","created_at":5}""".encodeToByteArray()
            val account = controller()
            account.start()
            account.onForeground()
            signIn(account)

            approve(secret = "grant-secret")
            runCurrent()

            val state = assertIs<AccountState.SignedIn>(account.state.value)
            assertEquals(AccountUser(USER, "Ada"), state.user)
            assertEquals(Connection.Online, state.connection)
            assertEquals(listOf("coffee"), state.cached.tags.map { it.label })
            assertEquals("grant-secret", secrets.values[SecretStore.SESSION])
            assertNull(secrets.values[SecretStore.PENDING_SIGN_IN], "the flow's state is deleted after it")
            assertEquals("Ada", cache.stored?.name)
        }

    @Test
    fun ringsReturnLinkForThisSignInIsShownAndOthersAreIgnored() =
        runTest {
            val account = controller()
            account.start()
            account.onForeground()
            val link = signIn(account)

            account.onRingCallback("pubkymaps://auth/success?nonce=someone-else")
            account.onRingCallback("https://example.com/auth/cancel?nonce=nonce1")
            runCurrent()
            assertEquals(AccountState.SigningIn(link), account.state.value)

            account.onRingCallback("pubkymaps://auth/success?nonce=nonce1")
            runCurrent()
            assertEquals(AccountState.SigningIn(link, returned = true), account.state.value)
            assertEquals(1, core.awaiting, "still waiting for the approval on the relay")
        }

    @Test
    fun cancelInRingEndsTheSignInAndDeletesItsState() =
        runTest {
            val account = controller()
            account.start()
            account.onForeground()
            signIn(account)

            account.onRingCallback("pubkymaps://auth/cancel?nonce=nonce1")
            runCurrent()

            assertEquals(AccountState.SignedOut("The sign-in was cancelled in Pubky Ring."), account.state.value)
            assertEquals(listOf("nonce1"), core.cancelled)
            assertNull(secrets.values[SecretStore.PENDING_SIGN_IN])
            assertEquals(0, core.awaiting)
        }

    @Test
    fun cancelHereEndsTheSignInWithoutANotice() =
        runTest {
            val account = controller()
            account.start()
            signIn(account)

            account.cancelSignIn()
            runCurrent()

            assertEquals(AccountState.SignedOut(), account.state.value)
            assertEquals(listOf("nonce1"), core.cancelled)
            assertNull(secrets.values[SecretStore.PENDING_SIGN_IN])
        }

    @Test
    fun anErrorInRingEndsTheSignInWithANotice() =
        runTest {
            val account = controller()
            account.start()
            signIn(account)

            account.onRingCallback("pubkymaps://auth/error?nonce=nonce1")
            runCurrent()

            assertEquals(AccountState.SignedOut("Pubky Ring could not sign you in. Please try again."), account.state.value)
            assertNull(secrets.values[SecretStore.PENDING_SIGN_IN])
        }

    @Test
    fun anUnreachableRelayOrAnExpiredLinkEndsTheSignInWithANotice() =
        runTest {
            val account = controller()
            account.start()
            account.onForeground()
            signIn(account)
            core.signInSteps.trySend(Result.failure(CoreFailure.Network("the sign-in relay is unreachable")))
            runCurrent()
            assertEquals(
                AccountState.SignedOut("Can't reach the sign-in relay. Check your connection and try again."),
                account.state.value,
            )
            assertNull(secrets.values[SecretStore.PENDING_SIGN_IN])

            signIn(account)
            core.signInSteps.trySend(Result.failure(CoreFailure.Auth("expired")))
            runCurrent()
            assertEquals(AccountState.SignedOut("The sign-in link expired. Please start again."), account.state.value)
        }

    @Test
    fun aRelayChangeShowsAndKeepsTheNewLink() =
        runTest {
            val account = controller()
            account.start()
            account.onForeground()
            signIn(account)

            core.signInSteps.trySend(Result.success(SignInStep.RelayChanged(core.link(7))))
            runCurrent()

            assertEquals(AccountState.SigningIn(core.link(7).url, relayChanged = true), account.state.value)
            assertEquals("state7", secrets.values[SecretStore.PENDING_SIGN_IN])
            assertEquals(1, core.awaiting, "waits again, on the new relay")
            // The new flow's return link counts, the old one no longer does.
            account.onRingCallback("pubkymaps://auth/cancel?nonce=nonce1")
            runCurrent()
            assertIs<AccountState.SigningIn>(account.state.value)
            account.onRingCallback("pubkymaps://auth/success?nonce=nonce7")
            runCurrent()
            assertEquals(true, (account.state.value as AccountState.SigningIn).returned)
        }

    @Test
    fun aKilledProcessContinuesTheSignInFromItsStoredState() =
        runTest {
            val first = controller()
            first.start()
            val link = signIn(first)

            // A new process: a new core and controller, the same stored secrets.
            val restarted = FakePubkyCore()
            val second = controller { restarted }
            second.start()
            runCurrent()
            assertEquals(AccountState.SigningIn(link), second.state.value)

            second.onForeground()
            runCurrent()
            assertEquals(1, restarted.awaiting)
            restarted.signInSteps.trySend(Result.success(SignInStep.SignedIn(AccountSession(USER, null), "grant-secret")))
            runCurrent()
            assertIs<AccountState.SignedIn>(second.state.value)
            assertNull(secrets.values[SecretStore.PENDING_SIGN_IN])
        }

    @Test
    fun ringsReturnLinkThatStartedTheProcessIsHandledAfterTheResume() =
        runTest {
            val first = controller()
            first.start()
            signIn(first)

            val second = controller { FakePubkyCore() }
            // As in MainActivity.onCreate right after Application.onCreate: queued in that order.
            second.start()
            second.onRingCallback("pubkymaps://auth/cancel?nonce=nonce1")
            runCurrent()

            assertEquals(AccountState.SignedOut("The sign-in was cancelled in Pubky Ring."), second.state.value)
            assertNull(secrets.values[SecretStore.PENDING_SIGN_IN])
        }

    @Test
    fun anApprovalThatArrivedWhileTheWaitWasCancelledIsTakenFromTheCore() =
        runTest {
            val account = controller()
            account.start()
            signIn(account)
            core.installed = SignInStep.SignedIn(AccountSession(USER, null), "grant-secret")

            account.onForeground()
            runCurrent()

            assertIs<AccountState.SignedIn>(account.state.value)
            assertEquals("grant-secret", secrets.values[SecretStore.SESSION])
            assertEquals(0, core.awaiting)
        }

    @Test
    fun aStoredSessionShowsTheCachedUserAtOnceAndStaysSignedInWhileTheHomeserverIsUnreachable() =
        runTest {
            secrets.values[SecretStore.SESSION] = "grant-secret"
            val tag = CachedTag("/pub/social/v1/tags/a.json", "geo:47.1,8.5", "bench", 1)
            cache.stored = CachedAccount(USER, "Ada", 500, listOf(tag), emptyList())
            val account = controller()

            account.start()
            runCurrent()

            val state = assertIs<AccountState.SignedIn>(account.state.value)
            assertEquals(AccountUser(USER, "Ada"), state.user)
            assertEquals(Connection.Offline, state.connection)
            assertEquals(listOf(tag), state.cached.tags)
            assertEquals(setOf(ServicePart.Homeserver), health.missing.value)
            assertEquals("grant-secret", secrets.values[SecretStore.SESSION], "the session is kept for later")
            assertEquals(listOf("grant-secret"), core.restored)
        }

    @Test
    fun theRestoreIsRetriedInTheForegroundUntilTheHomeserverAnswers() =
        runTest {
            secrets.values[SecretStore.SESSION] = "grant-secret"
            cache.stored = CachedAccount(USER, null, null, emptyList(), emptyList())
            profile(USER, "Ada")
            val account = controller()
            account.start()
            account.onForeground()
            runCurrent()
            assertEquals(Connection.Offline, (account.state.value as AccountState.SignedIn).connection)

            core.restore = { AccountSession(USER, "homeserver") }
            advanceTimeBy(16.seconds)
            runCurrent()

            val state = assertIs<AccountState.SignedIn>(account.state.value)
            assertEquals(Connection.Online, state.connection)
            assertEquals("Ada", state.user.name)
            assertEquals(emptySet(), health.missing.value)
            assertEquals(2, core.restored.size)
        }

    @Test
    fun aConnectionThatComesBackRetriesTheHomeserverAtOnceInsteadOfAfterTheNextWait() =
        runTest {
            secrets.values[SecretStore.SESSION] = "grant-secret"
            cache.stored = CachedAccount(USER, null, null, emptyList(), emptyList())
            profile(USER, "Ada")
            val account = controller()
            account.start()
            account.onForeground()
            runCurrent()
            // Offline long enough that the next retry is 5 minutes away.
            advanceTimeBy((15 + 30 + 60 + 120 + 1).seconds)
            runCurrent()
            assertEquals(5, core.restored.size)

            core.restore = { AccountSession(USER, "homeserver") }
            account.onNetworkAvailable()
            runCurrent()

            val state = assertIs<AccountState.SignedIn>(account.state.value)
            assertEquals(Connection.Online, state.connection)
            assertEquals("Ada", state.user.name)
            assertEquals(emptySet(), health.missing.value)
            assertEquals(6, core.restored.size)
        }

    @Test
    fun aConnectionThatComesBackInTheBackgroundIsTriedOnceWithoutRetriesOnATimer() =
        runTest {
            secrets.values[SecretStore.SESSION] = "grant-secret"
            cache.stored = CachedAccount(USER, null, null, emptyList(), emptyList())
            val account = controller()
            account.start()
            runCurrent()
            assertEquals(1, core.restored.size)

            account.onNetworkAvailable()
            runCurrent()
            assertEquals(2, core.restored.size)
            assertEquals(Connection.Offline, assertIs<AccountState.SignedIn>(account.state.value).connection)

            advanceTimeBy(600.seconds)
            runCurrent()
            assertEquals(2, core.restored.size, "no retries on a timer in the background")
        }

    @Test
    fun aConnectionThatComesBackReadsTheDataAgainWhenTheSessionWasRestoredBeforeTheHomeserverWentAway() =
        runTest {
            secrets.values[SecretStore.SESSION] = "grant-secret"
            cache.stored = CachedAccount(USER, null, null, emptyList(), emptyList())
            profile(USER, "Ada")
            core.restore = { AccountSession(USER, "homeserver") }
            core.failReads = CoreFailure.Network("unreachable")
            val account = controller()
            account.start()
            runCurrent()
            assertEquals(Connection.Offline, assertIs<AccountState.SignedIn>(account.state.value).connection)

            core.failReads = null
            account.onNetworkAvailable()
            runCurrent()

            val state = assertIs<AccountState.SignedIn>(account.state.value)
            assertEquals(Connection.Online, state.connection)
            assertEquals("Ada", state.user.name)
            assertEquals(1, core.restored.size, "the session stays; only the data is read again")
        }

    @Test
    fun aConnectionThatComesBackWhileTheHomeserverAnswersChangesNothing() =
        runTest {
            secrets.values[SecretStore.SESSION] = "grant-secret"
            core.restore = { AccountSession(USER, "homeserver") }
            val account = controller()
            account.start()
            runCurrent()
            assertEquals(Connection.Online, assertIs<AccountState.SignedIn>(account.state.value).connection)
            core.failReads = CoreFailure.Network("would show as offline if read again")

            account.onNetworkAvailable()
            runCurrent()

            assertEquals(Connection.Online, assertIs<AccountState.SignedIn>(account.state.value).connection)
            assertEquals(1, core.restored.size)
        }

    @Test
    fun anEndedSessionSignsOutAndDeletesWhatThePhoneHad() =
        runTest {
            secrets.values[SecretStore.SESSION] = "grant-secret"
            cache.stored = CachedAccount(USER, "Ada", 500, emptyList(), emptyList())
            core.restore = { throw CoreFailure.Auth("revoked") }
            val account = controller()

            account.start()
            runCurrent()

            assertEquals(AccountState.SignedOut("Your sign-in has ended. Please sign in again."), account.state.value)
            assertNull(secrets.values[SecretStore.SESSION])
            assertNull(cache.stored)
        }

    @Test
    fun signOutRevokesTheSessionAndDeletesWhatThePhoneHad() =
        runTest {
            secrets.values[SecretStore.SESSION] = "grant-secret"
            cache.stored = CachedAccount(USER, "Ada", 500, emptyList(), emptyList())
            core.restore = { AccountSession(USER, "homeserver") }
            val account = controller()
            account.start()
            runCurrent()

            account.signOut()
            runCurrent()

            assertEquals(AccountState.SignedOut(), account.state.value)
            assertNull(secrets.values[SecretStore.SESSION])
            assertNull(cache.stored)
            assertEquals(1, core.forgotten)
        }

    @Test
    fun signOutWithoutTheHomeserverSaysTheSignInIsStillListedInRing() =
        runTest {
            secrets.values[SecretStore.SESSION] = "grant-secret"
            cache.stored = CachedAccount(USER, "Ada", 500, emptyList(), emptyList())
            val account = controller()
            account.start()
            runCurrent()

            account.signOut()
            runCurrent()

            val state = assertIs<AccountState.SignedOut>(account.state.value)
            assertTrue(state.notice!!.contains("could not be revoked"))
            assertNull(secrets.values[SecretStore.SESSION])
            assertNull(cache.stored)
            assertEquals(emptySet(), health.missing.value)
        }

    @Test
    fun aSessionWithNothingCachedIsLoadingUntilItIsRestored() =
        runTest {
            secrets.values[SecretStore.SESSION] = "grant-secret"
            var answer: () -> AccountSession = { throw CoreFailure.Network("unreachable") }
            core.restore = { answer() }
            val account = controller()
            account.start()
            account.onForeground()
            runCurrent()
            assertEquals(AccountState.Loading, account.state.value)

            answer = { AccountSession(OTHER_USER, null) }
            advanceTimeBy(16.seconds)
            runCurrent()
            assertEquals(OTHER_USER, (account.state.value as AccountState.SignedIn).user.publicKey)
        }

    @Test
    fun aCoreThatCannotLoadMakesSignInUnavailable() =
        runTest {
            val account = controller { throw Error("libpubky_maps_core.so not found") }
            account.start()
            runCurrent()

            val state = assertIs<AccountState.Unavailable>(account.state.value)
            assertTrue(state.reason.contains("not available"))
        }

    @Test
    fun aNewUserWithNothingWrittenYetIsOnlineNotOffline() =
        runTest {
            // No profile, no tags folder, no saved places: the homeserver answers 404 for each.
            val account = controller()
            account.start()
            account.onForeground()
            signIn(account)
            approve()
            runCurrent()

            val state = assertIs<AccountState.SignedIn>(account.state.value)
            assertEquals(Connection.Online, state.connection)
            assertEquals(false, state.refreshing)
            assertEquals(emptySet(), health.missing.value)
            assertNull(state.user.name)
        }

    @Test
    fun aRefreshThatEndsAfterTheSignOutChangesNothing() =
        runTest {
            secrets.values[SecretStore.SESSION] = "grant-secret"
            cache.stored = CachedAccount(USER, "Ada", 500, emptyList(), emptyList())
            core.restore = { AccountSession(USER, "homeserver") }
            core.tagPaths = listOf("/pub/social/v1/tags/a.json")
            val gate = CompletableDeferred<Unit>()
            core.listGate = gate
            val account = controller()
            account.start()
            runCurrent()
            assertEquals(true, (account.state.value as AccountState.SignedIn).refreshing)

            account.signOut()
            runCurrent()
            core.failReads = CoreFailure.Auth("not signed in")
            gate.complete(Unit)
            runCurrent()

            assertEquals(AccountState.SignedOut(), account.state.value)
        }

    @Test
    fun aSecretThatCannotBeStoredEndsTheSignInWithANoticeInsteadOfACrash() =
        runTest {
            secrets.writeFailure = IllegalStateException("keystore")
            val account = controller()
            account.start()

            val link = async { account.signIn() }
            runCurrent()

            assertNull(link.await())
            val state = assertIs<AccountState.SignedOut>(account.state.value)
            assertTrue(state.notice!!.contains("could not be stored"))
            // The queue still works.
            secrets.writeFailure = null
            assertEquals(core.link(2).url, signIn(account))
        }

    @Test
    fun aSecretStoreThatFailsDuringARestoreRetryIsRetriedInsteadOfCrashing() =
        runTest {
            secrets.values[SecretStore.SESSION] = "grant-secret"
            cache.stored = CachedAccount(USER, "Ada", 500, emptyList(), emptyList())
            val account = controller()
            account.start()
            account.onForeground()
            runCurrent()
            assertEquals(Connection.Offline, (account.state.value as AccountState.SignedIn).connection)

            // The retry's read of the stored session fails (the Keystore does not answer).
            secrets.readFailure = IllegalStateException("keystore busy")
            advanceTimeBy(16.seconds)
            runCurrent()
            assertEquals(Connection.Offline, (account.state.value as AccountState.SignedIn).connection)
            assertEquals(setOf(ServicePart.Homeserver), health.missing.value)
            assertEquals("grant-secret", secrets.values[SecretStore.SESSION], "the session is kept")

            // The next retry reads it and the homeserver answers.
            secrets.readFailure = null
            core.restore = { AccountSession(USER, "homeserver") }
            advanceTimeBy(31.seconds)
            runCurrent()
            assertEquals(Connection.Online, (account.state.value as AccountState.SignedIn).connection)
        }

    @Test
    fun aCacheThatCannotBeWrittenAfterARefreshEndsTheRefresh() =
        runTest {
            secrets.values[SecretStore.SESSION] = "grant-secret"
            cache.stored = CachedAccount(USER, "Ada", 500, emptyList(), emptyList())
            core.restore = { AccountSession(USER, "homeserver") }
            profile(USER, "Ada L.")
            cache.replaceFailure = IllegalStateException("database is locked")
            val account = controller()

            account.start()
            runCurrent()

            val state = assertIs<AccountState.SignedIn>(account.state.value)
            assertEquals(false, state.refreshing, "Refresh works again")
            assertEquals("Ada", state.user.name, "what the phone has is shown")
            // And a later refresh that can write goes through.
            cache.replaceFailure = null
            account.refresh()
            runCurrent()
            assertEquals("Ada L.", (account.state.value as AccountState.SignedIn).user.name)
        }

    @Test
    fun aSignOutThatCannotClearTheCacheEndsSignedOutWithANotice() =
        runTest {
            secrets.values[SecretStore.SESSION] = "grant-secret"
            cache.stored = CachedAccount(USER, "Ada", 500, emptyList(), emptyList())
            core.restore = { AccountSession(USER, "homeserver") }
            val account = controller()
            account.start()
            runCurrent()
            cache.clearFailure = IllegalStateException("database is locked")

            account.signOut()
            runCurrent()

            val state = assertIs<AccountState.SignedOut>(account.state.value)
            assertTrue(state.notice!!.contains("could not delete everything"))
            assertNull(secrets.values[SecretStore.SESSION], "the session is gone")
            assertEquals(1, core.forgotten)

            // The next start finishes it: no stored session, so the cache is cleared.
            cache.clearFailure = null
            val next = controller()
            next.start()
            runCurrent()
            assertEquals(AccountState.SignedOut(), next.state.value)
            assertNull(cache.stored)
        }

    @Test
    fun aSignOutWhileOfflineThatCannotClearTheCacheLeavesNoHomeserverLine() =
        runTest {
            secrets.values[SecretStore.SESSION] = "grant-secret"
            cache.stored = CachedAccount(USER, "Ada", 500, emptyList(), emptyList())
            // The homeserver does not answer: signed in from the cache, the map names it.
            val account = controller()
            account.start()
            runCurrent()
            assertEquals(Connection.Offline, assertIs<AccountState.SignedIn>(account.state.value).connection)
            assertEquals(setOf(ServicePart.Homeserver), health.missing.value)
            cache.clearFailure = IllegalStateException("database is locked")

            account.signOut()
            runCurrent()

            assertIs<AccountState.SignedOut>(account.state.value)
            assertEquals(emptySet(), health.missing.value, "nobody is signed in, so no homeserver is missing")
        }

    @Test
    fun aWriteTheHomeserverTookShowsInTheCachedDataAtOnce() =
        runTest {
            secrets.values[SecretStore.SESSION] = "grant-secret"
            cache.stored = CachedAccount(USER, "Ada", 500, emptyList(), emptyList())
            core.restore = { AccountSession(USER, "homeserver") }
            val account = controller()
            account.start()
            runCurrent()
            val tag = CachedTag("/pub/social/v1/tags/T.json", "https://www.openstreetmap.org/node/1", "coffee", 7)

            account.updateCached { it.copy(tags = it.tags + tag) }
            runCurrent()

            assertEquals(listOf(tag), assertIs<AccountState.SignedIn>(account.state.value).cached.tags)
            assertEquals(listOf(tag), cache.stored!!.tags)
        }

    @Test
    fun aCacheUpdateWhileSignedOutChangesNothing() =
        runTest {
            val account = controller()
            account.start()
            runCurrent()

            account.updateCached { it.copy(name = "Eve") }
            runCurrent()

            assertEquals(AccountState.SignedOut(), account.state.value)
            assertNull(cache.stored)
        }

    @Test
    fun linksAndSecretsNeverShowInLogs() {
        val link = FakePubkyCore().link(1)
        assertEquals("SignInLink(<redacted>)", link.toString())
        assertEquals(false, AccountState.SigningIn(link.url).toString().contains("secret"))
        assertEquals(false, SignInStep.SignedIn(AccountSession(USER, null), "grant-secret").toString().contains("grant-secret"))
    }
}
