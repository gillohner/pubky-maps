package to.pubky.maps.offline

import to.pubky.maps.links.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalitiesTest {
    private fun label(
        name: String,
        kind: String,
        lat: Double,
        lon: Double,
    ) = OfflinePlace(name, null, "place", kind, null, GeoPoint(lat, lon))

    private fun cafe(
        lat: Double,
        lon: Double,
    ) = OfflinePlace("Café", null, "amenity", "cafe", "Café", GeoPoint(lat, lon))

    // About 111 m per 0.001° of latitude.
    private val zurich = label("Zürich", "city", 47.3700, 8.5400)
    private val hottingen = label("Hottingen", "quarter", 47.3700, 8.5600)
    private val zollikerberg = label("Zollikerberg", "village", 47.3450, 8.6000)
    private val winterthur = label("Winterthur", "town", 47.5000, 8.7240)
    private val winterthurOldTown = label("Winterthur", "suburb", 47.5000, 8.7250)

    private val localities = Localities(listOf(zurich, hottingen, zollikerberg, winterthur, winterthurOldTown))

    @Test
    fun aPlaceIsInTheNearestDistrictAndSettlementForTheirReach() {
        assertEquals("Hottingen, Zürich", localities.of(cafe(47.3705, 8.5590)))
        // 4.4 km from the city's centre and 0.9 km from the village: the city reaches six times as far.
        assertEquals("Zürich", localities.at(47.3500, 8.5900))
        // In the village's centre the village wins.
        assertEquals("Zollikerberg", localities.at(47.3452, 8.6002))
    }

    @Test
    fun aLabelFartherThanItsReachNamesNothing() {
        // 1.4 km from Hottingen (a quarter reaches 1.2 km), 0.2 km from Zürich's centre.
        assertEquals("Zürich", localities.at(47.3700, 8.5420))
        // 30 km from every label.
        assertNull(localities.at(47.1000, 8.2000))
        assertNull(Localities(emptyList()).at(47.37, 8.54))
    }

    @Test
    fun aDistrictNamedAsItsTownIsSaidOnce() {
        assertEquals("Winterthur", localities.at(47.5010, 8.7250))
    }

    @Test
    fun aSettlementHasNoLocalityAndADistrictOnlyItsSettlement() {
        assertNull(localities.of(zurich))
        assertNull(localities.of(winterthur))
        assertEquals("Zürich", localities.of(hottingen))
        // A label never names itself: the old town's suburb label is not "Winterthur" twice nor itself.
        assertNull(localities.of(winterthurOldTown))
        // A hamlet is a district: it is named after its village.
        assertEquals("Zollikerberg", localities.of(label("Waltikon", "hamlet", 47.3500, 8.6000)))
        // Other labels (a named locality without people) get their district too.
        assertEquals("Hottingen, Zürich", localities.of(label("Dolder", "locality", 47.3705, 8.5590)))
    }

    @Test
    fun aCityReachesOverManyCells() {
        // 11 km north of Zürich's centre, where no other label is: still within its 12 km.
        assertEquals("Zürich", localities.at(47.4690, 8.5400))
        assertNull(localities.at(47.4800, 8.5400))
    }
}
