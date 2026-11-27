package to.pubky.maps.place

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.map.DROPPED_PIN
import to.pubky.maps.map.droppedPin
import to.pubky.maps.map.savedPointPlace
import to.pubky.maps.map.toPlaceRef
import to.pubky.maps.search.SourceAnswer
import to.pubky.maps.write.Kept
import to.pubky.maps.write.SavedEntry
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** Answers from the recorded fixtures; each source can be made unreachable or held back. */
internal class FakePlaceData : PlaceData {
    var pinakesDown = false
    var nexusDown = false

    /** Only Nexus' posts (replies and photos) do not answer. */
    var postsDown = false
    var cardGate: CompletableDeferred<Unit>? = null
    val viewers = mutableListOf<String?>()
    val askedLinks = mutableListOf<List<String>>()
    val askedCards = mutableListOf<List<String>>()
    val replyPages = mutableListOf<Int>()

    /** Replies Nexus has for the Schober (newest first), 20 per page. */
    var replies: List<PlacePost> = parseNexusPosts(PlaceFixtures.read("nexus-replies-schober.json"), PlaceFixtures.NEXUS)

    /** The OSM details the Schober's card brings; null keeps the recorded card's (none). */
    var schoberFacts: Map<String, String>? = null

    private fun <T> pinakes(value: () -> T): SourceAnswer<T> =
        if (pinakesDown) SourceAnswer.Unreachable else SourceAnswer.Answered(value(), "p")

    private fun <T> nexus(value: () -> T): SourceAnswer<T> =
        if (nexusDown) SourceAnswer.Unreachable else SourceAnswer.Answered(value(), "n")

    override suspend fun card(uris: List<String>): SourceAnswer<PlaceCardInfo?> {
        askedCards += uris
        cardGate?.await()
        return pinakes {
            if (SCHOBER in uris) {
                parsePinakesCard(PlaceFixtures.read("pinakes-card-schober.json")).let { card ->
                    schoberFacts?.let { card.copy(facts = it) } ?: card
                }
            } else {
                null
            }
        }
    }

    override suspend fun collections(
        cardId: String,
        viewer: String?,
    ) = pinakes { parsePinakesCollections(PlaceFixtures.read("pinakes-collections-schober.json")) }

    override suspend fun placesIn(
        bbox: GeoBox,
        cursor: String?,
    ) = pinakes { parseMapPlaces(PlaceFixtures.read("pinakes-bbox-zurich.json")) }

    override suspend fun views(
        uris: List<String>,
        viewer: String?,
    ): SourceAnswer<List<ResourceView>> {
        viewers += viewer
        askedLinks += uris
        return nexus {
            parseNexusResourceViews(PlaceFixtures.read("nexus-by-ids-zurich.json"))
                .filter { it.uri in uris }
                .map { view ->
                    if (view.uri == SCHOBER) {
                        view.copy(
                            replies = replies.size,
                            tags =
                                view.tags.map {
                                    if (it.label == "cake" &&
                                        viewer != null
                                    ) {
                                        it.copy(tagUri = "pubky://$viewer/t")
                                    } else {
                                        it
                                    }
                                },
                        )
                    } else {
                        view
                    }
                }
        }
    }

    override suspend fun posts(
        resourceId: String,
        embeds: Boolean,
        skip: Int,
        limit: Int,
    ): SourceAnswer<List<PlacePost>> =
        if (postsDown) {
            SourceAnswer.Unreachable
        } else {
            nexus {
                if (embeds) {
                    parseNexusPosts(PlaceFixtures.read("nexus-embeds-schober.json"), PlaceFixtures.NEXUS)
                } else {
                    replyPages += skip
                    replies.drop(skip).take(limit)
                }
            }
        }

    override suspend fun people(
        ids: List<String>,
        viewer: String?,
    ) = nexus {
        parseNexusUsers(PlaceFixtures.read("nexus-users.json"), PlaceFixtures.NEXUS)
            .filter { it.id in ids }
            .map { if (it.name == "Pinakes Test 5") it.copy(followed = true) else it }
    }

    override suspend fun lists(
        postUris: List<String>,
        viewer: String?,
    ) = nexus { parseNexusCollections(PlaceFixtures.read("nexus-collection-posts.json"), viewer) }

    companion object {
        const val SCHOBER = "https://www.openstreetmap.org/node/270803095"
    }
}

