package to.pubky.maps.offline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import to.pubky.maps.map.MapPlacesLayer
import to.pubky.maps.place.CollectionRefs
import to.pubky.maps.place.GeoBox
import to.pubky.maps.place.KeptSocial
import to.pubky.maps.place.MapPlace
import to.pubky.maps.place.MapPlacesPage
import to.pubky.maps.place.PlaceCardInfo
import to.pubky.maps.place.PlaceData
import to.pubky.maps.place.PlaceFixtures
import to.pubky.maps.place.PlaceList
import to.pubky.maps.place.PlacePerson
import to.pubky.maps.place.PlacePost
import to.pubky.maps.place.PlaceSocial
import to.pubky.maps.place.PlaceTag
import to.pubky.maps.place.ResourceView
import to.pubky.maps.place.ViewTag
import to.pubky.maps.place.mergeResourceViews
import to.pubky.maps.place.parseMapPlaces
import to.pubky.maps.place.parseNexusResourceViews
import to.pubky.maps.search.SourceAnswer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Pinakes' places of any box: [perPage] places a page, [pages] pages. */
private class PinakesPlaces : PlaceData {
    val asked = mutableListOf<Pair<GeoBox, String?>>()
    var down = false
    var pages = 1
    var perPage = 3

    /** Boxes Pinakes refuses (a server error for that region, say). */
    var refused = emptySet<GeoBox>()

    /** Runs before each answer. */
    var onAsk: () -> Unit = {}

    /** Other links people referenced for each place. */
    var refsPerPlace = 0

    /** What Nexus was asked: the links, and for whom. */
    val askedViews = mutableListOf<Pair<List<String>, String?>>()
    var nexusDown = false

    override suspend fun placesIn(
        bbox: GeoBox,
        cursor: String?,
    ): SourceAnswer<MapPlacesPage> {
        asked += bbox to cursor
        onAsk()
        if (down || bbox in refused) return SourceAnswer.Unreachable
        val page = cursor?.toInt() ?: 0
        val places =
            (0 until perPage).map { i ->
                MapPlace("geo:${bbox.south},${bbox.west};p=$page;i=$i", "Place $page.$i", bbox.south, bbox.west, "Cafe", "amenity/cafe")
            }
        val refs = places.associate { place -> place.uri to (1..refsPerPlace).map { "${place.uri};ref=$it" } }
        return SourceAnswer.Answered(MapPlacesPage(places, (page + 1).takeIf { it < pages }?.toString(), refs), "p")
    }

    override suspend fun card(uris: List<String>): SourceAnswer<PlaceCardInfo?> = error("not used")

    override suspend fun collections(
        cardId: String,
        viewer: String?,
    ): SourceAnswer<CollectionRefs> = error("not used")

    /** Nexus knows the first place of each page, and each link of it has a tag "cosy" by one person. */
    override suspend fun views(
        uris: List<String>,
        viewer: String?,
    ): SourceAnswer<List<ResourceView>> {
        askedViews += uris to viewer
        if (nexusDown) return SourceAnswer.Unreachable
        val known = uris.filter { it.substringBefore(";ref=").endsWith(";i=0") }
        return SourceAnswer.Answered(
            known.map { uri ->
                ResourceView(
                    id = "id:$uri",
                    uri = uri,
                    tags = listOf(ViewTag("cosy", listOf("person:$uri"), 1, null)),
                    replies = 1,
                    embeds = 0,
                )
            },
            "n",
        )
    }

    override suspend fun posts(
        resourceId: String,
        embeds: Boolean,
        skip: Int,
        limit: Int,
    ): SourceAnswer<List<PlacePost>> = error("not used")

    override suspend fun people(
        ids: List<String>,
        viewer: String?,
    ): SourceAnswer<List<PlacePerson>> = error("not used")

    override suspend fun lists(
        postUris: List<String>,
        viewer: String?,
    ): SourceAnswer<List<PlaceList>> = error("not used")
}

