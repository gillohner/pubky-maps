package to.pubky.maps.place

import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The recorded Pinakes and Nexus answers in src/commonTest/fixtures/place (scripts/place/record-fixtures.py). */
internal object PlaceFixtures {
    private val dir = "src/commonTest/fixtures/place".toPath()

    fun read(name: String): String = FileSystem.SYSTEM.read(dir / name) { readUtf8() }

    const val NEXUS = "https://nexus.example.org"
    const val TESTER = "466weoro4e45cxdfdtxfjcuss1x6w91dfjn9mcmkksh6fexmx5xy"
}

class PlaceParsersTest {
    private fun read(name: String) = PlaceFixtures.read(name)

    @Test
    fun anOsmCardWithAddressAlsoOnAndLists() {
        val card = parsePinakesCard(read("pinakes-card-kunsthaus.json"))
        assertEquals("49d6eb1c6a69946a41fe45ccd44c4a72", card.id)
        assertEquals("https://www.openstreetmap.org/relation/3768127", card.uri)
        assertEquals("Kunsthaus", card.name)
        assertEquals("Museum", card.type)
        assertEquals("tourism/museum", card.category)
        assertEquals("Heimplatz 1, 8001 Zürich", card.address)
        assertEquals(47.3702241, card.point?.lat)
        assertEquals(2, card.collections)
        assertEquals("© OpenStreetMap contributors (ODbL)", card.attribution)
        assertTrue(card.alsoOn.any { it.site == "Wikipedia (de)" && it.derived })
        assertTrue(card.alsoOn.any { it.site == "Official website" && it.uri == "http://www.kunsthaus.ch/" })
        // Pinakes does not serve OSM details on its cards yet.
        assertEquals(emptyMap(), card.facts)
        assertNull(card.image)
    }

    @Test
    fun aWikidataCardWithACreditedImage() {
        val card = parsePinakesCard(read("pinakes-card-grossmuenster-wikidata.json"))
        assertEquals("church buildings in the city of Zurich, Switzerland", card.description)
        assertTrue(card.image!!.startsWith("https://thumb.wikimedia.org/"))
        val credit = card.credit("image")!!
        assertEquals("Ilya Hirschberg (Kreecher)", credit.author)
        assertEquals("CC-BY-SA-4.0", credit.license)
        assertEquals("https://creativecommons.org/licenses/by-sa/4.0/", credit.licenseUrl)
        assertTrue(credit.sourceUrl.startsWith("https://commons.wikimedia.org/wiki/File:"))
        // Its OSM element is an exact "Also on" link (people referenced it).
        assertTrue(card.alsoOn.any { it.uri == "https://www.openstreetmap.org/way/36916418" && !it.derived })
        assertNull(card.address)
    }

    @Test
    fun aCardKeepsTheLinksPeopleUsed() {
        val card = parsePinakesCard(read("pinakes-card-schober.json"))
        assertEquals("Stegengasse, 8001 Zürich", card.address)
        assertEquals(4, card.refs.size)
        assertTrue("https://www.openstreetmap.org/node/270803095/" in card.refs)
        assertEquals(3, card.collections)
    }

    @Test
    fun osmDetailsFromTheCardFacts() {
        // Babu's Bakery's attributes as Pinakes stores them (its dump), the way the card's facts carry them.
        val details =
            OsmDetails.from(
                mapOf(
                    "opening_hours" to "Tu-Fr 07:30-17:00, Sa 08:30-18:00, Su 09:00-15:00; PH off",
                    "website" to "https://babus.ch/",
                    "wheelchair" to "no",
                    "phone" to "+41 44 251 54 54;+41 79 000 00 00",
                    "cuisine" to "coffee_shop;tea",
                    "payment:lightning" to "yes",
                    "level" to "0",
                ),
            )!!
        assertEquals("https://babus.ch/", details.website)
        assertEquals(Wheelchair.No, details.wheelchair)
        assertEquals("+41 44 251 54 54", details.phone)
        assertEquals(listOf("Coffee shop", "Tea"), details.cuisine)
        assertEquals(listOf("Lightning"), details.bitcoin)
        assertEquals("Floor 0", details.level)
        assertNull(OsmDetails.from(emptyMap()))
        assertEquals("https://www.kunsthaus.ch", OsmDetails.webLink("www.kunsthaus.ch"))
        assertNull(OsmDetails.webLink("javascript://x"))
    }

    @Test
    fun listsHoldingAPlace() {
        val refs = parsePinakesCollections(read("pinakes-collections-schober.json"))
        assertEquals(3, refs.items.size)
        assertNull(refs.cursor)
        assertEquals(PlaceFixtures.TESTER, refs.items[0].author)

        val lists = parseNexusCollections(read("nexus-collection-posts.json"), viewer = PlaceFixtures.TESTER)
        assertEquals(
            listOf("Card review: every card type", "Card review: places and events", "RTI audit list (test data)"),
            lists.map { it.name },
        )
        assertEquals(listOf(63, 10, 2), lists.map { it.itemCount })
        assertTrue(lists.all { it.mine })
        assertFalse(parseNexusCollections(read("nexus-collection-posts.json"), viewer = null).any { it.mine })
        assertEquals(refs.items.map { it.uri }, lists.map { it.uri })
    }

