package to.pubky.maps.account

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import to.pubky.maps.cache.CachedAccount
import to.pubky.maps.cache.CachedBookmark
import to.pubky.maps.cache.CachedTag
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
import kotlin.time.Duration

internal const val MUSEUM = "https://www.kunsthaus.ch/"
internal const val ARTICLE = "https://blog.example.org/an-article"
internal const val UNANSWERED = "https://down.example.org/"

/**
 * Web pages are those starting with https:// that are no OpenStreetMap link; [answers] by page, the
 * rest no place. An answer given is kept, as Pinakes' are.
 */
internal class FakeWebPagePlaces(
    private val answers: Map<String, Boolean?> = mapOf(MUSEUM to true, ARTICLE to false, UNANSWERED to null),
    /** Pages whose answer never comes. */
    private val hanging: Set<String> = emptySet(),
    /** How long each answer takes. */
    private val answerTime: Duration = Duration.ZERO,
) : WebPagePlaces {
    val asked = mutableListOf<String>()

    /** Pages whose answer came, in order. */
    val answered = mutableListOf<String>()
    private val given = HashMap<String, Boolean>()

    override fun needsCard(target: String): Boolean = target.startsWith("https://") && "openstreetmap.org" !in target

    override suspend fun known(uri: String): Boolean? = given[uri]

    override suspend fun isPlace(uri: String): Boolean? {
        asked += uri
        if (uri in hanging) awaitCancellation()
        delay(answerTime)
        val place = if (uri in answers) answers[uri] else false
        answered += uri
        if (place != null) given[uri] = place
        return place
    }
}

