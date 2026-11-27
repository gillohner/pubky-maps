package to.pubky.maps.search

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.place.KeptDetails
import to.pubky.maps.place.KeptPlaceDetails
import kotlin.test.Test
import kotlin.test.assertEquals

class KeptHoursTest {
    private val museum = OsmId(OsmType.Way, 1)
    private val bar = OsmId(OsmType.Node, 2)
    private val stop = OsmId(OsmType.Node, 3)
    private val broken = OsmId(OsmType.Node, 4)
    private val unknown = OsmId(OsmType.Node, 5)

    private val asked = ArrayList<OsmId>()

    private val details =
        KeptPlaceDetails { osm ->
            asked += osm
            when (osm) {
                museum -> KeptDetails(mapOf("opening_hours" to " Tu-Su 10:00-18:00 ", "phone" to "+41 44 253 84 84"), fetchedAt = 1)
                bar -> KeptDetails(mapOf("opening_hours" to "Mo-Sa 17:00-24:00"), fetchedAt = 1)
                stop -> KeptDetails(mapOf("wheelchair" to "yes", "opening_hours" to " "), fetchedAt = 1)
                broken -> error("row in a shape this version cannot read")
                else -> null
            }
        }

    @Test
    fun readsTheHoursAsThePlacePageDoes() =
        runTest {
            val hours =
                details
                    .openingHours(
                        StandardTestDispatcher(testScheduler),
                    ).hoursOf(listOf(museum, bar, stop, broken, unknown, museum))
            // Trimmed as on the page; an element with no hours, an unreadable one and an unknown one have none.
            assertEquals(mapOf(museum to "Tu-Su 10:00-18:00", bar to "Mo-Sa 17:00-24:00"), hours)
            // Each element is read once.
            assertEquals(listOf(museum, bar, stop, broken, unknown), asked)
        }
}