    @Test
    fun nexusViewsMergeIntoTags() {
        val views = parseNexusResourceViews(read("nexus-by-ids-zurich.json"))
        assertEquals(4, views.size)
        val schober = views.single { it.uri == "https://www.openstreetmap.org/node/270803095" }
        assertEquals(1, schober.replies)
        assertEquals(2, schober.embeds)
        val social = mergeResourceViews(listOf(schober))
        assertEquals(listOf("coffee", "cake", "rtiafter", "zurich"), social.tags.map { it.label })
        assertEquals(3, social.tags[0].count)
        assertEquals(3, social.tags[0].taggers.size)
        assertFalse(social.tags.any { it.mine })
    }

    @Test
    fun aTaggerOnTwoLinksOfAPlaceCountsOnce() {
        val a = ResourceView("a", "https://www.openstreetmap.org/node/1", listOf(ViewTag("coffee", listOf("p1", "p2"), 2, null)), 1, 0)
        val b =
            ResourceView(
                "b",
                "https://maps.app.goo.gl/x",
                listOf(ViewTag("coffee", listOf("p2", "p3"), 7, "pubky://p2/pub/social/v1/tags/X"), ViewTag("cake", listOf("p3"), 1, null)),
                2,
                1,
            )
        val social = mergeResourceViews(listOf(a, b))
        val coffee = social.tags.single { it.label == "coffee" }
        assertEquals(listOf("p1", "p2", "p3"), coffee.taggers)
        // 2 + 7 counted, p2 listed on both.
        assertEquals(8, coffee.count)
        assertTrue(coffee.mine)
        assertEquals(listOf("a", "b"), social.resourceIds)
        assertEquals(3, social.replyCount)
        assertEquals(1, social.embedCount)
    }

    @Test
    fun repliesEmbedsAndWhatIsNotAPost() {
        val replies = parseNexusPosts(read("nexus-replies-schober.json"), PlaceFixtures.NEXUS)
        assertEquals(1, replies.size)
        assertEquals("note", replies[0].kind)
        assertTrue(replies[0].content.startsWith("Test data (pinakes-test, staging): reply"))
        assertEquals(2, parseNexusPosts(read("nexus-embeds-schober.json"), PlaceFixtures.NEXUS).size)
        // The Kunsthaus' only reply is a rating (social specs 1.1, not shown yet).
        assertEquals(emptyList(), parseNexusPosts(read("nexus-replies-kunsthaus.json"), PlaceFixtures.NEXUS))
    }

    @Test
    fun imageAttachmentsBecomeNexusFileUrls() {
        // No image post about a place is on the development Nexus yet: the attachment form of the specs.
        val photo = nexusImage("pubky://${PlaceFixtures.TESTER}/pub/social/v1/files/0035ZRTMYFEZ2.jpg", "${PlaceFixtures.NEXUS}/")!!
        assertEquals("${PlaceFixtures.NEXUS}/v1/static/files/${PlaceFixtures.TESTER}/0035ZRTMYFEZ2.jpg/main", photo.url)
        assertEquals("${PlaceFixtures.NEXUS}/v1/static/files/${PlaceFixtures.TESTER}/0035ZRTMYFEZ2.jpg/feed", photo.thumbnailUrl)
        assertEquals(PlaceFixtures.TESTER, photo.author)
        assertNull(nexusImage("pubky://${PlaceFixtures.TESTER}/pub/social/v1/files/0035ZRTMYFEZ2.pdf", PlaceFixtures.NEXUS))
        assertNull(nexusImage("https://example.org/a.jpg", PlaceFixtures.NEXUS))
    }

    @Test
    fun people() {
        val people = parseNexusUsers(read("nexus-users.json"), PlaceFixtures.NEXUS)
        assertEquals(6, people.size)
        assertEquals("Pinakes Test 10", people.single { it.id == PlaceFixtures.TESTER }.name)
        // None of the test accounts has a picture; none is followed without a viewer.
        assertTrue(people.all { it.avatarUrl == null && !it.followed })
    }

    @Test
    fun placesInAnArea() {
        val page = parseMapPlaces(read("pinakes-bbox-zurich.json"))
        assertEquals(11, page.places.size)
        assertNull(page.cursor)
        val schober = page.places.single { it.uri == "https://www.openstreetmap.org/node/270803095" }
        assertEquals("Conditorei schober", schober.name)
        assertEquals("CafeOrCoffeeShop", schober.type)
    }

    @Test
    fun globalPostIds() {
        assertEquals("abc:0035Z", globalPostId("pubky://abc/pub/social/v1/posts/0035Z"))
        assertNull(globalPostId("https://abc/pub/social/v1/posts/0035Z"))
        assertNull(globalPostId("pubky://abc/pub/social/v1/tags/X"))
    }
}
