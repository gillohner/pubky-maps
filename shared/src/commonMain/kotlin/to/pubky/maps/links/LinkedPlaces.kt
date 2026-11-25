package to.pubky.maps.links

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import to.pubky.maps.place.PlaceRef

/** The place the map shows because a link was opened, and the links shared into the app. */
data class LinkedPlaceState(
    val place: PlaceRef? = null,
    /** The app the link came from. */
    val provider: LinkProvider? = null,
    /** A link is being read (a short link followed, Pinakes asked). */
    val opening: Boolean = false,
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

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** One-off notes for the user ("no place in this link"). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var job: Job? = null

    /** Opens the map link in [text] with the user's Pinakes servers (in order). */
    fun open(
        text: String,
        pinakesServers: List<String>,
    ) {
        job?.cancel()
        _state.value = _state.value.copy(opening = true)
        job =
            scope.launch {
                when (val result = links.openAsPlace(text, pinakesServers)) {
                    is LinkOpening.Opened -> {
                        _state.value = LinkedPlaceState(place = result.place, provider = result.provider)
                    }

                    LinkOpening.NotAMapLink -> {
                        _state.value = _state.value.copy(opening = false)
                        _messages.tryEmit(NOT_A_MAP_LINK)
                    }
                }
            }
    }

    /** Closes the place card. */
    fun close() {
        job?.cancel()
        _state.value = LinkedPlaceState()
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