private class MemoryStore : OfflinePubkyStore {
    data class Kept(
        val box: GeoBox,
        val fetchedAt: Long,
        val places: List<MapPlace>,
        val links: Map<String, String>,
        val social: Map<String, PlaceSocial> = emptyMap(),
        val socialAt: Long? = null,
    ) {
        val socialFetched: Boolean get() = !KeptArea(fetchedAt, socialAt).socialMissing
    }

    val kept = mutableMapOf<String, Kept>()
    var broken = false

    override fun areas(): Map<String, KeptArea> {
        if (broken) error("disk")
        return kept.mapValues { KeptArea(it.value.fetchedAt, it.value.socialAt) }
    }

    override fun replace(
        area: String,
        box: GeoBox,
        fetchedAt: Long,
        places: List<MapPlace>,
        links: Map<String, String>,
    ) {
        val old = kept[area]
        kept[area] = Kept(box, fetchedAt, places, links, old?.social.orEmpty(), old?.socialAt)
    }

    override fun links(area: String): Map<String, String> = kept[area]?.links.orEmpty()

    override fun replaceSocial(
        area: String,
        fetchedAt: Long,
        social: Map<String, PlaceSocial>,
    ) {
        kept[area] = kept.getValue(area).copy(social = social, socialAt = fetchedAt)
    }

    override fun delete(area: String) {
        kept.remove(area)
    }

    override fun placesIn(
        box: GeoBox,
        limit: Long,
    ): List<MapPlace>? = error("not used")

    override fun socialOf(uris: List<String>): KeptSocial? = error("not used")
}

private class TestClock(
    val scope: TestScope,
) : Clock {
    override fun now() = Instant.fromEpochMilliseconds(1_800_000_000_000 + scope.testScheduler.currentTime)
}

@OptIn(ExperimentalCoroutinesApi::class)
class OfflinePubkyPlacesTest {
    private val zurich = AreaBounds(west = 8.51, south = 47.36, east = 8.56, north = 47.39)
    private val bern = AreaBounds(west = 7.42, south = 46.93, east = 7.47, north = 46.96)

    private fun area(
        id: String,
        bounds: AreaBounds?,
        status: OfflineMapStatus = OfflineMapStatus.Ready,
    ) = OfflineMap(id = id, name = id, createdAt = 0, status = status, bytes = 0, bounds = bounds)

    private class Running(
        val maps: MutableStateFlow<OfflineMapsState>,
        val data: PinakesPlaces,
        val store: MemoryStore,
        var unmetered: Boolean,
    )

    /**
     * Runs [test] with the places of the areas of [maps] kept from the start of the test clock: the
     * first look at them runs at the test's first wait. Stopped after it.
     */
    private fun keeping(
        vararg maps: OfflineMap,
        unmetered: Boolean = true,
        test: suspend TestScope.(Running) -> Unit,
    ) = runTest {
        val running =
            Running(MutableStateFlow<OfflineMapsState>(OfflineMapsState.Ready(maps.toList())), PinakesPlaces(), MemoryStore(), unmetered)
        val job = Job()
        OfflinePubkyPlaces(
            maps = running.maps,
            data = running.data,
            store = running.store,
            scope = CoroutineScope(coroutineContext + job),
            unmetered = { running.unmetered },
            clock = TestClock(this),
            io = StandardTestDispatcher(testScheduler),
        ).start()
        try {
            test(running)
        } finally {
            job.cancel()
        }
    }

    private fun TestScope.after(time: Duration) {
        advanceTimeBy(time)
        runCurrent()
    }

