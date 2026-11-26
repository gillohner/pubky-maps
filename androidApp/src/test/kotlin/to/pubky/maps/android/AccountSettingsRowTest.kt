package to.pubky.maps.android

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.pubky.maps.account.AccountSettingsRow
import to.pubky.maps.account.AccountState
import to.pubky.maps.account.AccountUser
import to.pubky.maps.account.Connection
import to.pubky.maps.cache.CachedAccount
import to.pubky.maps.ui.PubkyMapsTheme

/**
 * The account row of Settings shows its whole text at Android's largest font size (200 %) on a phone,
 * as the other Settings rows do: no line of it ends in "…". Native graphics, so text is measured with
 * real fonts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp")
class AccountSettingsRowTest {
    @get:Rule
    val compose = createComposeRule()

    private val user = AccountUser("8pinxxgqs41n4aididenw5apqp1urfmzdztr8jt4abrkdn435ewo", "Ada Lovelace")
    private val cached = CachedAccount(user.publicKey, user.name, refreshedAt = 1, tags = emptyList(), bookmarks = emptyList())

    private fun showAtLargeFont(state: AccountState) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                PubkyMapsTheme { AccountSettingsRow(state = state, onOpen = {}) }
            }
        }
    }

    /** Each of [texts] is on the row and laid out without cutting a line. */
    private fun assertShownWhole(vararg texts: String) {
        texts.forEach { text ->
            val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            // Not hasVisualOverflow: it reports a one-line ellipsized text as too wide even when it fits.
            val layout = layouts.single()
            assertFalse("'$text' is cut", (0 until layout.lineCount).any(layout::isLineEllipsized))
        }
    }

    @Test
    fun signedOutRowShowsWhatSigningInIsFor() {
        showAtLargeFont(AccountState.SignedOut())
        assertShownWhole("Sign in with Pubky Ring", "Tag and save places with your Pubky identity")
    }

    @Test
    fun offlineSignedInRowSaysTheHomeserverIsUnreachable() {
        showAtLargeFont(AccountState.SignedIn(user, cached, Connection.Offline))
        assertShownWhole("Ada Lovelace", "pubky8pin…5ewo · homeserver unreachable")
    }

    @Test
    fun unavailableRowSaysWhy() {
        showAtLargeFont(AccountState.Unavailable("no library"))
        assertShownWhole("Account", "Sign-in is not available on this phone")
    }
}
