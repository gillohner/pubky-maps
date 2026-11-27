package to.pubky.maps.routing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.search.Fixtures
import kotlin.math.round
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
    fun aRouteAnswerGivesItsStepsInOrder() {
        // The same answer, asked with turn instructions in English.
        val steps = parseValhallaRoute(Fixtures.read("hauptbahnhof-kunsthaus.valhalla.json")).steps
        assertEquals(
            listOf(
                RouteStep("Drive north.", Turn.Depart, 35.0),
                RouteStep("Turn right onto Museumstrasse.", Turn.Right, 52.0),
                RouteStep("Bear right onto Bahnhofquai.", Turn.SlightRight, 30.0),
                RouteStep("Keep left to stay on Bahnhofquai.", Turn.KeepLeft, 406.0),
                RouteStep("Turn left onto Rudolf-Brun-Brücke.", Turn.Left, 329.0),
                RouteStep("Turn right onto Seilergraben.", Turn.Right, 205.0),
                RouteStep("Bear right onto Hirschengraben.", Turn.SlightRight, 262.0),
                RouteStep("Bear right onto Heimplatz.", Turn.SlightRight, 81.0),
                RouteStep("Your destination is on the right.", Turn.Arrive, 0.0),
            ),
            steps.map { it.copy(meters = round(it.meters)) },
        )
    }

    @Test
    fun stepsOfEveryLegFollowEachOtherAndOnesWithoutASentenceAreLeftOut() {
        val json =
            """{"trip":{"legs":[{"shape":"${encode(GeoPoint(1.0, 2.0), GeoPoint(1.5, 2.5))}","maneuvers":[""" +
                """{"type":1,"instruction":"Walk east.","length":0.5},{"type":99,"instruction":"Take the gondola.","length":1.2},""" +
                """{"type":15,"instruction":" ","length":0.1}]},""" +
                """{"shape":"${encode(
                    GeoPoint(1.5, 2.5),
                    GeoPoint(2.0, 3.0),
                )}","maneuvers":[{"type":6,"instruction":"You have arrived."}]}],""" +
                """"summary":{"time":60,"length":2.5}}}"""
        assertEquals(
            listOf(
                RouteStep("Walk east.", Turn.Depart, 500.0),
                RouteStep("Take the gondola.", Turn.Straight, 1200.0),
                RouteStep("You have arrived.", Turn.Arrive, 0.0),
            ),
            parseValhallaRoute(json).steps,
        )
    }

    @Test
    fun maneuverTypesGiveTheStepsIcons() {
        val turns = (0..43).associateWith(::valhallaTurn)
        assertEquals(Turn.Depart, turns[2])
        assertEquals(Turn.Arrive, turns[5])
        assertEquals(Turn.SharpRight, turns[11])
        assertEquals(Turn.UTurnLeft, turns[13])
        assertEquals(Turn.SharpLeft, turns[14])
        assertEquals(Turn.SlightLeft, turns[16])
        assertEquals(Turn.Straight, turns[17])
        assertEquals(Turn.KeepRight, turns[20])
        assertEquals(Turn.KeepLeft, turns[19])
        assertEquals(Turn.Merge, turns[38])
        assertEquals(Turn.Roundabout, turns[26])
        assertEquals(Turn.Ferry, turns[28])
        assertEquals(Turn.Straight, turns[8])
        // Every kind of step has a type that gives it.
        assertEquals(Turn.entries.toSet(), turns.values.toSet())
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
    fun theRequestAsksForTheLineAndEnglishStepsByTheModesCosting() {
        val body = Json.parseToJsonElement(valhallaRouteBody(GeoPoint(47.0, 8.0), GeoPoint(47.5, 8.5), TravelMode.Bike)).jsonObject
        assertEquals("bicycle", body["costing"]!!.jsonPrimitive.content)
        assertEquals("instructions", body["directions_type"]!!.jsonPrimitive.content)
        assertEquals("en-US", body["language"]!!.jsonPrimitive.content)
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
        assertEquals(NoRouteReason.NotConnected, noRouteReason(170))
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
