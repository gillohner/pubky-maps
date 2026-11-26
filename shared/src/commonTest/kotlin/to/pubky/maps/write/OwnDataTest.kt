package to.pubky.maps.write

import to.pubky.maps.cache.CachedAccount
import to.pubky.maps.cache.CachedBookmark
import to.pubky.maps.cache.CachedTag
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.place.PlacePost
import to.pubky.maps.place.PlaceTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ME = "8pinxxgqs41n4aididenw5apqp1urfmzdztr8jt4abrkdn435ewo"
private const val OSM = "https://www.openstreetmap.org/node/1"
private const val GOOGLE = "https://maps.app.goo.gl/abc"
private const val NEW_LIST = "pubky://me/priv/social/v1/posts/L2"

class OwnDataTest {
    private fun data(
        cached: CachedAccount? = CachedAccount(ME, null, 100, emptyList(), emptyList()),
        queue: List<WriteOp> = emptyList(),
        confirmed: List<Confirmed> = emptyList(),
        lists: List<OwnList> = emptyList(),
        phone: PhoneData = PhoneData(),
        points: Map<String, GeoPoint> = emptyMap(),
    ) = ownData(
        viewer = ME,
        cached = cached,
        lists = lists,
        phone = phone,
        queue = queue.mapIndexed { i, op -> QueuedOp(i.toLong(), op) },
        confirmed = confirmed,
        labels = mapOf(OSM to "Schober"),
        points = points,
        normalize = { it.replace("https://osm.org/", "https://www.openstreetmap.org/") },
    )

    @Test
    fun aQueuedTagShowsAsPendingAndAQueuedUntagHidesTheStoredOne() {
        val cached = CachedAccount(ME, null, 100, listOf(CachedTag("/t/1", "https://osm.org/node/1", "coffee", 1)), emptyList())

        val own = data(cached, queue = listOf(WriteOp.Tag(OSM, "cake"), WriteOp.Untag(OSM, "coffee")))

        assertEquals(listOf(OwnTag(OSM, "cake", pending = true)), own.tags)
        assertEquals(2, own.pending)
        assertEquals(setOf("cake"), own.forPlace(setOf(OSM)).labels)
    }

    @Test
    fun aWriteMadeAfterTheCacheWasReadStaysUntilARefreshHasIt() {
        val tag = Confirmed(WriteOp.Tag(OSM, "cake"), at = 200)

        assertEquals(listOf(OwnTag(OSM, "cake")), data(confirmed = listOf(tag)).tags)
        // A refresh that began after the write read it from the homeserver (or saw it gone).
        val later = CachedAccount(ME, null, 300, emptyList(), emptyList())
        assertEquals(emptyList(), data(later, confirmed = listOf(tag)).tags)
    }

    @Test
    fun savedPlacesComeFromTheHomeserverAndThePhoneWithTheirNames() {
        val cached = CachedAccount(ME, null, 100, emptyList(), listOf(CachedBookmark("/b/1", OSM)))
        val phone = PhoneData(saved = listOf(PhoneSaved("geo:1,2", "Lake", 5)))

        val own = data(cached, queue = listOf(WriteOp.Save(GOOGLE)), phone = phone)

        assertEquals(
            listOf(
                // By name, else by link.
                SavedEntry(GOOGLE, Kept.Private, null, pending = true),
                SavedEntry("geo:1,2", Kept.Phone, "Lake", point = GeoPoint(1.0, 2.0)),
                SavedEntry(OSM, Kept.Private, "Schober"),
            ),
            own.saved,
        )
        assertTrue(own.forPlace(setOf(OSM)).isSaved)
        assertTrue(!own.forPlace(setOf("geo:9,9")).isSaved)
    }

    @Test
    fun aSavedPlaceIsWhereItWasSavedElseWhereItsGeoLinkSays() {
        val kunsthaus = GeoPoint(47.37038, 8.54831)
        val lake = GeoPoint(47.35, 8.55)
        val cached =
            CachedAccount(
                ME,
                null,
                100,
                emptyList(),
                listOf(CachedBookmark("/b/1", OSM), CachedBookmark("/b/2", "https://www.openstreetmap.org/way/7")),
            )
        val phone =
            PhoneData(
                saved =
                    listOf(
                        PhoneSaved("https://www.openstreetmap.org/node/5", "Bench", 5, lake),
                        PhoneSaved("geo:47.1,8.5?z=17", null, 6),
                        PhoneSaved("geo:0,0?q=Zürich", null, 7),
                    ),
            )

        val own =
            data(
                cached,
                // A queued save is written as the link was opened; its point is stored as the core spells it.
                queue = listOf(WriteOp.Save("geo:46.9,7.4"), WriteOp.Save("https://osm.org/node/2")),
                phone = phone,
                points = mapOf(OSM to kunsthaus, "https://www.openstreetmap.org/node/2" to lake),
            )

        val points = own.saved.associate { it.place to it.point }
        assertEquals(kunsthaus, points[OSM], "stored with the save")
        assertEquals(lake, points["https://www.openstreetmap.org/node/2"], "a queued save shows its pin at once")
        assertEquals(GeoPoint(46.9, 7.4), points["geo:46.9,7.4"], "a geo: link carries its point")
        assertEquals(lake, points["https://www.openstreetmap.org/node/5"], "kept on the phone with its point")
        assertEquals(GeoPoint(47.1, 8.5), points["geo:47.1,8.5?z=17"])
        assertNull(points["geo:0,0?q=Zürich"], "a geo: search names no point")
        assertNull(points["https://www.openstreetmap.org/way/7"], "saved elsewhere, without a point: no pin")
    }

