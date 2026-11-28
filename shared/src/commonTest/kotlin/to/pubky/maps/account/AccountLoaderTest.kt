package to.pubky.maps.account

import kotlinx.coroutines.test.runTest
import to.pubky.maps.cache.CachedAccount
import to.pubky.maps.cache.CachedTag
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
    fun tagsOtherAppsPutOnTheirOwnObjectsAreNotTheUsersPlaceTags() =
        runTest {
            tag("a", "https://www.openstreetmap.org/node/1", "coffee", 30)
            tag("b", "pubky://$USER/pub/pubky.app/posts/0034A0000001", "funny", 20)
            tag("c", "pubky://$USER/pub/pubky.app/profile.json", "friend", 10)
            tag("d", "geo:47.1,8.5", "bench", 5)
            core.tagPaths = listOf("a", "b", "c", "d").map { "/pub/social/v1/tags/$it.json" }

            assertEquals(listOf("coffee", "bench"), AccountLoader(core) { 0 }.load(USER).tags.map { it.label })
        }

    private fun path(name: String) = "/pub/social/v1/tags/$name.json"

    @Test
    fun aRefreshReadsOnlyTheTagFilesTheLastOneDidNotKnow() =
        runTest {
            tag("a", "https://www.openstreetmap.org/node/1", "coffee", 30)
            tag("b", "pubky://$USER/pub/pubky.app/posts/0034A0000001", "funny", 20)
            core.files[path("c")] = "not json".encodeToByteArray()
            tag("d", "geo:47.1,8.5", "bench", 5)
            core.tagPaths = listOf("a", "b", "c", "d").map(::path)
            val first = AccountLoader(core) { 0 }.load(USER)
            assertEquals(listOf("coffee", "bench"), first.tags.map { it.label })
            assertEquals(mapOf(path("b") to "pubky://$USER/pub/pubky.app/posts/0034A0000001"), first.otherTags)

            // A new tag, and one place tag deleted; the file with no tag is read again (a later version
            // may read it).
            tag("e", "geo:1,2", "view", 40)
            core.tagPaths = listOf("a", "b", "c", "e").map(::path)
            core.gets.clear()
            val second = AccountLoader(core) { 0 }.load(USER, known = first)

            assertEquals(listOf(path("c"), path("e")), core.gets.sorted())
            assertEquals(listOf("view", "coffee"), second.tags.map { it.label })
            assertEquals(first.otherTags, second.otherTags)

            // An other app's tag deleted.
            core.tagPaths = listOf("a", "e").map(::path)
            assertEquals(emptyMap(), AccountLoader(core) { 0 }.load(USER, known = second).otherTags)
        }

    @Test
    fun theTagFilesBeyondOneRefreshsReadsAreReadByTheNextRefresh() =
        runTest {
            // A heavy pubky.app user: more tags on posts than one refresh reads, a place tag listed last.
            val others = List(MAX_TAG_READS + 10) { "p$it" }
            others.forEach { tag(it, "pubky://$USER/pub/pubky.app/posts/$it", "funny", 1) }
            tag("zz", "https://www.openstreetmap.org/node/1", "coffee", 30)
            core.tagPaths = (others + "zz").map(::path)
            core.listPageSize = 100

            val first = AccountLoader(core) { 100 }.load(USER, known = CachedAccount(USER, null, 50, emptyList(), emptyList()))
            assertEquals(MAX_TAG_READS, core.gets.size)
            assertEquals(emptyList(), first.tags)
            assertEquals(MAX_TAG_READS, first.otherTags.size)
            // A tag written since the last refresh may be among the files left: its write still shows.
            assertEquals(50, first.refreshedAt)

            core.gets.clear()
            val second = AccountLoader(core) { 200 }.load(USER, known = first)
            assertEquals(11, core.gets.size)
            assertEquals(listOf("coffee"), second.tags.map { it.label })
            assertEquals(others.size, second.otherTags.size)
            assertEquals(200, second.refreshedAt)

            core.gets.clear()
            assertEquals(second.copy(refreshedAt = 300), AccountLoader(core) { 300 }.load(USER, known = second))
            assertEquals(emptyList(), core.gets)
        }

    @Test
    fun aKnownFileWhoseTargetTheRuleNowJudgesOtherwiseIsReadAgain() =
        runTest {
            tag("a", "geo:47.1,8.5", "bench", 5)
            tag("b", "pubky://$USER/pub/pubky.app/posts/0034A0000001", "funny", 20)
            core.tagPaths = listOf("a", "b").map(::path)
            // What an earlier version kept: a rule that took the post and left out geo: links.
            val known =
                CachedAccount(
                    USER,
                    null,
                    null,
                    tags = listOf(CachedTag(path("b"), "pubky://$USER/pub/pubky.app/posts/0034A0000001", "funny", 20)),
                    bookmarks = emptyList(),
                    otherTags = mapOf(path("a") to "geo:47.1,8.5"),
                )

            val loaded = AccountLoader(core) { 0 }.load(USER, known = known)

            assertEquals(listOf(path("a"), path("b")), core.gets.sorted())
            assertEquals(listOf("bench"), loaded.tags.map { it.label })
            assertEquals(mapOf(path("b") to "pubky://$USER/pub/pubky.app/posts/0034A0000001"), loaded.otherTags)
        }

    @Test
    fun anotherAccountsTagFilesAreReadAgain() =
        runTest {
            tag("a", "geo:47.1,8.5", "bench", 5)
            core.tagPaths = listOf(path("a"))
            val other = AccountLoader(core) { 0 }.load(OTHER_USER)
            core.gets.clear()

            assertEquals(listOf("bench"), AccountLoader(core) { 0 }.load(USER, known = other).tags.map { it.label })
            assertEquals(listOf(path("a")), core.gets)
        }

    @Test
    fun savedPlacesAreListedInPlaceOrder() =
        runTest {
            core.bookmarks =
                listOf(SavedPlace("geo:1,2", "/priv/social/v1/bookmarks/b.json"), SavedPlace("geo:0,1", "/priv/social/v1/bookmarks/a.json"))

            assertEquals(listOf("geo:0,1", "geo:1,2"), AccountLoader(core) { 0 }.load(USER).bookmarks.map { it.place })
        }

    @Test
    fun anEmptyPageOfSavedPlacesWithACursorIsNotTheEnd() =
        runTest {
            // The core's page after 20 listing pages of other apps' bookmarks: no place, more to come.
            core.bookmarkPages =
                listOf(
                    listOf(SavedPlace("geo:0,1", "/priv/social/v1/bookmarks/a.json")),
                    emptyList(),
                    listOf(SavedPlace("geo:1,2", "/priv/social/v1/bookmarks/~b.json")),
                )

            assertEquals(listOf("geo:0,1", "geo:1,2"), AccountLoader(core) { 0 }.load(USER).bookmarks.map { it.place })
        }

    @Test
    fun aListingWhoseCursorNeverEndsStopsAfterTheLastAllowedRead() =
        runTest {
            core.bookmarkPages = List(MAX_PAGE_READS + 50) { emptyList() }

            assertEquals(emptyList(), AccountLoader(core) { 0 }.load(USER).bookmarks)
            assertEquals(MAX_PAGE_READS, core.bookmarkReads)
        }

    @Test
    fun theShortIdShowsTheStartAndTheEndOfTheKey() {
        assertEquals("pubky8pin…5ewo", shortPubkyId(USER))
    }
}