/** Tags kept for an offline area holding the Schober, fetched at [fetchedAt]; records the links asked about. */
internal class KeptSchober(
    val fetchedAt: Long,
) : KeptPlaceSocial {
    val asked = mutableListOf<List<String>>()

    override fun socialOf(uris: List<String>): KeptSocial? {
        asked += uris
        if (FakePlaceData.SCHOBER !in uris) return null
        val tag = PlaceTag(label = "kept", count = 2, taggers = listOf(PlaceFixtures.TESTER, "someoneelse"), mine = false)
        return KeptSocial(PlaceSocial(listOf(tag), resourceIds = listOf("r"), replyCount = 1, embedCount = 0), fetchedAt)
    }
}

/** Runs what is sent to it only when told to: a disk read still under way. */
private class HeldDispatcher : CoroutineDispatcher() {
    private val held = ArrayDeque<Runnable>()

    val waiting: Int get() = held.size

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        held += block
    }

    fun runAll() {
        while (held.isNotEmpty()) held.removeFirst().run()
    }
}

private class MemoryPlaceCache : PlaceCache {
    val saved = mutableMapOf<String, PlaceSnapshot>()

    override fun read(uri: String) = saved[uri]

    override fun write(snapshot: PlaceSnapshot) {
        saved[snapshot.uri] = snapshot
    }
}

class PlacePageControllerTest {
    private val schober =
        PlaceRef(
            uri = FakePlaceData.SCHOBER,
            name = "Conditorei schober",
            point = null,
            osm = OsmId(OsmType.Node, 270803095),
            source = PlaceSource.Pinakes,
        )

    private fun TestScope.controller(
        data: PlaceData,
        cache: PlaceCache? = null,
        viewer: String? = null,
        kept: KeptPlaceSocial? = null,
        clock: Clock = Clock.System,
        localities: OfflineLocality? = null,
        details: KeptPlaceDetails? = null,
        io: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ) = PlacePageController(
        data = data,
        cache = cache,
        scope = this,
        kept = kept,
        viewer = { viewer },
        clock = clock,
        io = io,
        localities = localities,
        details = details,
    )

    /** The offline areas keep Café Rosa's details (or every element's), fetched at 5,000; records what was asked. */
    private class KeptRosa(
        val everyElement: Boolean = false,
    ) : KeptPlaceDetails {
        val asked = mutableListOf<OsmId>()

        override fun detailsOf(osm: OsmId): KeptDetails? {
            asked += osm
            return KeptDetails(mapOf("opening_hours" to "Mo-Su 08:00-18:00", "phone" to "+41 44 000 00 00"), fetchedAt = 5_000).takeIf {
                everyElement || osm == OsmId(OsmType.Node, 42)
            }
        }
    }

    @Test
    fun aPlaceWithNoCardShowsTheDetailsTheOfflineAreasKeepWithTheirDate() =
        runTest {
            val details = KeptRosa()
            val pages =
                controller(
                    FakePlaceData().apply {
                        pinakesDown = true
                        nexusDown = true
                    },
                    details = details,
                )
            pages.open(tapped)
            advanceUntilIdle()
            val page = pages.state.value!!
            assertEquals(listOf(OsmId(OsmType.Node, 42)), details.asked)
            assertEquals("Mo-Su 08:00-18:00", page.osm?.openingHours)
            assertEquals("+41 44 000 00 00", page.osm?.phone)
            assertEquals(5_000, page.shownAt, "the offline note dates the details")
        }

    @Test
    fun aPlacePinakesHasNoCardForShowsTheKeptDetailsOnlineToo() =
        runTest {
            val pages = controller(FakePlaceData(), details = KeptRosa())
            pages.open(tapped)
            advanceUntilIdle()
            val page = pages.state.value!!
            assertNull(page.card)
            assertEquals("Mo-Su 08:00-18:00", page.osm?.openingHours)
            assertFalse(page.offline)
        }

    @Test
    fun aCardWithoutOsmDetailsShowsTheKeptOnesFreshAndFromTheCache() =
        runTest {
            val details = KeptRosa(everyElement = true)
            val cache = MemoryPlaceCache()
            val online = controller(FakePlaceData(), cache, details = details).apply { open(schober) }
            advanceUntilIdle()
            assertNotNull(online.state.value!!.card)
            assertEquals(
                "Mo-Su 08:00-18:00",
                online.state.value!!
                    .osm
                    ?.openingHours,
                "Pinakes gives the card no OSM details",
            )
            val pages = controller(FakePlaceData().apply { pinakesDown = true }, cache, details = details)
            pages.open(schober)
            advanceUntilIdle()
            val page = pages.state.value!!
            assertNotNull(page.card, "the cached card")
            assertEquals("Mo-Su 08:00-18:00", page.osm?.openingHours)
            assertEquals("+41 44 000 00 00", page.osm?.phone)
            assertEquals(5_000, page.shownAt, "the kept details are older than the cached card")
            assertEquals(listOf(schober.osm!!, schober.osm), details.asked)
        }

