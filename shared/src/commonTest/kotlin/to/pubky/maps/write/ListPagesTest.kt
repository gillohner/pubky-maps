package to.pubky.maps.write

import kotlinx.coroutines.test.runTest
import to.pubky.maps.account.FakeWebPagePlaces
import to.pubky.maps.account.MAX_PAGE_CHECKS
import to.pubky.maps.account.notPlaces
import kotlin.test.Test
import kotlin.test.assertEquals

class ListPagesTest {
    @Test
    fun oneReadOfTheCollectionsAsksAboutAtMostTheChecksOfOneRefreshInListThenItemOrder() =
        runTest {
            val first = List(60) { "https://example.org/a$it" }
            val second = List(60) { "https://example.org/b$it" }
            val lists =
                listOf(
                    OwnList("pubky://me/pub/social/v1/posts/L1", Kept.Public, "Reading", first),
                    OwnList("pubky://me/pub/social/v1/posts/L2", Kept.Public, "More reading", second + "geo:1,2"),
                )
            // Every page asked about is no place.
            val places = FakeWebPagePlaces(answers = emptyMap())

            val known = knownListPages(lists, places)
            val shown = lists.withoutPagesOf(known.notPlaces + notPlaces(known.unchecked, places))

            assertEquals((first + second).take(MAX_PAGE_CHECKS), known.unchecked)
            assertEquals(known.unchecked, places.asked)
            assertEquals(listOf("pubky://me/pub/social/v1/posts/L2"), shown.map { it.uri }, "the first holds only pages that are no place")
            assertEquals(second.drop(MAX_PAGE_CHECKS - first.size) + "geo:1,2", shown.single().items, "pages past the checks stay shown")
            assertEquals(MAX_PAGE_CHECKS - first.size, shown.single().otherItems)
        }
}
