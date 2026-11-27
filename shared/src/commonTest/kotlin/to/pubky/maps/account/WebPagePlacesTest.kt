package to.pubky.maps.account

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import to.pubky.maps.cache.CachedAccount
import to.pubky.maps.cache.CachedBookmark
import to.pubky.maps.place.CollectionRefs
import to.pubky.maps.place.GeoBox
import to.pubky.maps.place.MapPlacesPage
import to.pubky.maps.place.PlaceCardInfo
import to.pubky.maps.place.PlaceData
import to.pubky.maps.place.PlaceFixtures
import to.pubky.maps.place.PlaceList
import to.pubky.maps.place.PlacePerson
import to.pubky.maps.place.PlacePost
import to.pubky.maps.place.ResourceView
import to.pubky.maps.place.parsePinakesCard
import to.pubky.maps.search.SourceAnswer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val MUSEUM = "https://www.kunsthaus.ch/"
internal const val ARTICLE = "https://blog.example.org/an-article"
internal const val UNANSWERED = "https://down.example.org/"

/** Web pages are those starting with https:// that are no OpenStreetMap link; [answers] by page, the rest no place. */
internal class FakeWebPagePlaces(
    private val answers: Map<String, Boolean?> = mapOf(MUSEUM to true, ARTICLE to false, UNANSWERED to null),
    /** Pages whose answer never comes. */
    private val hanging: Set<String> = emptySet(),
) : WebPagePlaces {
    val asked = mutableListOf<String>()

    override fun needsCard(target: String): Boolean = target.startsWith("https://") && "openstreetmap.org" !in target

    override suspend fun isPlace(uri: String): Boolean? {
        asked += uri
        if (uri in hanging) awaitCancellation()
        return if (uri in answers) answers[uri] else false
    }
}

class WebPagePlacesTest {
    private fun account(vararg places: String) =
        CachedAccount(
            publicKey = USER,
            name = null,
            refreshedAt = 0,
            tags = emptyList(),
            bookmarks = places.mapIndexed { i, place -> CachedBookmark("/priv/social/v1/bookmarks/$i", place) },
        )

    @Test
    fun aBookmarkedWebPageIsKeptOnlyWhenPinakesDoesNotSayItIsNoPlace() =
        runTest {
            val places = FakeWebPagePlaces()
            val osm = "https://www.openstreetmap.org/node/1"

            val kept = withPlaceBookmarksOnly(account("geo:47.1,8.5", osm, MUSEUM, ARTICLE, UNANSWERED), places)

            assertEquals(listOf("geo:47.1,8.5", osm, MUSEUM, UNANSWERED), kept.bookmarks.map { it.place })
            assertEquals(setOf(MUSEUM, ARTICLE, UNANSWERED), places.asked.toSet())
            assertEquals(3, places.asked.size, "map links are never asked about")
        }

    @Test
    fun aPageBookmarkedTwiceIsAskedAboutOnce() =
        runTest {
            val places = FakeWebPagePlaces()

            val kept = withPlaceBookmarksOnly(account(ARTICLE, MUSEUM, ARTICLE), places)

            assertEquals(listOf(MUSEUM), kept.bookmarks.map { it.place })
            assertEquals(listOf(ARTICLE, MUSEUM), places.asked)
        }

    @Test
    fun anAccountWithoutWebPagesIsReturnedAsItIs() =
        runTest {
            val places = FakeWebPagePlaces()
            val account = account("geo:1,2")

            assertTrue(withPlaceBookmarksOnly(account, places) === account)
            assertEquals(emptyList(), places.asked)
        }

    @Test
    fun pagesPastTheChecksOfOneRefreshAreKept() =
        runTest {
            val pages = List(MAX_PAGE_CHECKS + 5) { "https://example.org/$it" }
            val places = FakeWebPagePlaces(answers = emptyMap())

            val kept = withPlaceBookmarksOnly(account(*pages.toTypedArray()), places)

            assertEquals(MAX_PAGE_CHECKS, places.asked.size)
            assertEquals(pages.drop(MAX_PAGE_CHECKS), kept.bookmarks.map { it.place })
        }

    @Test
    fun onceNoPinakesServerAnswersThePagesNotAskedYetAreKeptWithoutAsking() =
        runTest {
            val places = FakeWebPagePlaces(answers = mapOf(UNANSWERED to null))
            val later = List(10) { "https://example.org/$it" }

            val kept = withPlaceBookmarksOnly(account(UNANSWERED, *later.toTypedArray()), places)

            assertEquals(listOf(UNANSWERED) + later, kept.bookmarks.map { it.place })
            assertTrue(places.asked.size < 4, "at most the checks already running: ${places.asked}")
        }

    @Test
    fun pagesNotAnsweredWithinTheBudgetAreKeptAndTheRefreshGoesOn() =
        runTest {
            val places = FakeWebPagePlaces(hanging = setOf(UNANSWERED))

            val kept = withPlaceBookmarksOnly(account(UNANSWERED, ARTICLE, MUSEUM), places)

            assertEquals(listOf(UNANSWERED, MUSEUM), kept.bookmarks.map { it.place })
            assertEquals(CHECKS_BUDGET.inWholeMilliseconds, currentTime)
        }

