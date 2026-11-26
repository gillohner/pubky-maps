package to.pubky.maps.android

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.account.AccountScreen
import to.pubky.maps.account.AccountState
import to.pubky.maps.ui.PubkyMapsTheme

private const val PHONE_LIGHT = "w412dp-h915dp-notnight-mdpi"
private const val PHONE_DARK = "w412dp-h915dp-night-mdpi"
private const val TABLET_LIGHT = "w1280dp-h800dp-notnight-mdpi"
private const val TABLET_DARK = "w1280dp-h800dp-night-mdpi"

/**
 * Screenshots of the sign-in while it waits for Pubky Ring. Its progress indicators run forever, so
 * these are taken with the Compose clock stopped (ScreenshotTest's capture would never go idle);
 * sizes and themes match ScreenshotTest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignInScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    // A made-up sign-in link of the real form (the secret is not one).
    private val link =
        "pubkyauth://signin_grant?caps=/pub/social/v1/tags/:rw,/priv/social/v1/bookmarks/:rw&client_id=pubky.maps" +
            "&relay=https://httprelay.pubky.app/inbox&secret=NOT-A-SECRET" +
            "&x-success=pubkymaps://auth/success?nonce%3Dn0nc3&x-cancel=pubkymaps://auth/cancel?nonce%3Dn0nc3"
    private val signingIn = AccountState.SigningIn(link)

    private fun capture(
        file: String,
        content: @Composable () -> Unit,
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent { PubkyMapsTheme { content() } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().captureRoboImage("screenshots/$file.png")
    }

    @Composable
    private fun SignIn(
        state: AccountState,
        ringOnThisPhone: Boolean,
    ) {
        AccountScreen(
            state = state,
            ringOnThisPhone = ringOnThisPhone,
            onSignIn = {},
            onOpenRing = {},
            onCancelSignIn = {},
            onRefresh = {},
            onSignOut = {},
            onBack = {},
        )
    }

    @Test
    @Config(qualifiers = PHONE_LIGHT)
    fun ringPhoneLight() = capture("sign-in-ring-phone-light") { SignIn(signingIn, ringOnThisPhone = true) }

    @Test
    @Config(qualifiers = PHONE_DARK)
    fun ringPhoneDark() = capture("sign-in-ring-phone-dark") { SignIn(signingIn, ringOnThisPhone = true) }

    @Test
    @Config(qualifiers = TABLET_LIGHT)
    fun ringTabletLight() = capture("sign-in-ring-tablet-light") { SignIn(signingIn, ringOnThisPhone = true) }

    @Test
    @Config(qualifiers = TABLET_DARK)
    fun ringTabletDark() = capture("sign-in-ring-tablet-dark") { SignIn(signingIn, ringOnThisPhone = true) }

    @Test
    @Config(qualifiers = PHONE_LIGHT)
    fun qrPhoneLight() = capture("sign-in-qr-phone-light") { SignIn(signingIn, ringOnThisPhone = false) }

    @Test
    @Config(qualifiers = PHONE_DARK)
    fun qrPhoneDark() = capture("sign-in-qr-phone-dark") { SignIn(signingIn, ringOnThisPhone = false) }

    @Test
    @Config(qualifiers = TABLET_LIGHT)
    fun qrTabletLight() = capture("sign-in-qr-tablet-light") { SignIn(signingIn, ringOnThisPhone = false) }

    @Test
    @Config(qualifiers = TABLET_DARK)
    fun qrTabletDark() = capture("sign-in-qr-tablet-dark") { SignIn(signingIn, ringOnThisPhone = false) }

    @Test
    @Config(qualifiers = PHONE_LIGHT)
    fun returnedPhoneLight() = capture("sign-in-returned-phone-light") { SignIn(signingIn.copy(returned = true), ringOnThisPhone = true) }

    @Test
    @Config(qualifiers = PHONE_LIGHT)
    fun relayChangedPhoneLight() =
        capture("sign-in-relay-changed-phone-light") { SignIn(signingIn.copy(relayChanged = true), ringOnThisPhone = true) }

    @Test
    @Config(qualifiers = PHONE_LIGHT)
    fun loadingPhoneLight() = capture("account-loading-phone-light") { SignIn(AccountState.Loading, ringOnThisPhone = true) }
}