    @Test
    fun listsShowQueuedChanges() {
        val list = OwnList("pubky://me/pub/social/v1/posts/L1", Kept.Public, "Coffee", listOf("https://osm.org/node/1"))

        val own =
            data(
                lists = listOf(list),
                queue =
                    listOf(
                        WriteOp.EditList(OSM, list.uri, remove = true),
                        WriteOp.CreateList(GOOGLE, "New", true, "/priv/x", NEW_LIST, "{}"),
                    ),
            )

        val edited = own.lists.first { it.uri == list.uri }
        assertEquals(emptyList(), edited.items)
        assertTrue(edited.pending)
        val created = own.lists.first { it.uri == NEW_LIST }
        assertEquals(Kept.Private, created.kept)
        assertEquals(listOf(GOOGLE), created.items)
        assertEquals(listOf(created), own.forPlace(setOf(GOOGLE)).lists)
        assertEquals(setOf(list.uri, NEW_LIST), own.forPlace(setOf(GOOGLE)).allLists)
    }

    @Test
    fun postsWrittenHereShowNewestFirstUntilNexusHasThem() {
        val post = WriteOp.Post(OSM, "Good", at = 10, path = "/p/P1", uri = "pubky://me/pub/social/v1/posts/P1", json = "{}")
        val sent = Confirmed(post, at = 11, uri = post.uri)
        val waiting =
            WriteOp.Photo(
                OSM,
                "/outbox/1.photo",
                null,
                at = 20,
                filePath = "/f/1.jpg",
                path = "/p/I1",
                uri = "pubky://me/pub/social/v1/posts/I1",
                json = "{}",
            )

        val own = data(confirmed = listOf(sent), queue = listOf(waiting)).forPlace(setOf(OSM))

        assertEquals(listOf("Good"), own.posts.map { it.text })
        assertEquals(listOf("/outbox/1.photo"), own.photos.map { it.photoFile })
        val nexus = listOf(PlacePost("pubky://me/pub/social/v1/posts/P1", ME, "note", "Good", 12, emptyList()))
        val rows = ownPostRows(own.posts + own.photos, nexus, ME)
        assertEquals(1, rows.size, "the sent post is in Nexus' answer, only the waiting photo is added")
        assertTrue(rows[0].pending)
        assertEquals("file:///outbox/1.photo", rows[0].images.single().url)
    }

    @Test
    fun signedOutThereIsNoHomeserverDataButThePhonesStays() {
        val phone = PhoneData(saved = listOf(PhoneSaved("geo:1,2", null, 5)))

        val own =
            ownData(
                viewer = null,
                cached = null,
                lists = emptyList(),
                phone = phone,
                queue = listOf(QueuedOp(1, WriteOp.Tag(OSM, "x"))),
                confirmed = emptyList(),
                labels = emptyMap(),
                normalize = { it },
            )

        assertNull(own.viewer)
        assertEquals(emptyList(), own.tags)
        assertEquals(0, own.pending)
        assertEquals(listOf("geo:1,2"), own.saved.map { it.place })
        assertNull(own.forPlace(setOf(OSM)).tags, "the user's tags are not known")
    }

    @Test
    fun theUsersOwnTagsCorrectNexusWhichLagsBehind() {
        val nexus =
            listOf(
                PlaceTag("coffee", 3, listOf("a", "b", "c"), mine = false),
                PlaceTag("cake", 1, listOf(ME), mine = true),
                PlaceTag("cozy", 2, listOf(ME, "a"), mine = true),
            )

        val merged = mergeOwnTags(nexus, mine = setOf("coffee", "cozy", "terrace"), viewer = ME)

        assertEquals(
            listOf(
                PlaceTag("coffee", 4, listOf(ME, "a", "b", "c"), mine = true),
                PlaceTag("cozy", 2, listOf(ME, "a"), mine = true),
                PlaceTag("terrace", 1, listOf(ME), mine = true),
            ),
            merged,
        )
        assertEquals(nexus, mergeOwnTags(nexus, mine = null, viewer = ME), "unknown own tags leave Nexus' word")
    }
}