    @Test
    fun aNotAPlaceAnswerIsAskedAgainAfterAnHourAPlaceAnswerIsNot() =
        runTest {
            val data = CardData()
            var clock = 0L
            val places = PinakesWebPagePlaces(rule = { true }, data = { data }, now = { clock })

            places.isPlace(MUSEUM)
            places.isPlace(ARTICLE)
            clock = 60 * 60 * 1000L - 1
            places.isPlace(MUSEUM)
            places.isPlace(ARTICLE)
            assertEquals(listOf(MUSEUM, ARTICLE), data.asked)
            clock = 60 * 60 * 1000L
            places.isPlace(MUSEUM)
            places.isPlace(ARTICLE)
            assertEquals(listOf(MUSEUM, ARTICLE, ARTICLE), data.asked)
        }

    @Test
    fun aCardIsAPlaceByItsTypeOrItsPoint() {
        assertTrue(parsePinakesCard(PlaceFixtures.read("pinakes-card-kunsthaus.json")).isPlace)
        val article = parsePinakesCard("""{"id":"a","uri":"$ARTICLE","type":"Article","type_path":["Thing","CreativeWork","Article"]}""")
        assertFalse(article.isPlace)
        assertEquals(listOf("Thing", "CreativeWork", "Article"), article.typePath)
        assertTrue(parsePinakesCard("""{"id":"p","uri":"$MUSEUM","type":"Place"}""").isPlace, "a Place without a type path")
        assertTrue(
            parsePinakesCard(
                """{"id":"m","uri":"$MUSEUM","type":"Museum","type_path":["Thing","Place","CivicStructure","Museum"]}""",
            ).isPlace,
            "a kind of Place without a point",
        )
        assertTrue(parsePinakesCard("""{"id":"e","uri":"$MUSEUM","type":"Event","point":{"lat":47.37,"lon":8.54}}""").isPlace, "on the map")
    }

    @Test
    fun pinakesAnswersArePlaceNotAPlaceOrUnansweredAndAnswersAreKept() =
        runTest {
            val data = CardData()
            val places = PinakesWebPagePlaces(rule = { true }, data = { data }, now = { 0 })

            assertEquals(true, places.isPlace(MUSEUM))
            assertEquals(false, places.isPlace(ARTICLE), "a card that is not a place")
            assertEquals(false, places.isPlace("https://unknown.example.org/"), "a page Pinakes does not know")
            data.down = true
            assertNull(places.isPlace(UNANSWERED))
            assertEquals(true, places.isPlace(MUSEUM), "kept from the first answer")
            assertEquals(false, places.isPlace(ARTICLE))
            data.down = false
            assertEquals(false, places.isPlace(UNANSWERED), "asked again once a server answers")
            assertEquals(listOf(MUSEUM, ARTICLE, "https://unknown.example.org/", UNANSWERED, UNANSWERED), data.asked)
        }

    @Test
    fun atMostTheKeptNumberOfAnswersIsKept() =
        runTest {
            val data = CardData()
            val places = PinakesWebPagePlaces(rule = { true }, data = { data }, now = { 0 }, kept = 1)

            places.isPlace(MUSEUM)
            places.isPlace(ARTICLE)
            places.isPlace(MUSEUM)
            places.isPlace(ARTICLE)

            assertEquals(listOf(MUSEUM, ARTICLE, ARTICLE), data.asked)
        }
}

/** Pinakes with the Kunsthaus' card under [MUSEUM] and an article's under [ARTICLE]; nothing else is asked. */
private class CardData : PlaceData {
    var down = false
    val asked = mutableListOf<String>()

    override suspend fun card(uris: List<String>): SourceAnswer<PlaceCardInfo?> {
        asked += uris
        if (down) return SourceAnswer.Unreachable
        val card =
            when (uris.single()) {
                MUSEUM -> {
                    parsePinakesCard(PlaceFixtures.read("pinakes-card-kunsthaus.json"))
                }

                ARTICLE -> {
                    parsePinakesCard(
                        """{"id":"a","uri":"$ARTICLE","type":"Article","type_path":["Thing","CreativeWork","Article"]}""",
                    )
                }

                else -> {
                    null
                }
            }
        return SourceAnswer.Answered(card, "p")
    }

    override suspend fun collections(
        cardId: String,
        viewer: String?,
    ): SourceAnswer<CollectionRefs> = error("not asked")

    override suspend fun placesIn(
        bbox: GeoBox,
        cursor: String?,
    ): SourceAnswer<MapPlacesPage> = error("not asked")

    override suspend fun views(
        uris: List<String>,
        viewer: String?,
    ): SourceAnswer<List<ResourceView>> = error("not asked")

    override suspend fun posts(
        resourceId: String,
        embeds: Boolean,
        skip: Int,
        limit: Int,
    ): SourceAnswer<List<PlacePost>> = error("not asked")

    override suspend fun people(
        ids: List<String>,
        viewer: String?,
    ): SourceAnswer<List<PlacePerson>> = error("not asked")

    override suspend fun lists(
        postUris: List<String>,
        viewer: String?,
    ): SourceAnswer<List<PlaceList>> = error("not asked")
}
