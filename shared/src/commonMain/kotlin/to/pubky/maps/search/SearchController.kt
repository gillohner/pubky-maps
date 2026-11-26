package to.pubky.maps.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.pubky.maps.offline.OfflinePlace
import to.pubky.maps.offline.OfflinePlaceSearch
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.place.formatCoordinate
import to.pubky.maps.status.ServiceHealth
import to.pubky.maps.status.ServicePart
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** What the search shows. */
enum class SearchMode {
    /** The bar on the map, empty. */
    Idle,

    /** Typing: the keyboard is up, the list shows recent searches or results as they come. */
    Editing,

    /** The results of a search on the map, and their list. */
    Results,

    /** One result picked: the map is on it and its card shows. */
    Selected,
}

/** Where one source stands for the current text. */
enum class SourceState { Idle, Loading, Answered, Unreachable }

data class SearchState(
    val mode: SearchMode = SearchMode.Idle,
    /** The text in the bar. */
    val query: String = "",
    /** The text [results] answer (results stay shown while the next text is asked). */
    val resultsQuery: String = "",
    val results: List<SearchResult> = emptyList(),
    val photon: SourceState = SourceState.Idle,
    val pinakes: SourceState = SourceState.Idle,
    /** The offline maps' places: asked when Photon cannot be reached; Idle when not asked or none can be searched. */
    val offlineMaps: SourceState = SourceState.Idle,
    val selected: SearchResult? = null,
    /** Counts the times search itself set [query] (not typing), so the bar takes the new text. */
    val textSetBySearch: Int = 0,
) {
    val searching: Boolean get() = photon == SourceState.Loading || pinakes == SourceState.Loading

    /** Neither source could be reached: search is offline. */
    val offline: Boolean get() = photon == SourceState.Unreachable && pinakes == SourceState.Unreachable

    /** True when [results] answer the text in the bar (spaces around it do not count). */
    val resultsAreCurrent: Boolean get() = resultsQuery.trim() == query.trim()
}

/**
 * Search as the user types. Text of [MIN_QUERY_LENGTH] or more characters is searched once the user
 * pauses for [debounce] (300 ms: Photon's public server asks for fair use, and a request per
 * keystroke would also flood the list); pressing Search asks at once, whatever the length.
 * Coordinates never go to the network. Photon and Pinakes are asked together and the list updates as
 * each answers, so a slow or failing source never holds back the other. With a [viewer] (the
 * signed-in user's pubky id), Pinakes ranks places the people they follow referenced higher. When
 * Photon cannot be reached, the places of the offline maps ([offline]) answer in its place, so search
 * still finds places, towns and streets in the downloaded areas without a connection. When neither
 * online source answers, [health] names search as missing (the map's degraded line) until one does.
 */
