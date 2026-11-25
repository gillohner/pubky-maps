package to.pubky.maps.search

import to.pubky.maps.links.GeoPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeoInputTest {
    private fun point(
        text: String,
        reference: GeoPoint? = null,
    ): GeoPoint? = (parseGeoInput(text, reference) as? GeoInput.Point)?.point

    private fun assertNear(
        expected: GeoPoint,
        actual: GeoPoint?,
        tolerance: Double = 1e-4,
    ) {
        requireNotNull(actual) { "no point" }
        assertTrue(
            abs(expected.lat - actual.lat) < tolerance && abs(expected.lon - actual.lon) < tolerance,
            "expected $expected, got $actual",
        )
    }

    @Test
    fun decimalCoordinates() {
        assertEquals(GeoPoint(47.37, 8.54), point("47.37, 8.54"))
        assertEquals(GeoPoint(47.37, 8.54), point("47.37,8.54"))
        assertEquals(GeoPoint(47.37, 8.54), point("  47.37 8.54 "))
        assertEquals(GeoPoint(-33.8568, 151.2153), point("-33.8568, 151.2153"))
        assertEquals(GeoPoint(47.0, 8.0), point("47, 8"))
        assertEquals(GeoPoint(47.37, 8.54), point("47.37; 8.54"))
    }

    @Test
    fun hemisphereLetters() {
        assertEquals(GeoPoint(47.37, 8.54), point("47.37N 8.54E"))
        assertEquals(GeoPoint(-47.37, -8.54), point("47.37 S, 8.54 W"))
        assertEquals(GeoPoint(47.37, 8.54), point("N 47.37 E 8.54"))
        // Longitude first, named as such.
        assertEquals(GeoPoint(47.37, 8.54), point("8.54E 47.37N"))
        assertNull(point("47.37N 8.54N"))
        assertNull(point("-47.37N 8.54E"))
    }

    @Test
    fun degreesMinutesSeconds() {
        assertNear(GeoPoint(47.366667, 8.533333), point("47°22'N 8°32'E"))
        assertNear(GeoPoint(47.370139, 8.534444), point("47°22'12.5\"N 8°32'4\"E"))
        assertNear(GeoPoint(47.370139, 8.534444), point("47°22′12.5″N 8°32′4″E"))
        assertNear(GeoPoint(-33.856667, 151.215), point("33° 51.4' S, 151° 12.9' E"))
        assertNear(GeoPoint(47.37, 8.54), point("47.37° 8.54°"))
        assertNull(point("47°61'N 8°32'E"))
    }

    @Test
    fun textAndNumbersThatAreNoCoordinates() {
        assertNull(parseGeoInput("Bahnhofstrasse 1"))
        assertNull(parseGeoInput("8001 2"))
        assertNull(parseGeoInput("47 8"))
        assertNull(parseGeoInput("1234"))
        assertNull(parseGeoInput("95.0, 8.0"))
        assertNull(parseGeoInput("47.0, 181.0"))
        assertNull(parseGeoInput(""))
        assertNull(parseGeoInput("E 47.37 N 8.54 extra"))
    }

    @Test
    fun geoUris() {
        assertEquals(GeoInput.Point(GeoPoint(47.37, 8.54)), parseGeoInput("geo:47.37,8.54"))
        assertEquals(GeoInput.Point(GeoPoint(47.37, 8.54)), parseGeoInput("GEO:47.37,8.54,410;u=35"))
        assertEquals(GeoInput.Point(GeoPoint(47.37, 8.54), zoom = 15.0), parseGeoInput("geo:47.37,8.54?z=15"))
        assertEquals(
            GeoInput.Point(GeoPoint(47.1, 8.2), label = "Grandma's"),
            parseGeoInput("geo:0,0?q=47.1,8.2(Grandma%27s)"),
        )
        assertEquals(GeoInput.Query("Kunsthaus Zürich"), parseGeoInput("geo:0,0?q=Kunsthaus+Z%C3%BCrich"))
        assertNull(parseGeoInput("geo:47.37,8.54;crs=utm"))
        assertNull(parseGeoInput("geo:147.37,8.54"))
        assertNull(parseGeoInput("geo:hello"))
        assertNull(parseGeoInput("geo:0,0"))
        assertEquals(GeoInput.Point(GeoPoint(47.37, 8.54)), parseGeoInput("geo:47.37,8.54?z=99"))
    }

    @Test
    fun plusCodes() {
        // The plus code specification's example (Zürich).
        assertNear(GeoPoint(47.365562, 8.524813), point("8FVC9G8F+6W"), 1e-4)
        assertNear(GeoPoint(47.365562, 8.524813), point("8fvc9g8f+6w"), 1e-4)
        assertNear(GeoPoint(47.365562, 8.524813), point("8FVC9G8F+6WQ"), 1e-4)
        // A short code needs a place nearby: the map centre.
        assertNear(GeoPoint(47.365562, 8.524813), point("9G8F+6W", GeoPoint(47.37, 8.54)), 1e-4)
        assertNull(parseGeoInput("9G8F+6W"))
        assertNull(parseGeoInput("8FVC9G8F+6"))
        assertNull(parseGeoInput("WFVC9G8F+6X"))
    }

    @Test
    fun aShortPlusCodeNearAnAreaEdgeGoesToTheNearerSide() {
        // The reference sits just across the 1-degree line from the code's area.
        assertNear(GeoPoint(47.365562, 8.524813), point("9G8F+6W", GeoPoint(46.9, 8.5)), 1e-4)
        // The nearest of the repeating areas wins: from 9.4 E that is the one at 9.5 E, not 8.5 E.
        assertNear(GeoPoint(47.365562, 9.524813), point("9G8F+6W", GeoPoint(47.3, 9.4)), 1e-4)
    }

    @Test
    fun coordinatesAreOneResultWithNoNetwork() {
        val result = coordinateResult(parseGeoInput("47.37, 8.54") as GeoInput.Point)
        assertEquals("geo:47.37,8.54", result.place.uri)
        assertEquals("47.37, 8.54", result.place.title)
        assertEquals(SearchController.COORDINATE_ZOOM, result.place.zoom)
        val labelled = coordinateResult(parseGeoInput("geo:0,0?q=47.1,8.2(Home)") as GeoInput.Point)
        assertEquals("Home", labelled.place.title)
    }
}