    @Test
    fun aListedAreasPlacesAreFetchedForTheAreaOnTheLayersGridAndKept() =
        keeping(area("zh", zurich, OfflineMapStatus.Downloading(percent = 10))) { running ->
            running.data.pages = 3
            after(5.seconds)
            val box = GeoBox(8.5, 47.3125, 8.5625, 47.4375)
            assertEquals(box, GeoBox(8.51, 47.36, 8.56, 47.39).snappedOutward(MapPlacesLayer.GRID_DEGREES))
            assertEquals(listOf(box to null, box to "1", box to "2"), running.data.asked)
            val kept = running.store.kept.getValue("zh")
            assertEquals(box, kept.box)
            assertEquals(9, kept.places.size)
            assertEquals(1_800_000_000_000, kept.fetchedAt)
        }

    @Test
    fun aFetchTakesAtMostTwentyPagesOneEveryOneAndAHalfSeconds() =
        keeping(area("zh", zurich)) { running ->
            running.data.pages = 50
            after(27.seconds)
            // 18 gaps of 1.5 s before the 19th page.
            assertEquals(19, running.data.asked.size)
            after(1.5.seconds)
            assertEquals(20, running.data.asked.size)
            // Then Nexus, 1.5 s later: 60 places in one request.
            after(1.5.seconds)
            assertEquals(1, running.data.askedViews.size)
            assertEquals(
                60,
                running.store.kept
                    .getValue("zh")
                    .places.size,
            )
            after(1.hours)
            assertEquals(20, running.data.asked.size)
        }

    @Test
    fun aDeletedAreasPlacesGoAndANewAreasCome() =
        keeping(area("zh", zurich)) { running ->
            after(5.seconds)
            assertEquals(setOf("zh"), running.store.kept.keys)
            running.maps.value = OfflineMapsState.Ready(listOf(area("be", bern)))
            after(5.seconds)
            assertEquals(setOf("be"), running.store.kept.keys)
            assertEquals(
                GeoBox(7.375, 46.875, 7.5, 47.0),
                running.store.kept
                    .getValue("be")
                    .box,
            )
        }

    @Test
    fun withoutPinakesNothingIsKeptAndAllAreTriedAgainLater() =
        keeping(area("zh", zurich), area("be", bern), area("lost", bounds = null)) { running ->
            running.data.down = true
            after(5.seconds)
            // The sync stops at its second failure, not one request per area.
            assertEquals(2, running.data.asked.size)
            assertTrue(running.store.kept.isEmpty())
            running.data.down = false
            after(OfflinePubkyPlaces.RETRY)
            // An area without bounds (its pack lost its metadata) has nothing to ask for.
            assertEquals(setOf("zh", "be"), running.store.kept.keys)
        }

    @Test
    fun areasThatFailGoLastSoTheOthersAreKept() =
        keeping(area("bad", bern), area("bad2", AreaBounds(6.1, 46.18, 6.16, 46.22)), area("zh", zurich)) { running ->
            val badBoxes = setOf(GeoBox(7.375, 46.875, 7.5, 47.0), GeoBox(6.0625, 46.125, 6.1875, 46.25))
            running.data.refused = badBoxes
            after(5.seconds)
            // Two failures in a row: the sync stops before Zürich.
            assertEquals(badBoxes.toList(), running.data.asked.map { it.first })
            assertTrue(running.store.kept.isEmpty())
            after(OfflinePubkyPlaces.RETRY)
            // Zürich goes first now; the failing areas are tried after it.
            assertEquals(GeoBox(8.5, 47.3125, 8.5625, 47.4375), running.data.asked[2].first)
            assertEquals(setOf("zh"), running.store.kept.keys)
            assertEquals(5, running.data.asked.size)
        }

    @Test
    fun anAreaDeletedDuringItsFetchIsNotKept() =
        keeping { running ->
            running.data.onAsk = { running.maps.value = OfflineMapsState.Ready(emptyList()) }
            running.maps.value = OfflineMapsState.Ready(listOf(area("be", bern)))
            after(5.seconds)
            assertEquals(1, running.data.asked.size)
            assertTrue(running.store.kept.isEmpty())
        }

