package to.pubky.maps.routing

import to.pubky.maps.links.GeoPoint
import to.pubky.maps.search.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoicePromptsTest {
    // FOSSGIS' route by car from Zürich HB to the Kunsthaus, with Valhalla's verbal instructions.
    private val route = parseValhallaRoute(Fixtures.read("hauptbahnhof-kunsthaus.valhalla.json"))
    private val kunsthaus = route.points.last()

    private fun guiding(
        next: Int?,
        toNext: Double,
        mode: TravelMode = TravelMode.Car,
        on: Route = route,
        offRoute: Boolean = false,
        arrived: Boolean = false,
    ) = Directions(
        placeUri = "osm:relation/3768127",
        place = kunsthaus,
        mode = mode,
        position = route.points.first(),
        status = RouteStatus.Found(on, "https://valhalla1.openstreetmap.de"),
        guiding = true,
        progress =
            RouteProgress(
                segment = 0,
                along = 0.0,
                offBy = if (offRoute) 80.0 else 3.0,
                offRoute = offRoute,
                arrived = arrived,
                nextStep = next,
                toNextStep = toNext,
                metersLeft = toNext,
                secondsLeft = 60.0,
                ahead = 0.0,
            ),
    )

    @Test
    fun aDriveSaysEachTurnOnceWarnedAheadAndAgainJustBefore() {
        val prompts = VoicePrompts()
        val said =
            listOf(
                // The first turn is 35 m away: it is said instead of "Drive north.".
                guiding(1, 35.0),
                guiding(1, 20.0),
                guiding(2, 25.0),
                guiding(3, 10.0),
                // 400 m of Bahnhofquai to the bridge: warned at once, said again 110 m before.
                guiding(4, 400.0),
                guiding(4, 300.0),
                guiding(4, 110.0),
                guiding(4, 40.0),
                guiding(5, 320.0),
                guiding(5, 100.0),
                guiding(8, 100.0),
                guiding(8, 20.0, arrived = true),
            ).map(prompts::next)
        assertEquals(
            listOf(
                "Turn right onto Museumstrasse. Then Bear right onto Bahnhofquai.",
                null,
                "Bear right onto Bahnhofquai. Then Keep left to stay on Bahnhofquai.",
                "Keep left to stay on Bahnhofquai.",
                "In 400 meters, turn left onto Rudolf-Brun-Brücke.",
                null,
                "Turn left onto Rudolf-Brun-Brücke.",
                null,
                "In 300 meters, turn right onto Seilergraben.",
                "Turn right onto Seilergraben.",
                "Your destination is on the right.",
                // Already said on the way in.
                null,
            ),
            said,
        )
    }

    @Test
    fun aFirstTurnFarAheadComesAfterTheWayToStart() {
        val prompts = VoicePrompts()
        assertEquals("Drive north. Then, in 40 meters, Turn right onto Museumstrasse.", prompts.next(guiding(1, 300.0)))
        assertEquals("In 300 meters, turn right onto Museumstrasse.", prompts.next(guiding(1, 290.0)))
    }

    @Test
    fun aLongStretchAfterATurnIsSaidAndItsWarningComesLater() {
        val prompts = VoicePrompts()
        // On foot the warning for Seilergraben comes 140 m before it (30 s at the stretch's pace).
        prompts.next(guiding(4, 20.0, TravelMode.Walk))
        assertEquals("Continue for 300 meters.", prompts.next(guiding(5, 320.0, TravelMode.Walk)))
        assertNull(prompts.next(guiding(5, 200.0, TravelMode.Walk)))
        assertEquals("In 150 meters, turn right onto Seilergraben.", prompts.next(guiding(5, 130.0, TravelMode.Walk)))
        assertEquals("Turn right onto Seilergraben.", prompts.next(guiding(5, 30.0, TravelMode.Walk)))
    }

    @Test
    fun noWarningJustBeforeATurnsOwnSentence() {
        val prompts = VoicePrompts()
        prompts.next(guiding(3, 5.0))
        // 150 m to the bridge: inside the warning distance but too close to its own sentence at 120 m.
        assertNull(prompts.next(guiding(4, 150.0)))
        assertEquals("Turn left onto Rudolf-Brun-Brücke.", prompts.next(guiding(4, 115.0)))
    }

    @Test
    fun offTheRouteIsSaidOnceEachTimeTheUserLeavesIt() {
        val prompts = VoicePrompts()
        prompts.next(guiding(4, 400.0))
        assertEquals("You're off the route.", prompts.next(guiding(4, 400.0, offRoute = true)))
        assertNull(prompts.next(guiding(4, 400.0, offRoute = true)))
        // Back on it: the bridge's warning was said, nothing again.
        assertNull(prompts.next(guiding(4, 380.0)))
        assertEquals("You're off the route.", prompts.next(guiding(4, 380.0, offRoute = true)))
    }

    @Test
    fun arrivingSaysTheDestinationOnceWhenItWasNotSaidBefore() {
        val prompts = VoicePrompts()
        assertEquals("Your destination is on the right.", prompts.next(guiding(8, 20.0, TravelMode.Walk, arrived = true)))
        assertNull(prompts.next(guiding(8, 10.0, TravelMode.Walk, arrived = true)))
    }

    @Test
    fun aNewRouteStartsOverAndEndingForgetsTheRoute() {
        val prompts = VoicePrompts()
        val first = "Turn right onto Museumstrasse. Then Bear right onto Bahnhofquai."
        assertEquals(first, prompts.next(guiding(1, 35.0)))
        // Found again from where the user is: another route, even with the same steps.
        assertEquals(first, prompts.next(guiding(1, 35.0, on = route.copy())))
        assertNull(prompts.next(guiding(1, 35.0, on = route.copy()).copy(guiding = false)))
    }

    @Test
    fun nothingIsSaidWithoutAFoundRouteOrAPlaceOnIt() {
        val prompts = VoicePrompts()
        assertNull(prompts.next(guiding(1, 35.0).copy(status = RouteStatus.Finding)))
        assertNull(prompts.next(guiding(1, 35.0).copy(progress = null)))
        // Not used up by those.
        assertEquals("Turn right onto Museumstrasse. Then Bear right onto Bahnhofquai.", prompts.next(guiding(1, 35.0)))
    }

    @Test
    fun aStepWithoutSpokenSentencesIsSaidWithItsWrittenOne() {
        val a = GeoPoint(47.0, 8.0)
        val plain =
            Route(
                listOf(a, GeoPoint(47.01, 8.0)),
                seconds = 600.0,
                meters = 1100.0,
                steps =
                    listOf(
                        RouteStep("Walk north.", Turn.Depart, 800.0, a, 0, seconds = 600.0),
                        RouteStep("Turn left.", Turn.Left, 300.0, a, 1),
                        RouteStep("Your destination is on the left.", Turn.Arrive, 0.0, a, 1),
                    ),
            )
        val prompts = VoicePrompts()
        assertEquals("Walk north.", prompts.next(guiding(1, 800.0, TravelMode.Walk, on = plain)))
        assertEquals("In 100 meters, turn left.", prompts.next(guiding(1, 90.0, TravelMode.Walk, on = plain)))
        assertEquals("Turn left.", prompts.next(guiding(1, 20.0, TravelMode.Walk, on = plain)))
        assertEquals("Your destination is on the left.", prompts.next(guiding(2, 10.0, TravelMode.Walk, on = plain, arrived = true)))
    }

    @Test
    fun fastRoadsAreWarnedEarlier() {
        val a = GeoPoint(47.0, 8.0)
        // A motorway at 120 km/h: 30 s and 7 s ahead.
        val motorway = RouteStep("Continue on A1.", Turn.Straight, 10_000.0, a, seconds = 300.0)
        val (warn, say) = promptDistances(TravelMode.Car, motorway)
        assertEquals(1_000.0, warn, 0.5)
        assertEquals(233.3, say, 0.5)
        assertEquals(2_000.0, promptDistances(TravelMode.Car, motorway.copy(seconds = 100.0)).first)
        assertEquals(500.0 to 120.0, promptDistances(TravelMode.Car, null))
        assertEquals(250.0 to 50.0, promptDistances(TravelMode.Bike, null))
        assertEquals(100.0 to 25.0, promptDistances(TravelMode.Walk, motorway.copy(seconds = 0.0)))
    }

    @Test
    fun distancesAreSaidInWords() {
        assertEquals("50 meters", spokenDistance(20.0))
        assertEquals("450 meters", spokenDistance(430.0))
        assertEquals("1 kilometer", spokenDistance(980.0))
        assertEquals("1.2 kilometers", spokenDistance(1_240.0))
        assertEquals("2 kilometers", spokenDistance(2_010.0))
        assertEquals("12 kilometers", spokenDistance(12_400.0))
    }
}
