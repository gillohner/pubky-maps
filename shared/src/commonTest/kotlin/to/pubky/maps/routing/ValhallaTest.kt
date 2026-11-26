package to.pubky.maps.routing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.search.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ValhallaTest {
    @Test
    fun aRouteAnswerGivesItsLineTimeAndLength() {
        // FOSSGIS' answer by car from Zürich HB to the Kunsthaus (2026-11-26).
        val route = parseValhallaRoute(Fixtures.read("hauptbahnhof-kunsthaus.valhalla.json"))
        assertEquals(94, route.points.size)
        assertEquals(GeoPoint(47.378186, 8.540583), route.points.first())
        assertEquals(GeoPoint(47.370255, 8.548698), route.points.last())
        assertEquals(289.373, route.seconds, 0.001)
        assertEquals(1401.0, route.meters, 0.001)
    }

    @Test
    fun legsAreJoinedWithoutRepeatingTheirSharedPoint() {
        // Two legs: (1,2) -> (1.5,2.5) and (1.5,2.5) -> (2,3).
        val json =
            """{"trip":{"legs":[{"shape":"${encode(GeoPoint(1.0, 2.0), GeoPoint(1.5, 2.5))}"},""" +
                """{"shape":"${encode(GeoPoint(1.5, 2.5), GeoPoint(2.0, 3.0))}"}],"summary":{"time":60,"length":2.5}}}"""
        val route = parseValhallaRoute(json)
        assertEquals(listOf(GeoPoint(1.0, 2.0), GeoPoint(1.5, 2.5), GeoPoint(2.0, 3.0)), route.points)
        assertEquals(2500.0, route.meters, 0.001)
    }

    @Test
    fun anAnswerWithoutALineIsNotARoute() {
        assertFailsWith<IllegalArgumentException> { parseValhallaRoute("""{"trip":{"legs":[],"summary":{"time":0,"length":0}}}""") }
    }

    @Test
    fun polylineDecodesNegativeCoordinatesAndStopsAtATruncatedPoint() {
        val encoded = encode(GeoPoint(-33.868820, 151.209296), GeoPoint(40.712776, -74.005974))
        assertEquals(listOf(GeoPoint(-33.86882, 151.209296), GeoPoint(40.712776, -74.005974)), decodePolyline6(encoded))
        assertEquals(listOf(GeoPoint(-33.86882, 151.209296)), decodePolyline6(encoded.dropLast(1)))
        assertEquals(emptyList(), decodePolyline6(""))
    }

    @Test
    fun theRequestAsksForTheLineOnlyByTheModesCosting() {
        val body = Json.parseToJsonElement(valhallaRouteBody(GeoPoint(47.0, 8.0), GeoPoint(47.5, 8.5), TravelMode.Bike)).jsonObject
        assertEquals("bicycle", body["costing"]!!.jsonPrimitive.content)
        assertEquals("none", body["directions_type"]!!.jsonPrimitive.content)
        assertEquals("polyline6", body["shape_format"]!!.jsonPrimitive.content)
        val locations = body["locations"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(47.0, 47.5), locations.map { it["lat"]!!.jsonPrimitive.double })
        assertEquals(listOf(8.0, 8.5), locations.map { it["lon"]!!.jsonPrimitive.double })
        assertEquals(listOf("auto", "bicycle", "pedestrian"), TravelMode.entries.map { it.costing })
    }

    @Test
    fun errorCodesSayWhyThereIsNoRoute() {
        // FOSSGIS' answer to a 4,000 km walk.
        val tooFar = """{"error_code":154,"error":"Path distance exceeds the max distance limit: 100000 meters","status_code":400}"""
        assertEquals(154, valhallaErrorCode(tooFar))
        assertNull(valhallaErrorCode("<html>Bad Request</html>"))
        assertEquals(NoRouteReason.TooFar, noRouteReason(154))
        assertEquals(NoRouteReason.NoRoadNear, noRouteReason(171))
        assertEquals(NoRouteReason.NotConnected, noRouteReason(442))
        assertEquals(NoRouteReason.NotConnected, noRouteReason(199))
    }

    private fun encode(vararg points: GeoPoint): String {
        val out = StringBuilder()
        var lastLat = 0L
        var lastLon = 0L

        fun put(value: Long) {
            var v = if (value < 0) (value shl 1).inv() else value shl 1
            while (v >= 0x20) {
                out.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
                v = v shr 5
            }
            out.append((v.toInt() + 63).toChar())
        }
        for (p in points) {
            val lat = kotlin.math.round(p.lat * 1e6).toLong()
            val lon = kotlin.math.round(p.lon * 1e6).toLong()
            put(lat - lastLat)
            put(lon - lastLon)
            lastLat = lat
            lastLon = lon
        }
        // A backslash in the line would need escaping in JSON; these test points have none.
        return out.toString()
    }
}
