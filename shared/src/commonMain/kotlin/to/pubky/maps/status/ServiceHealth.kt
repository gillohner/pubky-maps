package to.pubky.maps.status

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A part of the app that depends on a server and can be missing while the rest works. */
enum class ServicePart(
    /** One short line for the map's banner. */
    val missingText: String,
) {
    Homeserver("Can't reach your homeserver"),

    /** Every Pinakes server failed: places from links show from their link text only. */
    Pinakes("Can't reach Pinakes"),

    /** Neither Photon nor Pinakes answered the last search. */
    Search("Search is offline"),

    /** No routing server answered the last route. */
    Routing("Can't reach routing"),
}

/**
 * Which parts are missing because every server of their list failed. The map shows them in one line
 * and keeps working.
 */
class ServiceHealth {
    private val state = MutableStateFlow<Set<ServicePart>>(emptySet())

    val missing: StateFlow<Set<ServicePart>> = state.asStateFlow()

    fun failed(part: ServicePart) = state.update { it + part }

    fun recovered(part: ServicePart) = state.update { it - part }
}