    @Test
    fun aDownloadsProgressAsksNothingAgain() =
        keeping(area("zh", zurich, OfflineMapStatus.Downloading(percent = 10))) { running ->
            after(5.seconds)
            running.maps.value = OfflineMapsState.Ready(listOf(area("zh", zurich, OfflineMapStatus.Downloading(percent = 60))))
            after(5.seconds)
            running.maps.value = OfflineMapsState.Ready(listOf(area("zh", zurich).copy(bytes = 20_000_000)))
            after(5.seconds)
            assertEquals(1, running.data.asked.size)
        }

    @Test
    fun keptPlacesAreFetchedAgainAfterADayOnlyOnAnUnmeteredNetwork() =
        keeping(area("zh", zurich), unmetered = false) { running ->
            // The first fetch does not wait for Wi-Fi: the user just asked for the area.
            after(5.seconds)
            assertEquals(1, running.data.asked.size)
            after(1.days)
            assertEquals(1, running.data.asked.size)
            running.unmetered = true
            after(OfflinePubkyPlaces.RETRY)
            assertEquals(2, running.data.asked.size)
            assertTrue(
                running.store.kept
                    .getValue("zh")
                    .fetchedAt > 1_800_000_000_000 + 1.days.inWholeMilliseconds,
            )
            after(23.hours)
            assertEquals(2, running.data.asked.size)
        }

    @Test
    fun aFailingDiskIsTriedAgainOnTheNextTick() =
        keeping(area("zh", zurich)) { running ->
            running.store.broken = true
            after(5.seconds)
            assertTrue(running.data.asked.isEmpty())
            running.store.broken = false
            after(OfflinePubkyPlaces.RETRY)
            assertEquals(setOf("zh"), running.store.kept.keys)
        }

    @Test
    fun theTagsOfAnAreasPlacesComeFromNexusWithNoViewerAWholePlaceARequest() =
        keeping(area("zh", zurich)) { running ->
            running.data.pages = 2
            running.data.perPage = 30
            running.data.refsPerPlace = 3
            after(10.seconds)
            // 60 places of 4 links each: 25 places a request, in 3 requests after the 2 pages, 1.5 s apart.
            assertEquals(listOf(100, 100, 40), running.data.askedViews.map { it.first.size })
            assertTrue(running.data.askedViews.all { it.second == null })
            val kept = running.store.kept.getValue("zh")
            assertTrue(kept.socialFetched)
            // Only the places Nexus knows, each merged over its links as the place page merges them.
            val first = "geo:47.3125,8.5;p=0;i=0"
            assertEquals(setOf(first, "geo:47.3125,8.5;p=1;i=0"), kept.social.keys)
            val cosy =
                kept.social
                    .getValue(first)
                    .tags
                    .single()
            assertEquals(
                PlaceTag(
                    "cosy",
                    count = 4,
                    taggers =
                        (
                            listOf(first) +
                                (1..3).map {
                                    "$first;ref=$it"
                                }
                        ).map { "person:$it" },
                    mine = false,
                ),
                cosy,
            )
            assertEquals(4, kept.social.getValue(first).replyCount)
        }

    @Test
    fun withoutNexusThePlacesAreKeptAndOnlyNexusIsAskedAgainAtTheNextTryOnAnyNetwork() =
        keeping(area("zh", zurich), unmetered = false) { running ->
            running.data.nexusDown = true
            running.data.refsPerPlace = 1
            after(5.seconds)
            val kept = running.store.kept.getValue("zh")
            assertEquals(3, kept.places.size)
            assertFalse(kept.socialFetched)
            assertEquals(1, running.data.asked.size)
            after(OfflinePubkyPlaces.RETRY)
            // Still down: one Nexus request a try, Pinakes is not asked again.
            assertEquals(1, running.data.asked.size)
            assertEquals(2, running.data.askedViews.size)
            running.data.nexusDown = false
            after(OfflinePubkyPlaces.RETRY)
            assertEquals(1, running.data.asked.size)
            // The kept places' links, as at the fetch.
            assertEquals(
                running.data.askedViews
                    .first()
                    .first,
                running.data.askedViews
                    .last()
                    .first,
            )
            val again = running.store.kept.getValue("zh")
            assertTrue(again.socialFetched)
            assertEquals(1, again.social.size)
            assertEquals(1_800_000_000_000 + 2 * OfflinePubkyPlaces.RETRY.inWholeMilliseconds, again.socialAt)
            after(OfflinePubkyPlaces.RETRY)
            assertEquals(3, running.data.askedViews.size)
        }