/** What a refresh keeps of [account]: the kept answers applied, then the other pages asked about. */
internal suspend fun placesOnly(
    account: CachedAccount,
    places: WebPagePlaces,
): CachedAccount {
    val known = withKnownPlaces(account, places)
    return known.account.withoutPagesOf(notPlaces(known.unchecked, places))
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

            val kept = placesOnly(account("geo:47.1,8.5", osm, MUSEUM, ARTICLE, UNANSWERED), places)

            assertEquals(listOf("geo:47.1,8.5", osm, MUSEUM, UNANSWERED), kept.bookmarks.map { it.place })
            assertEquals(setOf(MUSEUM, ARTICLE, UNANSWERED), places.asked.toSet())
            assertEquals(3, places.asked.size, "map links are never asked about")
        }

    private fun tagged(
        account: CachedAccount,
        vararg places: String,
    ) = account.copy(tags = places.mapIndexed { i, place -> CachedTag("/pub/social/v1/tags/$i.json", place, "t$i", i.toLong()) })

    @Test
    fun aTaggedWebPageIsKeptOnlyWhenPinakesDoesNotSayItIsNoPlace() =
        runTest {
            val places = FakeWebPagePlaces()
            val osm = "https://www.openstreetmap.org/node/1"

            val kept = placesOnly(tagged(account(), "geo:47.1,8.5", osm, MUSEUM, ARTICLE, UNANSWERED), places)

            assertEquals(listOf("geo:47.1,8.5", osm, MUSEUM, UNANSWERED), kept.tags.map { it.place })
            assertEquals(listOf(MUSEUM, ARTICLE, UNANSWERED), places.asked, "map links are never asked about")
        }

    @Test
    fun aPageBookmarkedAndTaggedIsAskedAboutOnceAndBothAreLeftOut() =
        runTest {
            val places = FakeWebPagePlaces()

            val kept = placesOnly(tagged(account(ARTICLE, MUSEUM), MUSEUM, ARTICLE, ARTICLE, UNANSWERED), places)

            assertEquals(listOf(MUSEUM), kept.bookmarks.map { it.place })
            assertEquals(listOf(MUSEUM, UNANSWERED), kept.tags.map { it.place })
            assertEquals(listOf(ARTICLE, MUSEUM, UNANSWERED), places.asked, "bookmarked pages first, then tagged ones")
        }

    @Test
    fun keptAnswersLeaveOutTagsWithoutAsking() =
        runTest {
            val places = FakeWebPagePlaces()
            placesOnly(account(MUSEUM, ARTICLE), places)
            places.asked.clear()

            val known = withKnownPlaces(tagged(account(), ARTICLE, MUSEUM, UNANSWERED), places)

            assertEquals(listOf(MUSEUM, UNANSWERED), known.account.tags.map { it.place })
            assertEquals(listOf(UNANSWERED), known.unchecked)
            assertEquals(emptyList(), places.asked)
        }

    @Test
    fun theChecksOfOneRefreshCountBookmarkedAndTaggedPagesTogether() =
        runTest {
            val bookmarked = List(MAX_PAGE_CHECKS - 2) { "https://example.org/b$it" }
            val tagged = List(5) { "https://example.org/t$it" }
            val places = FakeWebPagePlaces(answers = emptyMap())

            val known = withKnownPlaces(tagged(account(*bookmarked.toTypedArray()), *tagged.toTypedArray()), places)

            assertEquals(bookmarked + tagged.take(2), known.unchecked)
        }

    @Test
    fun aPageBookmarkedTwiceIsAskedAboutOnce() =
        runTest {
            val places = FakeWebPagePlaces()

            val kept = placesOnly(account(ARTICLE, MUSEUM, ARTICLE), places)

            assertEquals(listOf(MUSEUM), kept.bookmarks.map { it.place })
            assertEquals(listOf(ARTICLE, MUSEUM), places.asked)
        }

    @Test
    fun anAccountWithoutWebPagesIsReturnedAsItIs() =
        runTest {
            val places = FakeWebPagePlaces()
            val account = account("geo:1,2")

            assertTrue(placesOnly(account, places) === account)
            assertTrue(withKnownPlaces(account, places).unchecked.isEmpty())
            assertEquals(emptyList(), places.asked)
        }

    @Test
    fun pagesPastTheChecksOfOneRefreshAreKept() =
        runTest {
            val pages = List(MAX_PAGE_CHECKS + 5) { "https://example.org/$it" }
            val places = FakeWebPagePlaces(answers = emptyMap())

            val kept = placesOnly(account(*pages.toTypedArray()), places)

            assertEquals(MAX_PAGE_CHECKS, places.asked.size)
            assertEquals(pages.drop(MAX_PAGE_CHECKS), kept.bookmarks.map { it.place })
        }

    @Test
    fun theNextRefreshAsksAboutThePagesPastTheChecksOfTheOneBefore() =
        runTest {
            val pages = List(MAX_PAGE_CHECKS + 5) { "https://example.org/$it" }
            val places = FakeWebPagePlaces(answers = mapOf(pages[0] to true))
            val account = account(*pages.toTypedArray())

            placesOnly(account, places)
            val second = placesOnly(account, places)

            assertEquals(pages, places.asked, "each page asked once, the kept answers not again")
            assertEquals(listOf(pages[0]), second.bookmarks.map { it.place })
        }

    @Test
    fun keptAnswersApplyWithoutAsking() =
        runTest {
            val places = FakeWebPagePlaces()
            placesOnly(account(MUSEUM, ARTICLE), places)
            places.asked.clear()

            val known = withKnownPlaces(account(ARTICLE, MUSEUM, UNANSWERED, "geo:1,2"), places)

            assertEquals(listOf(MUSEUM, UNANSWERED, "geo:1,2"), known.account.bookmarks.map { it.place })
            assertEquals(listOf(UNANSWERED), known.unchecked)
            assertEquals(emptyList(), places.asked)
        }

    @Test
    fun onceNoPinakesServerAnswersThePagesNotAskedYetAreKeptWithoutAsking() =
        runTest {
            val places = FakeWebPagePlaces(answers = mapOf(UNANSWERED to null))
            val later = List(10) { "https://example.org/$it" }

            val kept = placesOnly(account(UNANSWERED, *later.toTypedArray()), places)

            assertEquals(listOf(UNANSWERED) + later, kept.bookmarks.map { it.place })
            assertTrue(places.asked.size < 4, "at most the checks already running: ${places.asked}")
        }

    @Test
    fun pagesNotAnsweredWithinTheBudgetAreKeptAndTheRefreshGoesOn() =
        runTest {
            val places = FakeWebPagePlaces(hanging = setOf(UNANSWERED))

            val kept = placesOnly(account(UNANSWERED, ARTICLE, MUSEUM), places)

            assertEquals(listOf(UNANSWERED, MUSEUM), kept.bookmarks.map { it.place })
            assertEquals(CHECKS_BUDGET.inWholeMilliseconds, currentTime)
        }

    @Test
    fun aNotAPlaceAnswerIsAskedAgainAfterAnHourAPlaceAnswerIsNot() =
        runTest {
            val data = CardData()
            var clock = 0L
            val places = PinakesWebPagePlaces(rule = { true }, data = { data }, now = { clock })

            assertNull(places.known(MUSEUM), "nothing asked yet")
            places.isPlace(MUSEUM)
            places.isPlace(ARTICLE)
            clock = 60 * 60 * 1000L - 1
            assertEquals(true, places.known(MUSEUM))
            assertEquals(false, places.known(ARTICLE))
            places.isPlace(MUSEUM)
            places.isPlace(ARTICLE)
            assertEquals(listOf(MUSEUM, ARTICLE), data.asked)
            clock = 60 * 60 * 1000L
            assertEquals(true, places.known(MUSEUM))
            assertNull(places.known(ARTICLE), "an hour old")
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
            assertNull(places.known(UNANSWERED), "no answer is not kept")
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