    @Test
    fun aCachedCardWithoutOsmDetailsShowsTheKeptOnesBeforePinakesAnswers() =
        runTest {
            val details = KeptRosa(everyElement = true)
            val cache = MemoryPlaceCache()
            controller(FakePlaceData(), cache, details = details).apply { open(schober) }
            advanceUntilIdle()
            val data = FakePlaceData().apply { cardGate = CompletableDeferred() }
            val pages = controller(data, cache, details = details)
            pages.open(schober)
            advanceUntilIdle()
            val page = pages.state.value!!
            assertTrue(PlacePart.Card in page.loading, "Pinakes has not answered")
            assertEquals("Mo-Su 08:00-18:00", page.osm?.openingHours)
            data.cardGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                "Mo-Su 08:00-18:00",
                pages.state.value!!
                    .osm
                    ?.openingHours,
            )
            assertEquals(listOf(schober.osm!!, schober.osm), details.asked, "asked once per opening")
        }

    @Test
    fun aCardWithOsmDetailsShowsOnlyThemAlsoFromTheCache() =
        runTest {
            val details = KeptRosa(everyElement = true)
            val cache = MemoryPlaceCache()
            val withFacts = FakePlaceData().apply { schoberFacts = mapOf("opening_hours" to "Mo-Fr 07:00-19:00") }
            controller(withFacts, cache, details = details).apply { open(schober) }
            advanceUntilIdle()
            val pages = controller(FakePlaceData().apply { pinakesDown = true }, cache, details = details)
            pages.open(schober)
            advanceUntilIdle()
            assertEquals(emptyList(), details.asked, "the card's OSM details win as a whole")
            val page = pages.state.value!!
            assertNull(page.keptDetails)
            assertEquals("Mo-Fr 07:00-19:00", page.osm?.openingHours)
            assertNull(page.osm?.phone, "no field comes from the kept details")
        }

    @Test
    fun keptTagsThePageDoesNotShowDoNotDateTheOfflineNote() =
        runTest {
            val pages =
                controller(
                    FakePlaceData().apply {
                        pinakesDown = true
                        nexusDown = true
                    },
                    details = { KeptDetails(mapOf("wheelchair" to "unknown"), fetchedAt = 5_000) },
                )
            pages.open(tapped)
            advanceUntilIdle()
            val page = pages.state.value!!
            assertNull(page.osm)
            assertNull(page.shownAt)
        }

    @Test
    fun aFailingDetailsLookupLeavesThePageWithoutThem() =
        runTest {
            val pages = controller(FakePlaceData().apply { pinakesDown = true }, details = { error("disk") })
            pages.open(tapped)
            advanceUntilIdle()
            assertNull(pages.state.value!!.osm)
        }

    /** The offline areas' answer for every point; records what was asked. */
    private class Hottingen : OfflineLocality {
        val asked = mutableListOf<Pair<OsmId?, GeoPoint>>()

        override fun localityOf(
            osm: OsmId?,
            point: GeoPoint,
            name: String?,
        ): String {
            asked += osm to point
            return "Hottingen, Zürich"
        }
    }

    private val tapped =
        PlaceRef(
            uri = "https://www.openstreetmap.org/node/42",
            name = "Café Rosa",
            point = GeoPoint(47.3703, 8.5560),
            osm = OsmId(OsmType.Node, 42),
            source = PlaceSource.Map,
            category = "Café",
        )

    @Test
    fun aPlaceTappedOfflineSaysWhereItIsFromTheOfflineAreas() =
        runTest {
            val data =
                FakePlaceData().apply {
                    pinakesDown = true
                    nexusDown = true
                }
            val localities = Hottingen()
            val pages = controller(data, localities = localities)
            pages.open(tapped)
            advanceUntilIdle()
            val page = pages.state.value!!
            assertEquals(listOf<Pair<OsmId?, GeoPoint>>(tapped.osm to tapped.point!!), localities.asked)
            assertEquals("Hottingen, Zürich", page.address)
            // Not the street address a long press copies.
            assertNull(page.streetAddress)
            assertEquals("Hottingen, Zürich", page.detailText(provider = null))
        }

    @Test
    fun theCardsAddressComesBeforeTheOfflineLocality() =
        runTest {
            val pages = controller(FakePlaceData(), localities = Hottingen())
            pages.open(schober.copy(point = GeoPoint(47.3700, 8.5440)))
            advanceUntilIdle()
            val page = pages.state.value!!
            assertEquals("Hottingen, Zürich", page.locality)
            assertEquals(page.card?.address, page.address)
            assertNotNull(page.address)
        }

    @Test
    fun onlyAPinnedPlaceWithNoAddressOrLocalityIsLookedUp() =
        runTest {
            val localities = Hottingen()
            val pages = controller(FakePlaceData(), localities = localities)
            val view = tapped.copy(uri = "view", pointIsView = true)
            val near = tapped.copy(uri = "near", query = "cafe")
            val addressed = tapped.copy(uri = "addressed", address = "Hottingerstrasse 1, 8032 Zürich")
            val fromSearch = tapped.copy(uri = "search", locality = "Fluntern, Zürich")
            val noPoint = tapped.copy(uri = "nopoint", point = null)
            for (place in listOf(view, near, addressed, fromSearch, noPoint)) {
                pages.open(place)
                advanceUntilIdle()
                assertNull(pages.state.value!!.locality, place.uri)
            }
            assertEquals(emptyList<Pair<OsmId?, GeoPoint>>(), localities.asked)
            assertEquals(
                "Fluntern, Zürich",
                pages.state.value!!
                    .copy(place = fromSearch)
                    .address,
            )
            // Typed coordinates never leave the phone, and the offline areas are on it.
            val typed = tapped.copy(uri = "geo:47.3703,8.556", name = null, osm = null, source = PlaceSource.Coordinates, category = null)
            pages.open(typed)
            advanceUntilIdle()
            assertEquals("Hottingen, Zürich", pages.state.value!!.address)
            assertEquals(listOf<Pair<OsmId?, GeoPoint>>(null to typed.point!!), localities.asked)
        }

    @Test
    fun aDroppedPinAsksNoServerAndSaysItsDistrictAndTown() =
        runTest {
            val localities = Hottingen()
            val data = FakePlaceData()
            val pages = controller(data, localities = localities)
            val pin = droppedPin(47.37031, 8.55601)
            pages.open(pin)
            advanceUntilIdle()
            val page = pages.state.value!!
            // The point a long-press dropped can be the user's home: neither Pinakes nor Nexus hears of it.
            assertEquals(emptyList(), data.askedCards)
            assertEquals(emptyList(), data.askedLinks)
            assertEquals(emptySet(), page.loading)
            assertEquals(emptySet(), page.failed)
            assertEquals("47.37031, 8.55601", page.place.title)
            assertEquals(DROPPED_PIN, page.place.category)
            assertEquals("Hottingen, Zürich", page.address)
            assertEquals(listOf<Pair<OsmId?, GeoPoint>>(null to GeoPoint(47.37031, 8.55601)), localities.asked)
        }

    @Test
    fun aSavedPointReopenedFromItsPinOrTheSavedPageAsksNoServer() =
        runTest {
            val data = FakePlaceData()
            val pages = controller(data, localities = Hottingen())
            // A dropped pin saved (phone or homeserver), then opened again from its badge and from the Saved page.
            val pin = SavedEntry("geo:47.37031,8.55601", Kept.Private, "Home", point = GeoPoint(47.37031, 8.55601), pointPage = true)
            pages.open(pin.toPlaceRef())
            advanceUntilIdle()
            pages.close()
            pages.open(savedPointPlace("geo:47.37031,8.55601", "Home")!!)
            advanceUntilIdle()
            assertEquals(emptyList(), data.askedCards)
            assertEquals(emptyList(), data.askedLinks)
            assertEquals(
                "Home",
                pages.state.value!!
                    .place.title,
            )
            assertEquals("Hottingen, Zürich", pages.state.value!!.address)
        }

    @Test
    fun aSavedPublicGeoPlaceReopenedFromItsPinAsksPinakesAndNexus() =
        runTest {
            val data = FakePlaceData()
            val pages = controller(data, localities = Hottingen())
            // A geo: link another app shared (or a Pubky place with a geo: URI), saved from its Pinakes page.
            val shared = SavedEntry("geo:47.37031,8.55601", Kept.Private, "Kunsthalle", point = GeoPoint(47.37031, 8.55601))
            pages.open(shared.toPlaceRef())
            advanceUntilIdle()
            assertEquals(listOf(listOf("geo:47.37031,8.55601")), data.askedCards)
            assertTrue(data.askedLinks.isNotEmpty(), "Nexus is asked for its tags and posts")
        }

    @Test
    fun theSamePlaceOpenedAgainKeepsItsLocalityOrLooksItUp() =
        runTest {
            val localities = Hottingen()
            val pages = controller(FakePlaceData().apply { pinakesDown = true }, localities = localities)
            // An offline search result, then the same café tapped on the map (no locality of its own).
            pages.open(tapped.copy(locality = "Fluntern, Zürich"))
            advanceUntilIdle()
            pages.open(tapped)
            advanceUntilIdle()
            assertEquals("Fluntern, Zürich", pages.state.value!!.address)
            assertEquals(emptyList<Pair<OsmId?, GeoPoint>>(), localities.asked)
            // Opened first with no point (a link not resolved yet), then with its point: looked up then.
            pages.close()
            pages.open(tapped.copy(point = null))
            advanceUntilIdle()
            pages.open(tapped)
            advanceUntilIdle()
            assertEquals("Hottingen, Zürich", pages.state.value!!.address)
            assertEquals(1, localities.asked.size)
        }

    @Test
    fun aSavedStreetAddressIsNotLookedUpAndALateLookupStaysOnItsOwnPage() =
        runTest {
            val cache = MemoryPlaceCache()
            val localities = Hottingen()
            val online = controller(FakePlaceData(), cache, localities = localities)
            val schoberAt = schober.copy(point = GeoPoint(47.3700, 8.5440))
            online.open(schoberAt)
            advanceUntilIdle()
            assertEquals(1, localities.asked.size)
            // Offline later: the saved card's street address shows from the start, with no lookup.
            val offline = controller(FakePlaceData().apply { pinakesDown = true }, cache, localities = localities)
            offline.open(schoberAt)
            advanceUntilIdle()
            assertEquals(1, localities.asked.size)
            assertNull(offline.state.value!!.locality)
            assertNotNull(offline.state.value!!.address)

            // A lookup still running when another place opens does not name the other place.
            val held = HeldDispatcher()
            val pages = controller(FakePlaceData().apply { pinakesDown = true }, localities = localities, io = held)
            pages.open(tapped)
            runCurrent()
            assertEquals(1, held.waiting)
            pages.open(tapped.copy(uri = "other", point = null))
            held.runAll()
            advanceUntilIdle()
            assertEquals(
                "other",
                pages.state.value!!
                    .place.uri,
            )
            assertNull(pages.state.value!!.locality)
        }

    @Test
    fun aFailingLookupLeavesThePageWithoutALocality() =
        runTest {
            val pages = controller(FakePlaceData(), localities = { _, _, _ -> error("disk") })
            pages.open(tapped)
            advanceUntilIdle()
            assertNull(pages.state.value!!.locality)
            assertEquals(emptySet<PlacePart>(), pages.state.value!!.loading)
        }

    @Test
    fun everyPartLoads() =
        runTest {
            val data = FakePlaceData()
            val pages = controller(data)
            pages.open(schober)
            assertEquals(PlacePart.entries.toSet(), pages.state.value!!.loading)
            advanceUntilIdle()
            val page = pages.state.value!!
            assertEquals(emptySet<PlacePart>(), page.loading)
            assertFalse(page.offline)
            assertEquals("Conditorei schober", page.card?.name)
            assertEquals("coffee", page.tags.first().label)
            assertEquals(1, page.posts.size)
            assertEquals(3, page.lists.size)
            assertEquals("Pinakes Test 10", page.people[PlaceFixtures.TESTER]?.name)
            // Nexus is asked about every spelling people used for the place.
            assertTrue(data.askedLinks.single().containsAll(listOf(FakePlaceData.SCHOBER, "https://www.openstreetmap.org/node/270803095/")))
        }

    @Test
    fun theSignedInUsersOwnTagComesFirstAndFriendsLeadTheTaggers() =
        runTest {
            val data = FakePlaceData()
            val pages = controller(data, viewer = PlaceFixtures.TESTER)
            pages.open(schober)
            advanceUntilIdle()
            val page = pages.state.value!!
            assertEquals(listOf<String?>(PlaceFixtures.TESTER), data.viewers.toList())
            assertEquals("cake", page.tags.first().label)
            assertTrue(page.tags.first().mine)
            val coffee = page.tags.single { it.label == "coffee" }
            assertEquals(true, page.people[coffee.taggers.first()]?.followed)
            assertTrue(page.lists.all { it.mine })
        }

    @Test
    fun nexusDownStillShowsTheCard() =
        runTest {
            val data = FakePlaceData().apply { nexusDown = true }
            val pages = controller(data)
            pages.open(schober)
            advanceUntilIdle()
            val page = pages.state.value!!
            assertEquals("Conditorei schober", page.card?.name)
            assertTrue(page.offline)
            assertTrue(PlacePart.Social in page.failed)
            assertFalse(PlacePart.Card in page.failed)
            assertNull(page.social)
        }

    @Test
    fun theHeaderShowsWhileTheCardLoads() =
        runTest {
            val data = FakePlaceData().apply { cardGate = CompletableDeferred() }
            val pages = controller(data)
            pages.open(schober)
            advanceUntilIdle()
            // The card is still loading: the header shows the place as it was opened.
            val page = pages.state.value!!
            assertTrue(PlacePart.Card in page.loading)
            assertTrue(page.cardLoading)
            assertEquals("Conditorei schober", page.place.title)
            data.cardGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(emptySet<PlacePart>(), pages.state.value!!.loading)
        }

    @Test
    fun aCachedCardCountsAsShownWhileTheFreshCardLoads() =
        runTest {
            val cache = MemoryPlaceCache()
            val data = FakePlaceData()
            val pages = controller(data, cache)
            pages.open(schober)
            advanceUntilIdle()
            pages.close()

            data.cardGate = CompletableDeferred()
            pages.open(schober)
            advanceUntilIdle()
            val page = pages.state.value!!
            assertTrue(PlacePart.Card in page.loading)
            assertEquals("Conditorei schober", page.card?.name)
            assertFalse(page.cardLoading)
            data.cardGate!!.complete(Unit)
            advanceUntilIdle()
            assertFalse(pages.state.value!!.cardLoading)
        }

    @Test
    fun aPlaceReopensOfflineFromTheCache() =
        runTest {
            val cache = MemoryPlaceCache()
            val data = FakePlaceData()
            val pages = controller(data, cache)
            pages.open(schober)
            advanceUntilIdle()
            val saved = assertNotNull(cache.saved[FakePlaceData.SCHOBER])
            assertEquals(4, saved.social?.tags?.size)
            pages.close()

            data.pinakesDown = true
            data.nexusDown = true
            pages.open(schober)
            advanceUntilIdle()
            val page = pages.state.value!!
            assertTrue(page.offline)
            assertEquals(saved.savedAt, page.savedAt)
            assertEquals("Conditorei schober", page.card?.name)
            assertEquals(4, page.tags.size)
            assertEquals(3, page.lists.size)
            assertEquals(1, page.posts.size)
            // Nothing new to keep: the saved copy stays as it was.
            assertEquals(saved, cache.saved[FakePlaceData.SCHOBER])
        }

    @Test
    fun aCachedPageShowsNothingAsTheirsToAnotherAccountOrSignedOut() =
        runTest {
            val cache = MemoryPlaceCache()
            val signedIn = controller(FakePlaceData(), cache, viewer = PlaceFixtures.TESTER)
            signedIn.open(schober)
            advanceUntilIdle()
            assertTrue(
                signedIn.state.value!!
                    .tags
                    .any { it.mine },
            )
            assertTrue(
                signedIn.state.value!!
                    .lists
                    .all { it.mine },
            )
            assertEquals(PlaceFixtures.TESTER, cache.saved[FakePlaceData.SCHOBER]?.viewer)

            // Signed out, everything offline: the saved page, with nothing of the old account's.
            val down =
                FakePlaceData().apply {
                    pinakesDown = true
                    nexusDown = true
                }
            val signedOut = controller(down, cache, viewer = null)
            signedOut.open(schober)
            advanceUntilIdle()
            val page = signedOut.state.value!!
            assertTrue(page.offline)
            assertEquals(4, page.tags.size)
            assertFalse(page.tags.any { it.mine })
            assertFalse(page.lists.any { it.mine })
            assertFalse(page.people.values.any { it.followed })

            // Another account: its own lists are its own again.
            val other = controller(down, cache, viewer = "someoneelse").also { it.open(schober) }
            advanceUntilIdle()
            assertFalse(
                other.state.value!!
                    .tags
                    .any { it.mine },
            )
            assertFalse(
                other.state.value!!
                    .lists
                    .any { it.mine },
            )
        }

    @Test
    fun withoutNexusAPlaceOfAnOfflineAreaShowsItsKeptTagsAlsoWhenNeverOpened() =
        runTest {
            val data =
                FakePlaceData().apply {
                    pinakesDown = true
                    nexusDown = true
                }
            val kept = KeptSchober(fetchedAt = 1_000)
            val pages = controller(data, MemoryPlaceCache(), viewer = PlaceFixtures.TESTER, kept = kept)
            pages.open(schober)
            advanceUntilIdle()
            val page = pages.state.value!!
            assertTrue(page.offline)
            assertNull(page.card)
            assertEquals(listOf("kept"), page.tags.map { it.label })
            // Read with no viewer; the signed-in user is one of its taggers, so it is theirs.
            assertTrue(page.tags.single().mine)
            // "Offline: showing what was saved on" the day the area's tags were fetched.
            assertNull(page.savedAt)
            assertEquals(1_000, page.shownAt)
            // The links of the place as opened (its OSM element is the same link here).
            assertEquals(listOf(FakePlaceData.SCHOBER), kept.asked.single())
        }

    @Test
    fun keptTagsAreShownOnlyWhenNexusDoesNotAnswerAndTheCacheIsOlder() =
        runTest {
            val cache = MemoryPlaceCache()
            val online = KeptSchober(fetchedAt = Long.MAX_VALUE)
            val first = controller(FakePlaceData(), cache, kept = online)
            first.open(schober)
            advanceUntilIdle()
            // Nexus answered: nothing kept is read.
            assertTrue(online.asked.isEmpty())
            assertEquals(
                4,
                first.state.value!!
                    .tags.size,
            )
            val saved = assertNotNull(cache.saved[FakePlaceData.SCHOBER])

            val down =
                FakePlaceData().apply {
                    pinakesDown = true
                    nexusDown = true
                }
            // The page saved after the area's tags were fetched: the saved page.
            val newerCache = controller(down, cache, kept = KeptSchober(fetchedAt = saved.savedAt - 1)).also { it.open(schober) }
            advanceUntilIdle()
            assertEquals(
                4,
                newerCache.state.value!!
                    .tags.size,
            )
            assertEquals(saved.savedAt, newerCache.state.value!!.savedAt)

            // The area's tags are newer: they replace the saved ones; the card and posts stay the saved ones,
            // and the page names the older date.
            val newerKept = controller(down, cache, kept = KeptSchober(fetchedAt = saved.savedAt + 1)).also { it.open(schober) }
            advanceUntilIdle()
            val page = newerKept.state.value!!
            assertEquals(listOf("kept"), page.tags.map { it.label })
            assertFalse(page.tags.single().mine)
            assertEquals("Conditorei schober", page.card?.name)
            assertEquals(1, page.posts.size)
            assertEquals(saved.savedAt, page.shownAt)
        }

    @Test
    fun aPageSavedWithKeptTagsKeepsTheirDate() =
        runTest {
            val cache = MemoryPlaceCache()
            // Pinakes answers, Nexus does not: the card is new, the tags are the area's.
            val pages =
                controller(FakePlaceData().apply { nexusDown = true }, cache, kept = KeptSchober(fetchedAt = 1_000), clock = at(5_000))
            pages.open(schober)
            advanceUntilIdle()
            val saved = assertNotNull(cache.saved[FakePlaceData.SCHOBER])
            assertEquals("Conditorei schober", saved.card?.name)
            assertEquals(listOf("kept"), saved.social?.tags?.map { it.label })
            // The copy is as new as its card (the cache keeps the newest), its tags keep their date: the
            // next offline open names it, and newer kept tags still win.
            assertEquals(5_000, saved.savedAt)
            assertEquals(1_000, saved.socialAt)
            val offline =
                FakePlaceData().apply {
                    pinakesDown = true
                    nexusDown = true
                }
            val again = controller(offline, cache, kept = KeptSchober(fetchedAt = 1_001), clock = at(6_000)).also { it.open(schober) }
            advanceUntilIdle()
            assertEquals(1_001, again.state.value!!.shownAt)
        }

    @Test
    fun aCardSavedEarlierKeepsItsDateWhenThePageIsSavedAgainWithoutPinakes() =
        runTest {
            val cache = MemoryPlaceCache()
            controller(FakePlaceData().apply { nexusDown = true }, cache, clock = at(1_000)).open(schober)
            advanceUntilIdle()
            // No tags were shown: none has a date.
            assertNull(assertNotNull(cache.saved[FakePlaceData.SCHOBER]).socialAt)
            controller(FakePlaceData(), cache, clock = at(2_000)).open(schober)
            advanceUntilIdle()
            // Later Pinakes is down: the card shown is the one of 2,000, the tags are new.
            controller(FakePlaceData().apply { pinakesDown = true }, cache, clock = at(3_000)).open(schober)
            advanceUntilIdle()
            val saved = assertNotNull(cache.saved[FakePlaceData.SCHOBER])
            assertEquals(3_000, saved.savedAt)
            assertEquals(2_000, saved.contentAt)
            assertEquals(3_000, saved.socialAt)
            val down =
                FakePlaceData().apply {
                    pinakesDown = true
                    nexusDown = true
                }
            val offline = controller(down, cache, clock = at(4_000)).also { it.open(schober) }
            advanceUntilIdle()
            assertEquals(2_000, offline.state.value!!.shownAt)
        }

    @Test
    fun tagsFetchedAgainAreNotReplacedByOlderKeptOnesWhenAnotherPartFailed() =
        runTest {
            val cache = MemoryPlaceCache()
            controller(FakePlaceData(), cache, clock = at(1_000)).open(schober)
            advanceUntilIdle()
            // Opened again later: the tags load, only the posts do not.
            controller(FakePlaceData().apply { postsDown = true }, cache, clock = at(3_000)).open(schober)
            advanceUntilIdle()
            val saved = assertNotNull(cache.saved[FakePlaceData.SCHOBER])
            assertEquals(3_000, saved.savedAt)
            assertEquals(3_000, saved.socialAt)

            // Offline, with an area's tags fetched between the two opens: the newer saved tags stay.
            val down =
                FakePlaceData().apply {
                    pinakesDown = true
                    nexusDown = true
                }
            val page = controller(down, cache, kept = KeptSchober(fetchedAt = 2_000)).also { it.open(schober) }
            advanceUntilIdle()
            assertEquals(
                4,
                page.state.value!!
                    .tags.size,
            )
            // The note names the posts' date: they are the ones saved at 1,000.
            assertEquals(1_000, saved.contentAt)
            assertEquals(1_000, page.state.value!!.shownAt)
        }

    @Test
    fun theListCountIsTheCardsNotThePageShown() =
        runTest {
            val pages = controller(FakePlaceData())
            pages.open(schober)
            advanceUntilIdle()
            val page = pages.state.value!!
            assertEquals(3, page.listCount)
            val bigger = page.copy(card = page.card!!.copy(collections = 25))
            assertEquals(25, bigger.listCount)
        }

    @Test
    fun postsArePaged() =
        runTest {
            val one = parseNexusPosts(PlaceFixtures.read("nexus-replies-schober.json"), PlaceFixtures.NEXUS).single()
            val data = FakePlaceData().apply { replies = (0 until 45).map { one.copy(uri = "${one.uri}-$it", indexedAt = 1000L - it) } }
            val pages = controller(data)
            pages.open(schober)
            advanceUntilIdle()
            assertEquals(
                20,
                pages.state.value!!
                    .posts.size,
            )
            assertTrue(pages.state.value!!.morePosts)
            pages.loadMorePosts()
            advanceUntilIdle()
            pages.loadMorePosts()
            advanceUntilIdle()
            val page = pages.state.value!!
            assertEquals(45, page.posts.size)
            assertFalse(page.morePosts)
            assertEquals(listOf(0, 20, 40), data.replyPages)
            // Newest first.
            assertEquals(page.posts.sortedByDescending { it.indexedAt }, page.posts)
        }

    @Test
    fun aPlacePinakesDoesNotKnowHasNoCardAndNoLists() =
        runTest {
            val pages = controller(FakePlaceData())
            pages.open(PlaceRef(uri = "geo:47.37,8.54", name = null, point = null, osm = null, source = PlaceSource.Coordinates))
            advanceUntilIdle()
            val page = pages.state.value!!
            assertNull(page.card)
            assertEquals(emptyList(), page.lists)
            assertEquals(emptyList(), page.tags)
            assertFalse(page.offline)
        }

    @Test
    fun aNewerPlaceReplacesOneStillLoading() =
        runTest {
            val data = FakePlaceData().apply { cardGate = CompletableDeferred() }
            val pages = controller(data)
            pages.open(schober)
            advanceUntilIdle()
            val other = PlaceRef(uri = "geo:1,2", name = "Other", point = null, osm = null, source = PlaceSource.Coordinates)
            pages.open(other)
            data.cardGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                "geo:1,2",
                pages.state.value!!
                    .place.uri,
            )
            assertNull(pages.state.value!!.card)
        }

    private fun at(ms: Long) =
        object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(ms)
        }
}
