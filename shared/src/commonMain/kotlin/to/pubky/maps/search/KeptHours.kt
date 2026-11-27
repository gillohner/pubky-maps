package to.pubky.maps.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import to.pubky.maps.links.OsmId
import to.pubky.maps.place.KeptPlaceDetails
import to.pubky.maps.place.OsmDetails

/** The opening hours the offline areas keep for places, which search shows on its result rows. */
fun interface KeptOpeningHours {
    /** The OSM `opening_hours` kept for those of [elements] that have any; empty when none can be read. */
    suspend fun hoursOf(elements: Collection<OsmId>): Map<OsmId, String>
}

/**
 * The opening hours of these kept details, read on [io], as the place page reads them
 * ([OsmDetails.openingHours]); an element whose details cannot be read has none.
 */
fun KeptPlaceDetails.openingHours(io: CoroutineDispatcher = Dispatchers.IO): KeptOpeningHours =
    KeptOpeningHours { elements ->
        withContext(io) {
            elements
                .distinct()
                .mapNotNull { osm ->
                    val kept =
                        try {
                            detailsOf(osm)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    kept
                        ?.facts
                        ?.let(OsmDetails::from)
                        ?.openingHours
                        ?.let { osm to it }
                }.toMap()
        }
    }
