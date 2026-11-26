package to.pubky.maps.android

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.pubky.maps.account.AccountScreen
import to.pubky.maps.account.AccountState
import to.pubky.maps.account.AccountUser
import to.pubky.maps.account.Connection
import to.pubky.maps.cache.CachedAccount
import to.pubky.maps.cache.CachedBookmark
import to.pubky.maps.cache.CachedTag
import to.pubky.maps.ui.PubkyMapsTheme

/** The account page lists the user's tags and saved places by the places' names, never by a raw link. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp")
class AccountPlacesTest {
    @get:Rule
    val compose = createComposeRule()

    private val user = AccountUser("8pinxxgqs41n4aididenw5apqp1urfmzdztr8jt4abrkdn435ewo", "Ada Lovelace")
    private val cafe = "https://www.openstreetmap.org/node/2405123"
    private val unnamed = "https://www.openstreetmap.org/relation/1682248"
    private val bench = "geo:47.37689,8.54169"

    private fun show(names: Map<String, String>) {
        val cached =
            CachedAccount(
                publicKey = user.publicKey,
                name = user.name,
                refreshedAt = 1,
                tags =
                    listOf(
                        CachedTag("/pub/social/v1/tags/1.json", cafe, "great coffee", 2),
                        CachedTag("/pub/social/v1/tags/2.json", bench, "bench with a view", 1),
                    ),
                bookmarks =
                    listOf(
                        CachedBookmark("/priv/social/v1/bookmarks/a.json", cafe),
                        CachedBookmark("/priv/social/v1/bookmarks/b.json", unnamed),
                    ),
            )
        compose.setContent {
            PubkyMapsTheme {
                AccountScreen(
                    state = AccountState.SignedIn(user, cached, Connection.Online),
                    ringOnThisPhone = false,
                    onSignIn = {},
                    onOpenRing = {},
                    onCancelSignIn = {},
                    onRefresh = {},
                    onSignOut = {},
                    onBack = {},
                    placeName = names::get,
                )
            }
        }
    }

    @Test
    fun aPlaceWithAKnownNameIsListedByItsName() {
        show(mapOf(cafe to "Café Henrici"))

        // Under its tag and as a saved place.
        compose.onAllNodesWithText("Café Henrici").assertCountEquals(2)
        compose.onAllNodesWithText("OpenStreetMap node", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("openstreetmap.org", substring = true).assertCountEquals(0)
    }

    @Test
    fun aPlaceWithoutANameIsListedByItsLinkInWords() {
        show(emptyMap())

        compose.onAllNodesWithText("OpenStreetMap node 2405123").assertCountEquals(2)
        compose.onNodeWithText("OpenStreetMap relation 1682248").assertExists()
        compose.onNodeWithText("47.37689, 8.54169").assertExists()
        compose.onAllNodesWithText("openstreetmap.org", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("geo:", substring = true).assertCountEquals(0)
    }
}
