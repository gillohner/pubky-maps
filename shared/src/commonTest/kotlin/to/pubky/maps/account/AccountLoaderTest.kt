package to.pubky.maps.account

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountLoaderTest {
    private val core = FakePubkyCore()

    private fun tag(
        name: String,
        place: String,
        label: String,
        createdAt: Long,
    ) {
        core.files["/pub/social/v1/tags/$name.json"] =
            """{"uri":"$place","label":"$label","created_at":$createdAt,"x-extra":1}""".encodeToByteArray()
    }

    @Test
    fun theProfileNameComesFromTheSpecsPathOrTheOlderOne() =
        runTest {
            core.files["pubky://$USER/pub/pubky.app/profile.json"] = """{"name":"  Old Ada "}""".encodeToByteArray()
            assertEquals("Old Ada", AccountLoader(core) { 0 }.load(USER).name)

            core.files["pubky://$USER/pub/social/v1/profile.json"] = """{"name":"Ada","links":[]}""".encodeToByteArray()
            assertEquals("Ada", AccountLoader(core) { 0 }.load(USER).name)
        }

    @Test
    fun noProfileOrAnEmptyNameIsNoName() =
        runTest {
            assertNull(AccountLoader(core) { 0 }.load(USER).name)
            core.files["pubky://$USER/pub/social/v1/profile.json"] = """{"name":" "}""".encodeToByteArray()
            assertNull(AccountLoader(core) { 0 }.load(USER).name)
        }

    @Test
    fun everyPageOfTagsIsReadNewestFirstAndBrokenFilesAreSkipped() =
        runTest {
            tag("a", "https://www.openstreetmap.org/node/1", "coffee", 30)
            tag("b", "geo:47.1,8.5", "bench", 10)
            tag("c", "https://www.openstreetmap.org/way/2", "quiet", 20)
            core.files["/pub/social/v1/tags/d.json"] = "not json".encodeToByteArray()
            // Listed but gone by the time it is read.
            core.tagPaths = listOf("a", "b", "c", "d", "e").map { "/pub/social/v1/tags/$it.json" }

            val loaded = AccountLoader(core) { 77 }.load(USER)

            assertEquals(listOf("coffee", "quiet", "bench"), loaded.tags.map { it.label })
            assertEquals("geo:47.1,8.5", loaded.tags.last().place)
            assertEquals(77, loaded.refreshedAt)
        }

    @Test
    fun savedPlacesAreListedInPlaceOrder() =
        runTest {
            core.bookmarks =
                listOf(SavedPlace("geo:1,2", "/priv/social/v1/bookmarks/b.json"), SavedPlace("geo:0,1", "/priv/social/v1/bookmarks/a.json"))

            assertEquals(listOf("geo:0,1", "geo:1,2"), AccountLoader(core) { 0 }.load(USER).bookmarks.map { it.place })
        }

    @Test
    fun theShortIdShowsTheStartAndTheEndOfTheKey() {
        assertEquals("pubky8pin…5ewo", shortPubkyId(USER))
    }
}
