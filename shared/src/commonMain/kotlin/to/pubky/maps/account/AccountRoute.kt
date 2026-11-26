package to.pubky.maps.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import to.pubky.maps.status.ServiceHealth

/** Pubky Ring, the app that holds the user's keys, on this phone. */
interface PubkyRing {
    /** Whether an app on this phone opens Ring's `pubkyauth://` links. */
    fun isInstalled(): Boolean

    /** Hands [link] to Ring; false when no app took it. */
    fun open(link: String): Boolean
}

/** What the app's screens need of the account. */
class AccountUi(
    val controller: AccountController,
    val ring: PubkyRing,
    val health: ServiceHealth,
) {
    private val show = MutableStateFlow(false)

    /**
     * True while the account page should come to the front (Ring sent the user back). A state, not
     * an event: the app may not show anything yet when Ring's link starts it.
     */
    val showAccount: StateFlow<Boolean> = show.asStateFlow()

    /** Ring's return link opened the app: hand it to the sign-in and show the account page. */
    fun onRingCallback(url: String) {
        controller.onRingCallback(url)
        show.value = true
    }

    /** The account page is in front. */
    fun accountShown() {
        show.value = false
    }
}

/** The account page on the live [AccountController]. */
@Composable
fun AccountRoute(
    account: AccountUi,
    onBack: () -> Unit,
    /** Why the app asks to sign in now, when a write waits for it. */
    reason: String? = null,
    /** How a place (as the cache spells it) reads in a list: its name, else its link in words. */
    placeLabel: (String) -> String,
) {
    val state by account.controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var ringOnThisPhone by remember { mutableStateOf(account.ring.isInstalled()) }
    // The user may have installed Ring while away.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ringOnThisPhone = account.ring.isInstalled() }
    val openRing: (String) -> Unit = { link -> if (!account.ring.open(link)) ringOnThisPhone = false }
    AccountScreen(
        state = state,
        ringOnThisPhone = ringOnThisPhone,
        onSignIn = {
            scope.launch {
                val link = account.controller.signIn()
                if (link != null && ringOnThisPhone) openRing(link)
            }
        },
        onOpenRing = openRing,
        onCancelSignIn = account.controller::cancelSignIn,
        onRefresh = account.controller::refresh,
        onSignOut = account.controller::signOut,
        onBack = onBack,
        reason = reason,
        placeLabel = placeLabel,
    )
}
