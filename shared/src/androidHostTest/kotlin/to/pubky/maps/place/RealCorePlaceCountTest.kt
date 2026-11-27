package to.pubky.maps.place

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import to.pubky.maps.core.mayBeMapPlace
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Another user's collection on a place page counts its places with the real Rust core's rule (a
 * build for this machine, loaded through JNA), the one the user's own bookmarks are filtered with:
 * map links and web pages are counted, other apps' Pubky objects are not.
 */
class RealCorePlaceCountTest {
    @Test
    fun anotherUsersCollectionCountsItsPlacesNotOtherAppsObjects() {
        val places =
            listOf(
                "https://www.openstreetmap.org/node/270803095",
                "geo:47.37687,8.54169",
                "https://maps.app.goo.gl/3bTBxL9Sg2cXE6sq9",
                "https://www.kunsthaus.ch/",
            )
        val others =
            listOf(
                "pubky://8pinxxgqs41n4aididenw5apqp1urfmzdztr8jt4abrkdn435ewo/pub/pubky.app/posts/0034A0000001",
                "pubky://8pinxxgqs41n4aididenw5apqp1urfmzdztr8jt4abrkdn435ewo/pub/pubky.app/profile.json",
                "mailto:someone@example.com",
            )
        val lists = parseNexusCollections(nexusPost(places + others), viewer = null, ::mayBeMapPlace)
        assertEquals(listOf(places.size), lists.map { it.itemCount })
    }

    /** Nexus' by_ids answer holding one collection post by another user with [items]. */
    private fun nexusPost(items: List<String>): String {
        val content =
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive("Zürich"),
                    "items" to JsonArray(items.map { JsonObject(mapOf("uri" to JsonPrimitive(it))) }),
                ),
            )
        val details =
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive("0035ZRTMQP27E"),
                    "author" to JsonPrimitive("anna"),
                    "kind" to JsonPrimitive("collection"),
                    "deleted" to JsonPrimitive(false),
                    "uri" to JsonPrimitive("pubky://anna/pub/social/v1/posts/0035ZRTMQP27E"),
                    "content" to JsonPrimitive(content.toString()),
                ),
            )
        return JsonArray(listOf(JsonObject(mapOf("details" to details)))).toString()
    }
}