class SearchController(
    private val search: PlaceSearch,
    private val recents: RecentSearchStore,
    private val scope: CoroutineScope,
    private val viewer: () -> String? = { null },
    private val health: ServiceHealth? = null,
    private val debounce: Duration = DEBOUNCE,
    private val offline: OfflinePlaceSearch? = null,
) {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    /** The search asking the sources (after the typing pause, or at once for Search). */
    private var searchJob: Job? = null

    /** The text [searchJob] asks for. */
    private var searchText: String? = null

    /** What Search does after the answers: open a lone result. Waits for [searchJob]. */
    private var submitJob: Job? = null

    private fun cancelSearch() {
        submitJob?.cancel()
        searchJob?.cancel()
    }

    /** The bar was tapped. */
    fun startEditing() = _state.update { it.copy(mode = SearchMode.Editing) }

    fun onQueryChange(
        text: String,
        area: SearchArea,
    ) {
        val trimmed = text.trim()
        // Spaces around the text ask nothing new; the running search goes on.
        if (trimmed == state.value.query.trim() && trimmed.isNotEmpty()) {
            _state.update { it.copy(query = text, mode = SearchMode.Editing) }
            return
        }
        cancelSearch()
        _state.update { it.copy(query = text, mode = SearchMode.Editing, selected = null) }
        val geoLink = trimmed.startsWith("geo:", ignoreCase = true)
        when (val geo = parseGeoInput(trimmed, area.centre)) {
            is GeoInput.Point -> {
                showOnly(coordinateResult(geo), text)
            }

            is GeoInput.Query -> {
                if (geo.text.length >= MIN_QUERY_LENGTH) searchLater(geo.text, area) else clearResults()
            }

            null -> {
                // A geo: URI this app cannot read is no search text either.
                if (trimmed.length >= MIN_QUERY_LENGTH && !geoLink) searchLater(trimmed, area) else clearResults()
            }
        }
    }

    /** The Search key: show the results on the map; a single result opens at once. */
    fun submit(area: SearchArea) {
        val text = state.value.query.trim()
        if (text.isEmpty()) return
        val geo = parseGeoInput(text, area.centre)
        // A geo: URI this app cannot read is no search, and no history entry either.
        if (geo == null && text.startsWith("geo:", ignoreCase = true)) return
        recents.add(RecentSearch(text))
        if (geo is GeoInput.Point) {
            cancelSearch()
            // The typed text is the history entry; the point adds nothing to it.
            showOnly(coordinateResult(geo), state.value.query)
            select(state.value.results.single(), remember = false)
            return
        }
        val searchText = (geo as? GeoInput.Query)?.text ?: text
        _state.update { it.copy(mode = SearchMode.Results) }
        val current = state.value
        // Only a full answer counts: Search asks a source again that failed (the connection may be back).
        val haveAnswer =
            current.resultsAreCurrent && current.photon == SourceState.Answered && current.pinakes == SourceState.Answered
        // A search for this very text that is already on its way is awaited, not asked again.
        val running = searchJob?.takeIf { it.isActive && this.searchText == searchText }
        if (running == null) {
            cancelSearch()
            if (!haveAnswer) startSearch(searchText, area, wait = false)
        } else {
            submitJob?.cancel()
        }
        val asking = searchJob?.takeIf { it.isActive }
        submitJob =
            scope.launch {
                asking?.join()
                val results = state.value.results
                if (results.size == 1 && state.value.mode == SearchMode.Results) select(results.single())
            }
    }

    /** A result was picked from the list or the map. */
    fun select(result: SearchResult) = select(result, remember = true)

    private fun select(
        result: SearchResult,
        remember: Boolean,
    ) {
        if (remember) {
            recents.add(
                RecentSearch(
                    query =
                        state.value.query
                            .trim()
                            .ifEmpty { result.place.title },
                    place = result.place,
                ),
            )
        }
        _state.update { it.copy(mode = SearchMode.Selected, selected = result) }
    }

    /** A recent search was picked: a place opens again, a text is searched again. */
    fun selectRecent(
        entry: RecentSearch,
        area: SearchArea,
    ) {
        val place = entry.place
        if (place == null) {
            // Searched at once, like pressing Search: no typing pause to wait for.
            cancelSearch()
            _state.update {
                it.copy(query = entry.query, mode = SearchMode.Editing, selected = null, textSetBySearch = it.textSetBySearch + 1)
            }
            submit(area)
            return
        }
        cancelSearch()
        val result = SearchResult(place = place)
        _state.update {
            SearchState(
                mode = SearchMode.Results,
                query = place.title,
                resultsQuery = place.title,
                results = listOf(result),
                textSetBySearch = it.textSetBySearch + 1,
            )
        }
        select(result)
    }

    /** Empties the bar and keeps typing. */
    fun clearQuery() {
        cancelSearch()
        _state.update { SearchState(mode = SearchMode.Editing, textSetBySearch = it.textSetBySearch + 1) }
    }

    /** Leaves search: the map as it was, the bar empty, and no search outage named on the map. */
    fun close() {
        cancelSearch()
        health?.recovered(ServicePart.Search)
        _state.update { SearchState(textSetBySearch = it.textSetBySearch + 1) }
    }

    /** The place card's close button: back to the results, or out of search for a lone result. */
    fun closeCard() {
        val s = state.value
        if (s.results.size > 1) _state.update { it.copy(mode = SearchMode.Results, selected = null) } else close()
    }

    /**
     * The system back action. Returns false when search has nothing to go back from (the map then
     * handles it). Typing goes back to the results on the map when the text has some, else out of
     * search; a picked place goes back to its results.
     */
    fun back(): Boolean {
        val s = state.value
        when (s.mode) {
            SearchMode.Idle -> {
                return false
            }

            SearchMode.Editing -> {
                when {
                    s.selected != null -> _state.update { it.copy(mode = SearchMode.Selected) }
                    s.results.isNotEmpty() && s.resultsAreCurrent -> _state.update { it.copy(mode = SearchMode.Results) }
                    else -> close()
                }
            }

            SearchMode.Results -> {
                close()
            }

            SearchMode.Selected -> {
                closeCard()
            }
        }
        return true
    }

    private fun searchLater(
        text: String,
        area: SearchArea,
    ) = startSearch(text, area, wait = true)

    private fun startSearch(
        text: String,
        area: SearchArea,
        wait: Boolean,
    ) {
        searchText = text
        searchJob =
            scope.launch {
                if (wait) delay(debounce)
                run(text, area)
            }
    }

    /** True while [query] is still the text in the bar (spaces around it do not count). */
    private fun SearchState.stillFor(query: String) = this.query.trim() == query.trim()

    private fun clearResults() =
        _state.update {
            it.copy(
                results = emptyList(),
                resultsQuery = "",
                photon = SourceState.Idle,
                pinakes = SourceState.Idle,
                offlineMaps = SourceState.Idle,
            )
        }

    private fun showOnly(
        result: SearchResult,
        query: String,
    ) = _state.update {
        it.copy(
            results = listOf(result),
            resultsQuery = query,
            photon = SourceState.Idle,
            pinakes = SourceState.Idle,
            offlineMaps = SourceState.Idle,
        )
    }

    /** Asks both sources; the list updates as each answers. */
    private suspend fun run(
        text: String,
        area: SearchArea,
    ) {
        val query = state.value.query
        val answers = MutableStateFlow(Answers())
        // Only if the text is still the same: a search cancelled in between must not leave a spinner.
        _state.update { if (it.stillFor(query)) it.copy(photon = SourceState.Loading, pinakes = SourceState.Loading) else it }

        fun publish(a: Answers) {
            _state.update {
                if (!it.stillFor(query)) return@update it
                it.copy(
                    results = SearchRanking.rank(text, area, a.photon, a.pinakes, a.offline),
                    resultsQuery = query,
                    photon = a.photonState,
                    pinakes = a.pinakesState,
                    offlineMaps = a.offlineState,
                )
            }
            when {
                a.photonState == SourceState.Unreachable && a.pinakesState == SourceState.Unreachable -> {
                    health?.failed(ServicePart.Search)
                }

                a.photonState == SourceState.Answered || a.pinakesState == SourceState.Answered -> {
                    health?.recovered(ServicePart.Search)
                }

                else -> {}
            }
        }
        searchBoth(text, area, answers, ::publish)
    }

    private suspend fun searchBoth(
        text: String,
        area: SearchArea,
        answers: MutableStateFlow<Answers>,
        publish: (Answers) -> Unit,
    ) {
        coroutineScope {
            launch {
                val answer = search.photon(text, area)
                // Asked before Photon's failure shows, so the list goes straight to the offline places.
                val places = if (answer is SourceAnswer.Unreachable) searchOffline(text, area) else null
                publish(
                    answers.updateAndGet {
                        it.copy(
                            photon = answer.valueOrEmpty(),
                            photonState = answer.state(),
                            offline = places.orEmpty(),
                            offlineState = if (places != null) SourceState.Answered else SourceState.Idle,
                        )
                    },
                )
            }
            launch {
                val answer = search.pinakes(text, area, viewer())
                publish(answers.updateAndGet { it.copy(pinakes = answer.valueOrEmpty(), pinakesState = answer.state()) })
            }
        }
    }

    /** The offline maps' places for [text]; null when there are none to search or they cannot be read. */
    private suspend fun searchOffline(
        text: String,
        area: SearchArea,
    ): List<OfflinePlace>? =
        try {
            offline?.search(text, area)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private data class Answers(
        val photon: List<PhotonPlace> = emptyList(),
        val pinakes: List<PinakesPlace> = emptyList(),
        val offline: List<OfflinePlace> = emptyList(),
        val photonState: SourceState = SourceState.Loading,
        val pinakesState: SourceState = SourceState.Loading,
        val offlineState: SourceState = SourceState.Idle,
    )

    companion object {
        /** Shorter text is not searched while typing (Photon fair use); Search still asks. */
        const val MIN_QUERY_LENGTH = 3
        val DEBOUNCE: Duration = 300.milliseconds

        /** The zoom a typed point is shown at. */
        const val COORDINATE_ZOOM = 16.0
    }
}

private fun <T> MutableStateFlow<T>.updateAndGet(transform: (T) -> T): T {
    while (true) {
        val prev = value
        val next = transform(prev)
        if (compareAndSet(prev, next)) return next
    }
}

private fun <T> SourceAnswer<List<T>>.valueOrEmpty(): List<T> = (this as? SourceAnswer.Answered)?.value.orEmpty()

private fun SourceAnswer<*>.state(): SourceState =
    when (this) {
        is SourceAnswer.Answered -> SourceState.Answered
        SourceAnswer.Unreachable -> SourceState.Unreachable
    }

/** The result for typed coordinates: a pin at the point, named by its label or its coordinates. */
internal fun coordinateResult(geo: GeoInput.Point): SearchResult =
    SearchResult(
        place =
            PlaceRef(
                uri = "geo:${formatCoordinate(geo.point.lat)},${formatCoordinate(geo.point.lon)}",
                name = geo.label,
                point = geo.point,
                osm = null,
                source = PlaceSource.Coordinates,
                zoom = geo.zoom ?: SearchController.COORDINATE_ZOOM,
                category = "Coordinates",
            ),
        kind = PlaceKind.Point,
    )
