package to.pubky.maps.account

import kotlinx.coroutines.test.runTest
import to.pubky.maps.cache.CachedAccount
import to.pubky.maps.cache.CachedBookmark
import to.pubky.maps.cache.CachedTag
import to.pubky.maps.place.PlaceData
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The app asks Pinakes only about bookmarked or tagged web pages, by the real Rust core's rule (a build for
 * this machine, loaded through JNA): map links the link reader reads are places by themselves.
 */
class RealCoreWebPagePlacesTest {
    @Test
    fun onlyBookmarkedOrTaggedWebPagesAreCheckedAgainstPinakes() =
        runTest {
            val mapLinks =
                listOf(
                    "https://www.openstreetmap.org/node/270803095",
                    "geo:47.37687,8.54169",
                    "https://maps.app.goo.gl/3bTBxL9Sg2cXE6sq9",
                    "https://www.google.com/maps/place/Kunsthaus+Z%C3%BCrich/@47.3703,8.548,17z",
                )
            val pages = listOf("https://www.kunsthaus.ch/", "https://blog.example.org/an-article")
            val taggedPage = "https://news.example.org/a-story"
            val asked = mutableListOf<String>()
            val places =
                object : WebPagePlaces by androidWebPagePlaces({ error("Pinakes is not asked here") as PlaceData }) {
                    override suspend fun isPlace(uri: String): Boolean {
                        asked += uri
                        return false
                    }
                }
            val account =
                CachedAccount(
                    publicKey = USER,
                    name = null,
                    refreshedAt = 0,
                    tags = (mapLinks + taggedPage).mapIndexed { i, place -> CachedTag("/pub/social/v1/tags/$i.json", place, "t$i", 0) },
                    bookmarks = (mapLinks + pages).mapIndexed { i, place -> CachedBookmark("/priv/social/v1/bookmarks/$i", place) },
                )

            val kept = placesOnly(account, places)

            assertEquals(pages + taggedPage, asked)
            assertEquals(mapLinks, kept.bookmarks.map { it.place })
            assertEquals(mapLinks, kept.tags.map { it.place })
        }
}