    @Test
    fun aRefreshWithoutNexusKeepsTheOldTagsWithTheirDate() =
        keeping(area("zh", zurich)) { running ->
            after(5.seconds)
            val first = running.store.kept.getValue("zh")
            // Dated when Nexus answered, 1.5 s after the page of places.
            assertEquals(first.fetchedAt + 1_500, first.socialAt)
            assertEquals(1, first.social.size)
            running.data.nexusDown = true
            after(1.days)
            val refreshed = running.store.kept.getValue("zh")
            assertTrue(refreshed.fetchedAt > first.fetchedAt)
            assertEquals(first.socialAt, refreshed.socialAt)
            assertEquals(first.social, refreshed.social)
            assertFalse(refreshed.socialFetched)
        }

    @Test
    fun withoutNexusASyncStopsAtItsSecondFailure() =
        keeping(area("zh", zurich), area("be", bern), area("ge", AreaBounds(6.1, 46.18, 6.16, 46.22))) { running ->
            running.data.nexusDown = true
            after(10.seconds)
            assertEquals(2, running.data.askedViews.size)
            assertEquals(setOf("zh", "be"), running.store.kept.keys)
        }

    @Test
    fun nexusIsAskedAtMostTwentyTimesAnArea() =
        keeping(area("zh", zurich)) { running ->
            running.data.perPage = 500
            running.data.pages = 5
            running.data.refsPerPlace = 1
            after(2.minutes)
            // 2,500 places of 2 links: 50 a request, the first 1,000 of them asked about, so of the
            // places Nexus knows (the first of each page of 500) those of pages 0 and 1.
            assertEquals(OfflinePubkyPlaces.MAX_SOCIAL_REQUESTS, running.data.askedViews.size)
            assertEquals(
                2,
                running.store.kept
                    .getValue("zh")
                    .social.size,
            )
            assertEquals(
                2_500,
                running.store.kept
                    .getValue("zh")
                    .places.size,
            )
        }

    @Test
    fun anAreaKeptWithoutTagsHasThemFetchedAtOnce() =
        keeping(area("zh", zurich), unmetered = false) { running ->
            // As an area kept by the version before the tags, just now: kept before the first look.
            val place = MapPlace("geo:47.3125,8.5;p=0;i=0", "Place", 47.3125, 8.5, null, null)
            running.store.kept["zh"] =
                MemoryStore.Kept(GeoBox(8.5, 47.3125, 8.5625, 47.4375), 1_800_000_000_000, listOf(place), mapOf(place.uri to place.uri))
            after(5.seconds)
            // Only Nexus, about the kept places.
            assertTrue(running.data.asked.isEmpty())
            assertEquals(
                listOf(place.uri),
                running.data.askedViews
                    .single()
                    .first,
            )
            val kept = running.store.kept.getValue("zh")
            assertTrue(kept.socialFetched)
            assertEquals(setOf(place.uri), kept.social.keys)
        }

