package to.pubky.maps.write

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import to.pubky.maps.account.CoreFailure
import to.pubky.maps.core.CollectionItem
import to.pubky.maps.core.PubkyMapsCore
import to.pubky.maps.core.buildBookmark
import to.pubky.maps.core.buildCollection
import to.pubky.maps.core.buildPhotoFile
import to.pubky.maps.core.buildPhotoPost
import to.pubky.maps.core.buildReview
import to.pubky.maps.core.buildTag
import to.pubky.maps.core.defaultCoreConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val AUTHOR = "8pinxxgqs41n4aididenw5apqp1urfmzdztr8jt4abrkdn435ewo"
private const val PLACE = "https://www.openstreetmap.org/node/270803095"

/**
 * The objects the writes send, built by the real Rust core (a build for this machine, loaded through
 * JNA) with the spec builders the session calls use, and [UniffiPubkyWrites] without a session.
 */
class RealCoreWritesTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun core(): PubkyMapsCore =
        runBlocking {
            val config = defaultCoreConfig()
            config.dhtEnabled = false
            config.pkarrRelays = listOf("http://127.0.0.1:9")
            config.authRelayUrls = listOf("http://127.0.0.1:9/inbox")
            PubkyMapsCore.create(config)
        }

    @Test
    fun aTagIsAPublicTagObjectOnTheLink() {
        val tag = buildTag(AUTHOR, "https://osm.org/node/270803095", "Cafe")

        assertTrue(tag.path.startsWith("/pub/social/v1/tags/"), tag.path)
        assertEquals("pubky://$AUTHOR${tag.path}", tag.uri)
        val body = json(tag.json)
        assertEquals(PLACE, body["uri"]!!.jsonPrimitive.content, "the core spells the OSM link canonically")
        assertEquals("cafe", body["label"]!!.jsonPrimitive.content)
        // The same link and label always have the same address, so sending it twice changes nothing.
        assertEquals(tag.path, buildTag(AUTHOR, PLACE, "cafe").path)
    }

    @Test
    fun aPostIsANoteReplyingToThePlace() {
        val post = buildReview(AUTHOR, PLACE, "Best espresso in town.")

        assertTrue(post.path.startsWith("/pub/social/v1/posts/${post.id}/"), post.path)
        val body = json(post.json)
        assertEquals("note", body["kind"]!!.jsonPrimitive.content)
        assertEquals(PLACE, body["parent"]!!.jsonPrimitive.content)
    }

    @Test
    fun aPhotoIsAnImagePostEmbeddingThePlace() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + "JFIF".encodeToByteArray()
        val uploaded = buildPhotoFile(AUTHOR, jpeg, "image/jpeg")
        assertTrue(uploaded.path.startsWith("/pub/social/v1/files/") && uploaded.path.endsWith(".jpg"), uploaded.path)
        val file = uploaded.uri
        val post = buildPhotoPost(AUTHOR, PLACE, file, "The bar", null)

        val body = json(post.json)
        assertEquals("image", body["kind"]!!.jsonPrimitive.content)
        assertEquals(PLACE, body["embed"]!!.jsonPrimitive.content)
        assertTrue(post.json.contains(file))
        assertTrue(post.json.contains("The bar"))
    }

    @Test
    fun aSavedPlaceIsAPrivateBookmark() {
        val bookmark = buildBookmark(AUTHOR, PLACE)

        assertTrue(bookmark.path.startsWith("/priv/social/v1/bookmarks/"), bookmark.path)
    }

    @Test
    fun aListIsACollectionPostTheAppReadsBack() {
        val list = buildCollection(AUTHOR, "Coffee", null, listOf(CollectionItem("https://osm.org/node/270803095", null)))

        assertEquals(ListPost("Coffee", null, listOf(PLACE)), listOfPost(list.json))
        assertEquals(false to list.id, listAddress(list.uri))
        assertEquals(true to list.id, listAddress(list.uri.replace("/pub/", "/priv/")))
    }

    @Test
    fun aListsDescriptionGoesInItsPostAndIsReadBack() {
        val writes = UniffiPubkyWrites(core())
        val list = writes.buildList(AUTHOR, "Coffee", "Where to sit with a book", listOf("https://osm.org/node/270803095"), private = false)

        assertEquals(ListPost("Coffee", "Where to sit with a book", listOf(PLACE)), listOfPost(list.json))
        val content = json(json(list.json)["content"]!!.jsonPrimitive.content)
        assertEquals("Where to sit with a book", content["description"]!!.jsonPrimitive.content, "the spec's field, as pubky.app reads it")
        assertFailsWith<CoreFailure> { writes.buildList(AUTHOR, "Coffee", "x".repeat(501), listOf(PLACE), private = false) }
    }

    @Test
    fun writesWithoutASessionAskForTheSignIn() =
        runBlocking {
            val writes = UniffiPubkyWrites(core())

            assertFailsWith<CoreFailure.Auth> { writes.tag(PLACE, "cafe") }
            assertFailsWith<CoreFailure.Auth> { writes.save(PLACE) }
            assertEquals(PLACE, writes.normalize("https://osm.org/node/270803095"))
            assertEquals("not a place", writes.normalize("not a place"), "kept as it is when the core cannot read it")
        }
}
