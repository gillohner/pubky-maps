package to.pubky.maps.place

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.search.SourceAnswer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Answers from the recorded fixtures; each source can be made unreachable or held back. */
private class FakePlaceData : PlaceData {
    var pinakesDown = false
    var nexusDown = false
    var cardGate: CompletableDeferred<Unit>? = null
    val viewers = mutableListOf<String?>()
    val askedLinks = mutableListOf<List<String>>()
    val replyPages = mutableListOf<Int>()

    /** Replies Nexus has for the Schober (newest first), 20 per page. */
    var replies: List<PlacePost> = parseNexusPosts(PlaceFixtures.read("nexus-replies-schober.json"), PlaceFixtures.NEXUS)

    private fun <T> pinakes(value: () -> T): SourceAnswer<T> =
        if (pinakesDown) SourceAnswer.Unreachable else SourceAnswer.Answered(value(), "p")

    private fun <T> nexus(value: () -> T): SourceAnswer<T> =
        if (nexusDown) SourceAnswer.Unreachable else SourceAnswer.Answered(value(), "n")

    override suspend fun card(uris: List<String>): SourceAnswer<PlaceCardInfo?> {
        cardGate?.await()
        return pinakes {
            if (SCHOBER in uris) parsePinakesCard(PlaceFixtures.read("pinakes-card-schober.json")) else null
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
        nexus {
            if (embeds) {
                parseNexusPosts(PlaceFixtures.read("nexus-embeds-schober.json"), PlaceFixtures.NEXUS)
            } else {
                replyPages += skip
                replies.drop(skip).take(limit)
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
    ) = PlacePageController(
        data = data,
        cache = cache,
        scope = this,
        viewer = { viewer },
        io = StandardTestDispatcher(testScheduler),
    )

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
            assertEquals("Conditorei schober", page.place.title)
            data.cardGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(emptySet<PlacePart>(), pages.state.value!!.loading)
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
    fun theListCountIsTheCardsNotThePageShown() =
        runTest {
            val pages = controller(FakePlaceData())
            pages.open(schober)
            advanceUntilIdle()
            val page = pages.state.value!!
            assertEquals(3, page.listCount)
            val bigger = page.copy(card = page.card!!.copy(collections = 25))
            assertEquals(25, bigger.listCount)
            // Nexus' tagger count of the Schober's link (6 people over 4 labels).
            assertEquals(6, page.taggerCount)
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
}