    @Test
    fun socialBatchesKeepAPlacesLinksTogetherAndAskALinkOnce() {
        fun place(uri: String) = MapPlace(uri, null, 0.0, 0.0, null, null)
        val links =
            OfflinePubkyPlaces.socialLinks(
                listOf(place("a"), place("b"), place("c"), place("d")),
                mapOf(
                    "a" to (1..60).map { "a$it" } + "d",
                    "b" to (1..50).map { "b$it" } + "a1",
                    "c" to (1..200).map { "c$it" },
                ),
            )
        // "a1" is a's, once; "d" is d's own uri, not a ref of a; c is cut at 100 links.
        assertEquals("a", links["a1"])
        assertEquals("d", links["d"])
        assertEquals(100, links.values.count { it == "c" })
        val batches = OfflinePubkyPlaces.socialBatches(links)
        assertEquals(listOf(61, 51, 100, 1), batches.map { it.size })
        assertEquals(setOf("c"), batches[2].values.toSet())
    }

    @Test
    fun spellingsOfOneLinkHaveOneKey() {
        val key = OfflinePubkyPlaces.linkKey("https://www.openstreetmap.org/node/270803095")
        assertEquals("openstreetmap.org/node/270803095", key)
        assertEquals(key, OfflinePubkyPlaces.linkKey("http://openstreetmap.org/node/270803095/"))
        assertEquals(key, OfflinePubkyPlaces.linkKey("https://WWW.OpenStreetMap.org/node/270803095"))
        assertEquals("geo:47.1,8.5", OfflinePubkyPlaces.linkKey("geo:47.1,8.5"))
    }

    @Test
    fun theRecordedZurichPlacesGetTheirRecordedTags() =
        runTest {
            // Pinakes' recorded places of Zürich and Nexus' recorded views, with a Nexus that spells
            // one link its own way (no www.): matched to its place all the same.
            val page = parseMapPlaces(PlaceFixtures.read("pinakes-bbox-zurich.json"))
            val links = OfflinePubkyPlaces.socialLinks(page.places, page.refs)
            val views = parseNexusResourceViews(PlaceFixtures.read("nexus-by-ids-zurich.json"))
            val kunsthaus = "https://www.openstreetmap.org/relation/3768127"
            val schober = "https://www.openstreetmap.org/node/270803095"
            val store = MemoryStore()
            val data =
                object : PlaceData by PinakesPlaces() {
                    override suspend fun placesIn(
                        bbox: GeoBox,
                        cursor: String?,
                    ) = SourceAnswer.Answered(page, "p")

                    override suspend fun views(
                        uris: List<String>,
                        viewer: String?,
                    ): SourceAnswer<List<ResourceView>> =
                        SourceAnswer.Answered(
                            views.filter { it.uri in uris }.map {
                                if (it.uri ==
                                    kunsthaus
                                ) {
                                    it.copy(uri = "https://openstreetmap.org/relation/3768127")
                                } else {
                                    it
                                }
                            },
                            "n",
                        )
                }
            val job = Job()
            OfflinePubkyPlaces(
                maps = MutableStateFlow(OfflineMapsState.Ready(listOf(area("zh", zurich)))),
                data = data,
                store = store,
                scope = CoroutineScope(coroutineContext + job),
                unmetered = { true },
                clock = TestClock(this),
                io = StandardTestDispatcher(testScheduler),
            ).start()
            advanceTimeBy(10.seconds)
            runCurrent()
            job.cancel()
            assertTrue(links.size > page.places.size)
            val social = store.kept.getValue("zh").social
            assertTrue(social.keys.containsAll(listOf(kunsthaus, schober)))
            assertEquals(mergeResourceViews(views.filter { it.uri == schober }), social.getValue(schober))
            assertEquals(
                listOf("art", "museum"),
                social
                    .getValue(kunsthaus)
                    .tags
                    .map { it.label }
                    .sorted(),
            )
        }

    @Test
    fun aKeptAreaIsNotAskedAgainWithinADay() =
        keeping(area("zh", zurich)) { running ->
            after(5.seconds)
            after(23.hours + 50.minutes)
            assertEquals(1, running.data.asked.size)
        }
}
