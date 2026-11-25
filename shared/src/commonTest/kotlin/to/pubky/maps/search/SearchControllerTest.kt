package to.pubky.maps.search

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.status.ServiceHealth
import to.pubky.maps.status.ServicePart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchControllerTest {
    /** Answers from the fixtures after [photonDelay] / [pinakesDelay] ms, or Unreachable when told to fail. */
    private class FakeSearch : PlaceSearch {
        val photonQueries = ArrayList<String>()
        val pinakesQueries = ArrayList<Pair<String, String?>>()
        var photonDelay = 100L
        var pinakesDelay = 200L
        var photonDown = false
        var pinakesDown = false
        var fixture = "kunsthaus"

        override suspend fun photon(
            query: String,
            area: SearchArea,
        ): SourceAnswer<List<PhotonPlace>> {
            photonQueries += query
            delay(photonDelay)
            return if (photonDown) SourceAnswer.Unreachable else SourceAnswer.Answered(Fixtures.photon(fixture), "p")
        }

        override suspend fun pinakes(
            query: String,
            area: SearchArea,
            viewer: String?,
        ): SourceAnswer<List<PinakesPlace>> {
            pinakesQueries += query to viewer
            delay(pinakesDelay)
            return if (pinakesDown) SourceAnswer.Unreachable else SourceAnswer.Answered(Fixtures.pinakes(fixture), "k")
        }
    }

    private val area = Fixtures.zurich
    private val search = FakeSearch()
    private val recents = InMemoryRecentSearchStore()

    private val health = ServiceHealth()

    private fun TestScope.controller(viewer: String? = null) = SearchController(search, recents, this, viewer = { viewer }, health = health)

    private fun SearchController.type(text: String) {
        for (i in 1..text.length) onQueryChange(text.take(i), area)
    }

    @Test
    fun typingAsksOnceAfterAPauseAndNeverForShortText() =
        runTest {
            val c = controller()
            c.startEditing()
            c.type("ku")
            advanceTimeBy(1_000)
            assertEquals(emptyList<String>(), search.photonQueries)
            c.type("kunsthaus")
            advanceTimeBy(299)
            assertEquals(emptyList<String>(), search.photonQueries)
            advanceTimeBy(2)
            runCurrent()
            assertEquals(listOf("kunsthaus"), search.photonQueries)
            assertEquals(listOf<Pair<String, String?>>("kunsthaus" to null), search.pinakesQueries)
            advanceUntilIdle()
            assertEquals(
                "Kunsthaus",
                c.state.value.results
                    .first()
                    .place.title,
            )
            assertEquals(SearchMode.Editing, c.state.value.mode)
        }

    @Test
    fun resultsShowAsEachSourceAnswers() =
        runTest {
            val c = controller()
            c.onQueryChange("kunsthaus", area)
            advanceTimeBy(300 + 101)
            val afterPhoton = c.state.value
            assertEquals(SourceState.Answered, afterPhoton.photon)
            assertEquals(SourceState.Loading, afterPhoton.pinakes)
            assertTrue(afterPhoton.searching)
            assertFalse(afterPhoton.results.first().onPubky)
            advanceUntilIdle()
            assertTrue(
                c.state.value.results
                    .first()
                    .onPubky,
            )
            assertFalse(c.state.value.searching)
        }

    @Test
    fun oneSourceDownStillShowsTheOther() =
        runTest {
            search.pinakesDown = true
            search.photonDelay = 5_000
            val c = controller()
            c.onQueryChange("kunsthaus", area)
            advanceUntilIdle()
            val s = c.state.value
            assertEquals(SourceState.Unreachable, s.pinakes)
            assertEquals(SourceState.Answered, s.photon)
            assertFalse(s.offline)
            assertEquals(
                "Kunsthaus",
                s.results
                    .first()
                    .place.title,
            )
        }

    @Test
    fun bothSourcesDownIsOffline() =
        runTest {
            search.photonDown = true
            search.pinakesDown = true
            val c = controller()
            c.onQueryChange("kunsthaus", area)
            advanceUntilIdle()
            assertTrue(c.state.value.offline)
            assertEquals(emptyList<SearchResult>(), c.state.value.results)
        }

    @Test
    fun theViewerGoesToPinakes() =
        runTest {
            val c = controller(viewer = "pubkyid")
            c.onQueryChange("kunsthaus", area)
            advanceUntilIdle()
            assertEquals(listOf<Pair<String, String?>>("kunsthaus" to "pubkyid"), search.pinakesQueries)
        }

    @Test
    fun coordinatesNeedNoNetwork() =
        runTest {
            val c = controller()
            c.onQueryChange("47.37, 8.54", area)
            advanceUntilIdle()
            assertEquals(emptyList<String>(), search.photonQueries)
            assertEquals(
                PlaceSource.Coordinates,
                c.state.value.results
                    .single()
                    .place.source,
            )
            c.submit(area)
            advanceUntilIdle()
            assertEquals(SearchMode.Selected, c.state.value.mode)
            assertEquals(
                PlaceSource.Coordinates,
                c.state.value.selected
                    ?.place
                    ?.source,
            )
            assertEquals(listOf(RecentSearch("47.37, 8.54")), recents.entries.value)
        }

    @Test
    fun aGeoSearchLinkSearchesItsText() =
        runTest {
            val c = controller()
            c.onQueryChange("geo:0,0?q=kunsthaus", area)
            advanceUntilIdle()
            assertEquals(listOf("kunsthaus"), search.photonQueries)
        }

    @Test
    fun searchKeyAsksAtOnceEvenForShortTextAndShowsTheResultsOnTheMap() =
        runTest {
            val c = controller()
            c.onQueryChange("ku", area)
            c.submit(area)
            runCurrent()
            assertEquals(listOf("ku"), search.photonQueries)
            advanceUntilIdle()
            assertEquals(SearchMode.Results, c.state.value.mode)
            assertEquals(listOf(RecentSearch("ku")), recents.entries.value)
        }

    @Test
    fun searchKeyDoesNotAskAgainForResultsAlreadyShown() =
        runTest {
            val c = controller()
            c.onQueryChange("kunsthaus", area)
            advanceUntilIdle()
            c.submit(area)
            advanceUntilIdle()
            assertEquals(1, search.photonQueries.size)
            assertEquals(SearchMode.Results, c.state.value.mode)
        }

    @Test
    fun aSingleResultOpensAtOnce() =
        runTest {
            search.fixture = "conditorei-schober"
            val c = controller()
            c.onQueryChange("conditorei schober", area)
            c.submit(area)
            advanceUntilIdle()
            assertEquals(SearchMode.Selected, c.state.value.mode)
            assertEquals(
                "Conditorei schober",
                c.state.value.selected
                    ?.place
                    ?.title,
            )
        }

    @Test
    fun pickingAResultRemembersThePlaceAndBackReturnsToTheResults() =
        runTest {
            val c = controller()
            c.onQueryChange("kunsthaus", area)
            c.submit(area)
            advanceUntilIdle()
            val museum =
                c.state.value.results
                    .first()
            c.select(museum)
            assertEquals(SearchMode.Selected, c.state.value.mode)
            assertEquals(listOf(RecentSearch("kunsthaus", museum.place)), recents.entries.value)
            assertTrue(c.back())
            assertEquals(SearchMode.Results, c.state.value.mode)
            assertNull(c.state.value.selected)
            assertTrue(c.back())
            assertEquals(SearchState(textSetBySearch = 1), c.state.value)
            assertFalse(c.back())
        }

    @Test
    fun backWhileTypingShowsTheResultsOnTheMapOrLeaves() =
        runTest {
            val c = controller()
            c.startEditing()
            c.onQueryChange("kunsthaus", area)
            advanceUntilIdle()
            assertTrue(c.back())
            assertEquals(SearchMode.Results, c.state.value.mode)
            c.startEditing()
            c.onQueryChange("k", area)
            assertTrue(c.back())
            assertEquals(SearchMode.Idle, c.state.value.mode)
            assertEquals("", c.state.value.query)
        }

    @Test
    fun aRecentPlaceOpensWithoutASearchAndARecentTextSearchesAgain() =
        runTest {
            val c = controller()
            c.onQueryChange("kunsthaus", area)
            advanceUntilIdle()
            val museum =
                c.state.value.results
                    .first()
            c.close()
            search.photonQueries.clear()
            c.selectRecent(RecentSearch("kunsthaus", museum.place), area)
            assertEquals(SearchMode.Selected, c.state.value.mode)
            assertEquals(
                museum.place,
                c.state.value.selected
                    ?.place,
            )
            assertEquals(emptyList<String>(), search.photonQueries)
            // Closing the card of a lone result leaves search.
            c.closeCard()
            assertEquals(SearchMode.Idle, c.state.value.mode)

            c.selectRecent(RecentSearch("zoo"), area)
            runCurrent()
            assertEquals(listOf("zoo"), search.photonQueries)
        }

    @Test
    fun newTextCancelsTheSearchStillRunning() =
        runTest {
            search.photonDelay = 1_000
            val c = controller()
            c.onQueryChange("kunsthaus", area)
            advanceTimeBy(500)
            c.onQueryChange("kunsthaus zürich", area)
            advanceUntilIdle()
            assertEquals(listOf("kunsthaus", "kunsthaus zürich"), search.photonQueries)
            assertEquals("kunsthaus zürich", c.state.value.resultsQuery)
        }

    @Test
    fun clearingTheBarKeepsTyping() =
        runTest {
            val c = controller()
            c.onQueryChange("kunsthaus", area)
            advanceUntilIdle()
            c.clearQuery()
            assertEquals(SearchState(mode = SearchMode.Editing, textSetBySearch = 1), c.state.value)
        }

    @Test
    fun spacesAroundTheTextAskNothingNew() =
        runTest {
            val c = controller()
            c.onQueryChange("zoo", area)
            advanceTimeBy(350)
            c.onQueryChange("zoo ", area)
            advanceUntilIdle()
            assertEquals(listOf("zoo"), search.photonQueries)
            assertTrue(c.state.value.resultsAreCurrent)
            assertTrue(
                c.state.value.results
                    .isNotEmpty(),
            )
        }

    @Test
    fun searchKeyWaitsForTheSearchAlreadyOnItsWay() =
        runTest {
            search.photonDelay = 1_000
            val c = controller()
            c.onQueryChange("kunsthaus", area)
            advanceTimeBy(500)
            c.submit(area)
            advanceUntilIdle()
            assertEquals(listOf("kunsthaus"), search.photonQueries)
            assertEquals(SearchMode.Results, c.state.value.mode)
            assertTrue(
                c.state.value.results
                    .isNotEmpty(),
            )
        }

    @Test
    fun aGeoUriTheAppCannotReadIsNoSearch() =
        runTest {
            val c = controller()
            c.onQueryChange("geo:somewhere", area)
            c.submit(area)
            advanceUntilIdle()
            assertEquals(emptyList<String>(), search.photonQueries)
        }

    @Test
    fun shorteningTheTextWhileASearchStartsLeavesNoSpinner() =
        runTest {
            search.photonDelay = 1_000
            val c = controller()
            c.onQueryChange("kunsthaus", area)
            advanceTimeBy(400)
            c.onQueryChange("ku", area)
            advanceUntilIdle()
            assertFalse(c.state.value.searching)
            assertEquals(emptyList<SearchResult>(), c.state.value.results)
        }

    @Test
    fun searchAsksAgainASourceThatFailed() =
        runTest {
            search.photonDown = true
            search.pinakesDown = true
            val c = controller()
            c.onQueryChange("kunsthaus", area)
            advanceUntilIdle()
            assertTrue(c.state.value.offline)
            assertEquals(setOf(ServicePart.Search), health.missing.value)
            // The connection is back: Search asks both again.
            search.photonDown = false
            search.pinakesDown = false
            c.submit(area)
            advanceUntilIdle()
            assertEquals(listOf("kunsthaus", "kunsthaus"), search.photonQueries)
            assertFalse(c.state.value.offline)
            assertEquals(emptySet(), health.missing.value)
            assertTrue(
                c.state.value.results
                    .isNotEmpty(),
            )
        }

    @Test
    fun oneSourceFailedIsAskedAgainToo() =
        runTest {
            search.pinakesDown = true
            val c = controller()
            c.onQueryChange("kunsthaus", area)
            advanceUntilIdle()
            search.pinakesDown = false
            c.submit(area)
            advanceUntilIdle()
            assertEquals(2, search.pinakesQueries.size)
            assertEquals(SourceState.Answered, c.state.value.pinakes)
            // Both never down at once: search was never named offline.
            assertEquals(emptySet(), health.missing.value)
        }

    @Test
    fun leavingSearchCancelsTheSearchSearchWasWaitingFor() =
        runTest {
            search.photonDelay = 5_000
            search.pinakesDelay = 5_000
            val c = controller()
            c.onQueryChange("kunsthaus", area)
            advanceTimeBy(400)
            c.submit(area)
            c.close()
            advanceUntilIdle()
            // The requests were cancelled: nothing came back into the closed search.
            assertEquals(SearchState(textSetBySearch = 1), c.state.value)
            assertTrue(currentTime < 5_000L, "the search ran on after close: $currentTime ms")
        }

    @Test
    fun anUnreadableGeoUriIsNoHistoryEntry() =
        runTest {
            val c = controller()
            c.onQueryChange("geo:somewhere", area)
            c.submit(area)
            advanceUntilIdle()
            assertEquals(emptyList<RecentSearch>(), recents.entries.value)
        }

    @Test
    fun closingSearchClearsItsOutageFromTheMap() =
        runTest {
            search.photonDown = true
            search.pinakesDown = true
            val c = controller()
            c.onQueryChange("kunsthaus", area)
            advanceUntilIdle()
            assertEquals(setOf(ServicePart.Search), health.missing.value)
            c.close()
            assertEquals(emptySet(), health.missing.value)
        }
}
