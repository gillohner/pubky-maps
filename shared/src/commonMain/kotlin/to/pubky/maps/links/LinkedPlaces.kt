package to.pubky.maps.links

import kotlinx.coroutines.CancellationException
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
import to.pubky.maps.place.toPlaceRef

/** The place the map shows because a link was opened, and the links shared into the app. */
data class LinkedPlaceState(
    val place: PlaceRef? = null,
    /** The app the link came from. */
    val provider: LinkProvider? = null,
    /** A link is being read and there is nothing to show yet (a short link is being followed). */
    val opening: Boolean = false,
    /** Grows with every [LinkedPlaces.open], [LinkedPlaces.show] and [LinkedPlaces.close]; a result of an older one is dropped. */
    val generation: Int = 0,
    /**
     * Changes whenever [place] gets a new point to show. The map moves there once per value, so coming
     * back from Settings does not move it again, and the same link opened twice does.
     */
    val placedAt: Int = 0,
)

/**
 * Opens the map links other apps hand to Pubky Maps, one at a time: a newer link replaces one still
 * being read. A link that names a point (or a search) shows at once from its text; then the core
 * follows a short link and asks Pinakes, and the result refines the place where it is. Lives as long
 * as the app process, so a place survives the activity being recreated. A saved point is shown
 * through [show] instead, read by no core. [onPinakesReachable] hears
 * whether the Pinakes servers answered, for the map's "missing" line.
 */
class LinkedPlaces(
    private val links: MapLinks,
    private val scope: CoroutineScope,
    private val onPinakesReachable: (Boolean) -> Unit = {},
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
                val fromText = attempt { links.read(text) }
                if (fromText == null) {
                    val current = write(generation) { it.copy(opening = false) }
                    if (current.generation == generation) _messages.trySend(NOT_A_MAP_LINK)
                    return@launch
                }
                // Shown at once when the text names a point or a search; a short link waits for its
                // redirect (its text holds no place).
                val shownNow = fromText.point != null || fromText.query != null
                if (shownNow) write(generation) { placed(fromText, generation, placedAt = 2 * generation) }
                val refined =
                    try {
                        links.open(text, pinakesServers) ?: fromText
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // No core (it could not be built): the link text is all there is.
                        fromText.copy(coreUnavailable = true)
                    }
                if (pinakesServers.isNotEmpty() && !refined.coreUnavailable) onPinakesReachable(!refined.pinakesUnreachable)
                write(generation) {
                    val moved = !shownNow || refined.point != fromText.point
                    placed(refined, generation, placedAt = if (moved) 2 * generation + 1 else it.placedAt)
                }
            }
    }

    /**
     * Shows [place] as it is, read by no core and asked of no server: a saved point (a dropped pin,
     * typed coordinates) that stays on the phone. Replaces a link still being read.
     */
    fun show(place: PlaceRef) {
        job?.cancel()
        _state.update { LinkedPlaceState(place = place, generation = it.generation + 1, placedAt = 2 * (it.generation + 1)) }
    }

    /** Closes the place card. */
    fun close() {
        job?.cancel()
        _state.update { LinkedPlaceState(generation = it.generation + 1, placedAt = it.placedAt) }
    }

    /**
     * Applies [change] only while no newer open or close came (a cancel does not stop a job that already
     * got its result); returns the state after.
     */
    private fun write(
        generation: Int,
        change: (LinkedPlaceState) -> LinkedPlaceState,
    ): LinkedPlaceState = _state.updateAndGet { if (it.generation == generation) change(it) else it }

    private fun placed(
        link: OpenedLink,
        generation: Int,
        placedAt: Int,
    ) = LinkedPlaceState(place = link.toPlaceRef(), provider = link.provider, generation = generation, placedAt = placedAt)

    /** [block]'s result, or null when it throws (a shared link must never crash the app). */
    private inline fun <T> attempt(block: () -> T?): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
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
    /** Which map apps are on the phone, for "Open in". */
    val installedApps: InstalledApps,
    /** Opens an "Open in" entry in its app (or the browser, or the `geo:` chooser). */
    val openApp: (OpenInAction) -> Unit,
)
