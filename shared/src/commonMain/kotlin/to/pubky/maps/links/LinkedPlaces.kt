package to.pubky.maps.links

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import to.pubky.maps.place.PlaceRef

/** The place the map shows because a link was opened, and the links shared into the app. */
data class LinkedPlaceState(
    val place: PlaceRef? = null,
    /** The app the link came from. */
    val provider: LinkProvider? = null,
    /** A link is being read (a short link followed, Pinakes asked). */
    val opening: Boolean = false,
    /** Grows with every [LinkedPlaces.open] and [LinkedPlaces.close]; a result of an older one is dropped. */
    val generation: Int = 0,
    /**
     * The [generation] that set [place]. The map moves to the place once per value, so coming back
     * from Settings does not move it again, and the same link opened twice does.
     */
    val placedAt: Int = 0,
)

/**
 * Opens the map links other apps hand to Pubky Maps, one at a time: a newer link replaces one still
 * being read. Lives as long as the app process, so a place survives the activity being recreated.
 */
class LinkedPlaces(
    private val links: MapLinks,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(LinkedPlaceState())
    val state: StateFlow<LinkedPlaceState> = _state.asStateFlow()

    // Buffered, so a note sent before the map screen collects (it was behind Settings) still shows.
    private val _messages = Channel<String>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** One-off notes for the user ("no place in this link"); for one collector, the map screen. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var job: Job? = null

    /** Opens the map link in [text] with the user's Pinakes servers (in order). */
    fun open(
        text: String,
        pinakesServers: List<String>,
    ) {
        job?.cancel()
        val generation = _state.updateAndGet { it.copy(opening = true, generation = it.generation + 1) }.generation
        job =
            scope.launch {
                val result = links.openAsPlace(text, pinakesServers)
                // Written only if no newer open or close came meanwhile (a cancel does not stop a job
                // that already got its result).
                val current =
                    _state.updateAndGet {
                        when {
                            it.generation != generation -> {
                                it
                            }

                            result is LinkOpening.Opened -> {
                                LinkedPlaceState(
                                    result.place,
                                    result.provider,
                                    generation = generation,
                                    placedAt = generation,
                                )
                            }

                            else -> {
                                it.copy(opening = false)
                            }
                        }
                    }
                if (current.generation == generation && result == LinkOpening.NotAMapLink) _messages.trySend(NOT_A_MAP_LINK)
            }
    }

    /** Closes the place card. */
    fun close() {
        job?.cancel()
        _state.update { LinkedPlaceState(generation = it.generation + 1, placedAt = it.placedAt) }
    }

    companion object {
        const val NOT_A_MAP_LINK = "This link has no place Pubky Maps can show."
    }
}

/** What the app needs for map links: the core's reader, the opened place, and the platform's share sheet. */
class AppLinks(
    val mapLinks: MapLinks,
    val linkedPlaces: LinkedPlaces,
    /** Texts of links other apps sent (`VIEW`/`SEND` intents), each to be opened once. */
    val incoming: Flow<String>,
    /** Shares text through the system share sheet. */
    val share: (String) -> Unit,
)
