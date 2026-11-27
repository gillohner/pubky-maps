package to.pubky.maps.account

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import to.pubky.maps.core.PubkyMapsCore
import to.pubky.maps.core.defaultCoreConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Account page's tags are filtered with the real Rust core's rule (a build for this machine,
 * loaded through JNA, no network), through [UniffiPubkyCore]: tags on map links and web pages are
 * the user's place tags, tags pubky.app put on its posts and profiles are not.
 */
class RealCoreAccountTagsTest {
    private val real: PubkyCore =
        runBlocking {
            val config = defaultCoreConfig()
            config.dhtEnabled = false
            config.pkarrRelays = listOf("http://127.0.0.1:9")
            config.authRelayUrls = listOf("http://127.0.0.1:9/inbox")
            UniffiPubkyCore(PubkyMapsCore.create(config))
        }

    /** The homeserver's files from [FakePubkyCore], the rule from the real core. */
    private val files = FakePubkyCore()
    private val core =
        object : PubkyCore by files {
            override fun mayBeMapPlace(target: String): Boolean = real.mayBeMapPlace(target)
        }

    @Test
    fun onlyTagsOnPlacesAreTheUsersTags() =
        runTest {
            val places =
                listOf(
                    "https://www.openstreetmap.org/node/270803095",
                    "geo:47.37687,8.54169",
                    "https://maps.app.goo.gl/3bTBxL9Sg2cXE6sq9",
                    "https://www.kunsthaus.ch/",
                )
            val others =
                listOf(
                    "pubky://$USER/pub/pubky.app/posts/0034A0000001",
                    "pubky://$USER/pub/pubky.app/profile.json",
                    "pubky://$USER/pub/social/v1/posts/0035ZRTMQP27E",
                    "mailto:someone@example.com",
                )
            (places + others).forEachIndexed { i, uri ->
                files.files["/pub/social/v1/tags/$i.json"] =
                    """{"uri":"$uri","label":"t$i","created_at":${100 - i}}""".encodeToByteArray()
            }
            files.tagPaths = (places + others).indices.map { "/pub/social/v1/tags/$it.json" }

            assertEquals(places, AccountLoader(core) { 0 }.load(USER).tags.map { it.place })
        }
}
