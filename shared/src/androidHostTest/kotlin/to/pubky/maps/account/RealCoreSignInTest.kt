package to.pubky.maps.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import to.pubky.maps.core.PubkyMapsCore
import to.pubky.maps.core.defaultCoreConfig
import to.pubky.maps.status.ServiceHealth
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sign-in on the real Rust core (a build for this machine, loaded through JNA), with no network:
 * no DHT and relays that refuse. Ring's side cannot run here; the link, the return links, the resume
 * after a process kill and the cancel are the core's own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealCoreSignInTest {
    private val relay = "http://127.0.0.1:9/inbox"

    private fun realCore(): PubkyCore =
        runBlocking {
            val config = defaultCoreConfig()
            config.dhtEnabled = false
            config.pkarrRelays = listOf("http://127.0.0.1:9")
            config.authRelayUrls = listOf(relay)
            UniffiPubkyCore(PubkyMapsCore.create(config))
        }

    private fun query(
        url: String,
        name: String,
    ): String? =
        url
            .substringAfter('?')
            .split('&')
            .map { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            .firstOrNull { it.first == name }
            ?.second

    @Test
    fun theLinkIsAPubkyRingLinkOnTheConfiguredRelayThatReturnsToTheApp() {
        val link = realCore().startSignIn()

        assertTrue(link.url.startsWith("pubkyauth://"), "a Ring link")
        assertEquals("$relay/", query(link.url, "relay")?.let { if (it.endsWith("/")) it else "$it/" })
        for (outcome in listOf("success", "error", "cancel")) {
            assertEquals("pubkymaps://auth/$outcome?nonce=${link.nonce}", query(link.url, "x-$outcome"), outcome)
        }
        assertEquals("SignInLink(<redacted>)", link.toString())
    }

    @Test
    fun ringsReturnLinksAreReadOnlyForTheirOwnSignIn() {
        val core = realCore()
        val link = core.startSignIn()

        assertEquals(RingCallback.Success, core.ringCallback("pubkymaps://auth/success?nonce=${link.nonce}", link.nonce))
        assertEquals(RingCallback.Cancel, core.ringCallback("pubkymaps://auth/cancel?nonce=${link.nonce}", link.nonce))
        assertEquals(RingCallback.Error, core.ringCallback("pubkymaps://auth/error?nonce=${link.nonce}", link.nonce))
        assertNull(core.ringCallback("pubkymaps://auth/success?nonce=stale", link.nonce))
        assertNull(core.ringCallback("https://evil.example/auth/success?nonce=${link.nonce}", link.nonce))
    }

    @Test
    fun aSignInContinuesInANewProcessFromItsPendingState() {
        val link = realCore().startSignIn()

        val resumed = realCore().resumeSignIn(link.pendingState)

        assertEquals(link.url, resumed.url)
        assertEquals(link.nonce, resumed.nonce)
        assertFailsWith<CoreFailure> { realCore().resumeSignIn("not a pending state") }
    }

    @Test
    fun aCancelledSignInEndsItsWait(): Unit =
        runBlocking {
            val core = realCore()
            core.startSignIn()
            val waiting = async(Dispatchers.IO) { runCatching { core.awaitSignIn() } }
            // The wait is running (on a relay that refuses: it retries for about 14 s).
            delay(500)
            core.cancelSignIn()

            assertIs<CoreFailure.Cancelled>(waiting.await().exceptionOrNull())
        }

    @Test
    fun aStoredSecretThatIsNotASessionSignsOut() {
        val failure = runBlocking { runCatching { realCore().restoreSession("not a session") }.exceptionOrNull() }
        assertIs<CoreFailure.Auth>(failure)
    }

    private fun TestScope.controller(
        core: PubkyCore,
        secrets: SecretStore,
    ) = AccountController(
        createCore = { core },
        secrets = secrets,
        cache = MemoryAccountCache(),
        health = ServiceHealth(),
        scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler),
        now = { 0 },
        webPages = FakeWebPagePlaces(),
    )

    @Test
    fun theStateMachineOnTheRealCoreResumesAfterAKillAndEndsOnRingsCancel() =
        runTest {
            val secrets = MemorySecretStore()
            val first = controller(realCore(), secrets)
            first.start()
            val started = async { first.signIn() }
            runCurrent()
            val link = started.await()!!

            // The process is killed; a new one starts with Ring's cancel link.
            val second = controller(realCore(), secrets)
            second.start()
            runCurrent()
            assertEquals(AccountState.SigningIn(link), second.state.value)
            val nonce = query(link, "x-cancel")!!.substringAfter("nonce=")
            second.onRingCallback("pubkymaps://auth/cancel?nonce=$nonce")
            runCurrent()

            assertEquals(AccountState.SignedOut("The sign-in was cancelled in Pubky Ring."), second.state.value)
            assertNull(secrets.values[SecretStore.PENDING_SIGN_IN])
        }
}
