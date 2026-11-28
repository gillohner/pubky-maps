package to.pubky.maps.routing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.status.ServiceHealth
import to.pubky.maps.status.ServicePart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DirectionsControllerTest {
    private val here = GeoPoint(47.3779, 8.5403)
    private val kunsthaus = GeoPoint(47.3702, 8.5486)
    private val route = Route(listOf(here, kunsthaus), seconds = 290.0, meters = 1401.0)

    private class FakeRouting(
        var answer: (TravelMode) -> RouteAnswer,
    ) : Routing {
        val asked = ArrayList<Triple<GeoPoint, GeoPoint, TravelMode>>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun route(
            from: GeoPoint,
            to: GeoPoint,
            mode: TravelMode,
        ): RouteAnswer {
            asked += Triple(from, to, mode)
            gate?.await()
            return answer(mode)
        }
    }

    private fun TestScope.controller(
        routing: Routing,
        health: ServiceHealth? = null,
    ) = DirectionsController(routing, this, health, timeSource = testScheduler.timeSource)

    @Test
    fun aKnownPositionAsksForTheRouteByCarAtOnce() =
        runTest {
            val routing = FakeRouting { RouteAnswer.Found(route, "https://v.test") }
            val c = controller(routing)
            c.start("osm:node/1", kunsthaus, here)
            assertEquals(RouteStatus.Finding, c.directions.value?.status)
            advanceUntilIdle()
            assertEquals(RouteStatus.Found(route, "https://v.test"), c.directions.value?.status)
            assertEquals(listOf(Triple(here, kunsthaus, TravelMode.Car)), routing.asked)
        }

    @Test
    fun withoutAPositionTheDirectionsWaitForIt() =
        runTest {
            val routing = FakeRouting { RouteAnswer.Found(route, "https://v.test") }
            val c = controller(routing)
            c.start("osm:node/1", kunsthaus, null)
            assertEquals(RouteStatus.Locating, c.directions.value?.status)
            c.located(here)
            advanceUntilIdle()
            assertTrue(c.directions.value?.status is RouteStatus.Found)
            // A later fix does not ask again: directions start where the user was.
            c.located(GeoPoint(47.0, 8.0))
            advanceUntilIdle()
            assertEquals(1, routing.asked.size)
        }

    @Test
    fun aMissingPositionSaysSoAndOnlyWhileWaiting() =
        runTest {
            val c = controller(FakeRouting { RouteAnswer.Found(route, "s") })
            c.start("osm:node/1", kunsthaus, null)
            c.locationMissing(permissionOff = true)
            assertEquals(RouteStatus.NoLocation(permissionOff = true), c.directions.value?.status)
            c.start("osm:node/1", kunsthaus, here)
            c.locationMissing(permissionOff = false)
            assertEquals(RouteStatus.Finding, c.directions.value?.status)
        }

    @Test
    fun aPositionThatDoesNotComeIsMissingAndOneThatComesLaterStillStartsTheRoute() =
        runTest {
            val routing = FakeRouting { RouteAnswer.Found(route, "s") }
            val c = controller(routing)
            // A permission dialog dismissed without an answer, or no fix indoors.
            c.start("osm:node/1", kunsthaus, null)
            advanceTimeBy(29_000)
            assertEquals(RouteStatus.Locating, c.directions.value?.status)
            advanceTimeBy(2_000)
            assertEquals(RouteStatus.NoLocation(permissionOff = false), c.directions.value?.status)
            // Location access turned on in Settings: its first fix starts the route.
            c.located(here)
            advanceUntilIdle()
            assertTrue(c.directions.value?.status is RouteStatus.Found)
            assertEquals(1, routing.asked.size)
        }

    @Test
    fun aPositionInTimeEndsTheWaitAndAClosedWaitTimesNothingOut() =
        runTest {
            val c = controller(FakeRouting { RouteAnswer.Found(route, "s") })
            c.start("osm:node/1", kunsthaus, null)
            c.selectMode(TravelMode.Bike)
            c.located(here)
            advanceUntilIdle()
            assertTrue(c.directions.value?.status is RouteStatus.Found)
            c.close()
            c.start("osm:node/2", kunsthaus, null)
            c.close()
            advanceUntilIdle()
            assertNull(c.directions.value)
        }

    @Test
    fun anotherModeAsksAgainAndIsKeptForTheNextPlace() =
        runTest {
            val routing = FakeRouting { RouteAnswer.Found(route, "s") }
            val c = controller(routing)
            c.start("osm:node/1", kunsthaus, here)
            advanceUntilIdle()
            c.selectMode(TravelMode.Walk)
            advanceUntilIdle()
            assertEquals(TravelMode.Walk, c.directions.value?.mode)
            c.close()
            c.start("osm:node/2", kunsthaus, here)
            advanceUntilIdle()
            assertEquals(listOf(TravelMode.Car, TravelMode.Walk, TravelMode.Walk), routing.asked.map { it.third })
        }

    private fun DirectionsController.toFit() = directions.value?.routeToFit?.route

    @Test
    fun theMapFitsEachFoundRouteOnceAndANewRequestAgain() =
        runTest {
            val routing = FakeRouting { mode -> RouteAnswer.Found(route.copy(seconds = mode.ordinal.toDouble()), "s") }
            val c = controller(routing)
            c.start("osm:node/1", kunsthaus, here)
            assertNull(c.directions.value?.routeToFit, "nothing found yet")
            advanceUntilIdle()
            val car = assertNotNull(c.directions.value?.routeToFit).route
            c.routeFitted(car)
            // Back from Settings the same route is not fitted again over the user's pan.
            assertNull(c.directions.value?.routeToFit)
            assertEquals(RouteStatus.Found(car, "s"), c.directions.value?.status)

            // Another mode's route is fitted once more; a fit reported late for the car's route does not count for it.
            c.selectMode(TravelMode.Walk)
            assertNull(c.directions.value?.routeToFit, "the walk is still being asked")
            advanceUntilIdle()
            c.routeFitted(car)
            val walk = assertNotNull(c.directions.value?.routeToFit).route
            assertEquals(TravelMode.Walk.ordinal.toDouble(), walk.seconds)
            c.routeFitted(walk)
            assertNull(c.directions.value?.routeToFit)

            // Asking again (Retry) and the next place's directions are new routes too.
            c.retry()
            advanceUntilIdle()
            assertEquals(walk, c.toFit())
            c.close()
            c.start("osm:node/2", kunsthaus, here)
            advanceUntilIdle()
            assertEquals(walk, c.toFit())
        }

    @Test
    fun aNewerRequestReplacesOneStillRunning() =
        runTest {
            val routing = FakeRouting { mode -> RouteAnswer.Found(route.copy(seconds = mode.ordinal.toDouble()), "s") }
            routing.gate = CompletableDeferred()
            val c = DirectionsController(routing, TestScope(StandardTestDispatcher(testScheduler)))
            c.start("osm:node/1", kunsthaus, here)
            runCurrent()
            c.selectMode(TravelMode.Bike)
            runCurrent()
            routing.gate!!.complete(Unit)
            advanceUntilIdle()
            val directions = c.directions.value!!
            assertEquals(TravelMode.Bike, directions.mode)
            assertEquals(TravelMode.Bike.ordinal.toDouble(), (directions.status as RouteStatus.Found).route.seconds)
        }

    @Test
    fun closingEndsTheRequest() =
        runTest {
            val routing = FakeRouting { RouteAnswer.Found(route, "s") }
            routing.gate = CompletableDeferred()
            val c = controller(routing)
            c.start("osm:node/1", kunsthaus, here)
            runCurrent()
            c.close()
            routing.gate!!.complete(Unit)
            advanceUntilIdle()
            assertNull(c.directions.value)
        }

    @Test
    fun noServerAnsweringNamesRoutingUntilOneAnswersAndRetryAsksAgain() =
        runTest {
            val health = ServiceHealth()
            val routing = FakeRouting { RouteAnswer.Unreachable }
            val c = controller(routing, health)
            c.start("osm:node/1", kunsthaus, here)
            advanceUntilIdle()
            assertEquals(RouteStatus.Unreachable, c.directions.value?.status)
            assertEquals(setOf(ServicePart.Routing), health.missing.value)
            routing.answer = { RouteAnswer.NoRoute(NoRouteReason.NotConnected) }
            c.retry()
            advanceUntilIdle()
            assertEquals(RouteStatus.NoRoute(NoRouteReason.NotConnected), c.directions.value?.status)
            assertEquals(emptySet(), health.missing.value)
        }

    @Test
    fun openedStepsStayOpenForOtherModesAndCloseForTheNextPlace() =
        runTest {
            val routing = FakeRouting { RouteAnswer.Found(route, "s") }
            val c = controller(routing)
            c.start("osm:node/1", kunsthaus, here)
            advanceUntilIdle()
            assertEquals(false, c.directions.value?.stepsShown)
            c.showSteps(true)
            assertEquals(true, c.directions.value?.stepsShown)
            c.selectMode(TravelMode.Walk)
            advanceUntilIdle()
            assertEquals(true, c.directions.value?.stepsShown)
            assertTrue(c.directions.value?.status is RouteStatus.Found)
            c.start("osm:node/2", kunsthaus, here)
            advanceUntilIdle()
            assertEquals(false, c.directions.value?.stepsShown)
            assertEquals(3, routing.asked.size)
        }

    @Test
    fun stepsOpenedWhileTheRouteIsFoundAreKeptWithIt() =
        runTest {
            val routing = FakeRouting { RouteAnswer.Found(route, "s") }
            routing.gate = CompletableDeferred()
            val c = controller(routing)
            c.start("osm:node/1", kunsthaus, here)
            runCurrent()
            c.showSteps(true)
            routing.gate!!.complete(Unit)
            advanceUntilIdle()
            val directions = c.directions.value!!
            assertEquals(RouteStatus.Found(route, "s"), directions.status)
            assertTrue(directions.stepsShown)
        }

    @Test
    fun thePageListsTheFoundRoutesStepsOnlyWhileTheyAreOpen() =
        runTest {
            val steps = listOf(RouteStep("Drive north.", Turn.Depart, 35.0, here), RouteStep("You have arrived.", Turn.Arrive, 0.0, here))
            val routing = FakeRouting { RouteAnswer.Found(route.copy(steps = steps), "s") }
            routing.gate = CompletableDeferred()
            val c = controller(routing)
            c.start("osm:node/1", kunsthaus, here)
            runCurrent()
            c.showSteps(true)
            // Open, but no route yet.
            assertEquals(emptyList(), c.directions.value?.shownSteps)
            routing.gate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(steps, c.directions.value?.shownSteps)
            c.showSteps(false)
            assertEquals(emptyList(), c.directions.value?.shownSteps)
        }

    @Test
    fun stepsWithoutDirectionsDoNothing() =
        runTest {
            val c = controller(FakeRouting { RouteAnswer.Found(route, "s") })
            c.showSteps(true)
            assertNull(c.directions.value)
        }

    private val steps =
        listOf(
            RouteStep("Drive north.", Turn.Depart, 35.0, here),
            RouteStep("Turn right onto Museumstrasse.", Turn.Right, 52.0, GeoPoint(47.3785, 8.5410)),
            RouteStep("You have arrived.", Turn.Arrive, 0.0, kunsthaus),
        )

    /** Directions with the steps of a route found by car open. */
    private fun TestScope.stepsOpen(routing: FakeRouting = FakeRouting { RouteAnswer.Found(route.copy(steps = steps), "s") }) =
        controller(routing).also {
            it.start("osm:node/1", kunsthaus, here)
            advanceUntilIdle()
            it.showSteps(true)
        }

    @Test
    fun aTappedStepIsMarkedAndTheMapMovesToItOncePerTap() =
        runTest {
            val c = stepsOpen()
            assertNull(c.directions.value?.markedStep)
            assertNull(c.directions.value?.stepTapToMoveTo)
            c.showStep(1)
            val tapped = c.directions.value!!
            assertEquals(steps[1], tapped.markedStep)
            val tap = assertNotNull(tapped.stepTapToMoveTo)
            c.mapMovedToStep(tap)
            // Back from Settings the map stays where the user moved it since.
            assertEquals(steps[1], c.directions.value?.markedStep)
            assertNull(c.directions.value?.stepTapToMoveTo)
            // The same step tapped again moves the map again.
            c.showStep(1)
            val again = assertNotNull(c.directions.value?.stepTapToMoveTo)
            assertTrue(again > tap)
            // A move for an older tap does not count for this one.
            c.mapMovedToStep(tap)
            assertEquals(again, c.directions.value?.stepTapToMoveTo)
        }

    @Test
    fun closingTheStepsTakesTheTappedStepOffTheMap() =
        runTest {
            val c = stepsOpen()
            c.showStep(2)
            c.showSteps(false)
            assertNull(c.directions.value?.markedStep)
            assertNull(c.directions.value?.stepTapToMoveTo)
            // Opened again, no step is marked until one is tapped.
            c.showSteps(true)
            assertNull(c.directions.value?.markedStep)
            assertNull(c.directions.value?.stepTapToMoveTo)
        }

    @Test
    fun anotherRouteHasNoTappedStep() =
        runTest {
            val c = stepsOpen()
            c.showStep(1)
            c.selectMode(TravelMode.Walk)
            assertNull(c.directions.value?.markedStep)
            advanceUntilIdle()
            val walk = c.directions.value!!
            assertTrue(walk.stepsShown)
            assertEquals(steps, walk.shownSteps)
            assertNull(walk.markedStep)
            assertNull(walk.stepTapToMoveTo)
        }

    @Test
    fun aStepTapOutsideTheOpenListDoesNothing() =
        runTest {
            val c = stepsOpen()
            c.showStep(3)
            c.showStep(-1)
            assertNull(c.directions.value?.markedStep)
            c.showSteps(false)
            c.showStep(0)
            assertNull(c.directions.value?.markedStep)
            assertNull(c.directions.value?.stepTapToMoveTo)
        }

    @Test
    fun aStepTappedBeforeTheRouteIsFittedKeepsTheMapAtTheStep() =
        runTest {
            val c = stepsOpen()
            // Found with the sheet at its top: the route waits for room to be fitted.
            assertNotNull(c.directions.value?.routeToFit)
            c.showStep(1)
            assertNull(c.directions.value?.routeToFit)
            assertNotNull(c.directions.value?.stepTapToMoveTo)
        }

    @Test
    fun aSwapAsksForTheRouteFromThePlaceBackToTheUserAndASecondOneTurnsItAroundAgain() =
        runTest {
            val routing = FakeRouting { RouteAnswer.Found(route, "s") }
            val c = controller(routing)
            c.start("osm:node/1", kunsthaus, here)
            advanceUntilIdle()
            c.routeFitted(route)
            c.swap()
            assertEquals(RouteStatus.Finding, c.directions.value?.status)
            advanceUntilIdle()
            val back = c.directions.value!!
            assertTrue(back.reversed)
            assertTrue(back.status is RouteStatus.Found)
            // The route back is fitted as a new route.
            assertNotNull(back.routeToFit)
            c.swap()
            advanceUntilIdle()
            assertEquals(false, c.directions.value?.reversed)
            assertEquals(
                listOf(
                    Triple(here, kunsthaus, TravelMode.Car),
                    Triple(kunsthaus, here, TravelMode.Car),
                    Triple(here, kunsthaus, TravelMode.Car),
                ),
                routing.asked,
            )
        }

    @Test
    fun aSwapWhileThePositionIsAwaitedIsKeptForItsRoute() =
        runTest {
            val routing = FakeRouting { RouteAnswer.Found(route, "s") }
            val c = controller(routing)
            c.start("osm:node/1", kunsthaus, null)
            c.swap()
            assertEquals(RouteStatus.Locating, c.directions.value?.status)
            assertTrue(routing.asked.isEmpty())
            c.located(here)
            advanceUntilIdle()
            assertEquals(listOf(Triple(kunsthaus, here, TravelMode.Car)), routing.asked)
            assertTrue(c.directions.value!!.reversed)
        }

    @Test
    fun aSwapReplacesTheRequestStillRunning() =
        runTest {
            val routing = FakeRouting { RouteAnswer.Found(route, "s") }
            routing.gate = CompletableDeferred()
            val c = DirectionsController(routing, TestScope(StandardTestDispatcher(testScheduler)))
            c.start("osm:node/1", kunsthaus, here)
            runCurrent()
            c.swap()
            runCurrent()
            routing.gate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf(Triple(here, kunsthaus, TravelMode.Car), Triple(kunsthaus, here, TravelMode.Car)), routing.asked)
            val directions = c.directions.value!!
            assertTrue(directions.reversed)
            assertTrue(directions.status is RouteStatus.Found)
        }

    @Test
    fun theSwappedRouteKeepsItsOpenStepsAndModeButNotTheTappedStep() =
        runTest {
            val routing = FakeRouting { RouteAnswer.Found(route.copy(steps = steps), "s") }
            val c = stepsOpen(routing)
            c.selectMode(TravelMode.Walk)
            advanceUntilIdle()
            c.showStep(1)
            c.swap()
            assertNull(c.directions.value?.markedStep)
            advanceUntilIdle()
            val back = c.directions.value!!
            assertEquals(TravelMode.Walk, back.mode)
            assertTrue(back.stepsShown)
            assertEquals(steps, back.shownSteps)
            assertEquals(Triple(kunsthaus, here, TravelMode.Walk), routing.asked.last())
        }

    @Test
    fun tryAgainKeepsTheWayRoundAndDirectionsOrTheNextPlaceStartFromTheUser() =
        runTest {
            val routing = FakeRouting { RouteAnswer.Found(route, "s") }
            val c = controller(routing)
            c.start("osm:node/1", kunsthaus, null)
            c.locationMissing(permissionOff = false)
            c.swap()
            // Try again for the missing position starts the same directions anew, the same way round.
            c.start("osm:node/1", kunsthaus, here, reversed = c.directions.value!!.reversed)
            advanceUntilIdle()
            assertTrue(c.directions.value!!.reversed)
            assertEquals(Triple(kunsthaus, here, TravelMode.Car), routing.asked.last())
            // The page's Directions again: to the place.
            c.start("osm:node/1", kunsthaus, here)
            advanceUntilIdle()
            assertEquals(false, c.directions.value?.reversed)
            assertEquals(Triple(here, kunsthaus, TravelMode.Car), routing.asked.last())
            c.swap()
            c.close()
            c.start("osm:node/2", kunsthaus, here)
            advanceUntilIdle()
            assertEquals(false, c.directions.value?.reversed)
            assertEquals(Triple(here, kunsthaus, TravelMode.Car), routing.asked.last())
            // Without directions a swap does nothing.
            c.close()
            c.swap()
            assertNull(c.directions.value)
        }

    @Test
    fun eachStartIsAnotherOpeningAndItsOtherModesAndWayRoundAreTheSame() =
        runTest {
            val c = controller(FakeRouting { RouteAnswer.Found(route, "s") })
            c.start("osm:node/1", kunsthaus, null)
            val first = c.directions.value!!.opening
            c.swap()
            c.selectMode(TravelMode.Bike)
            c.located(here)
            advanceUntilIdle()
            assertEquals(first, c.directions.value!!.opening)
            c.close()
            c.start("osm:node/1", kunsthaus, null)
            assertTrue(c.directions.value!!.opening != first)
        }

    private val hotel = RouteStart("osm:node/9", GeoPoint(47.3667, 8.5450), "Hotel Adler")

    @Test
    fun aChosenStartServesTheNextPlacesAtOnceWithoutAPosition() =
        runTest {
            val routing = FakeRouting { RouteAnswer.Found(route, "s") }
            val c = controller(routing)
            c.chooseStart(hotel)
            assertEquals(hotel, c.chosenStart.value)
            c.start("osm:node/1", kunsthaus, null)
            // No waiting for the user's position: the route starts at the hotel.
            assertEquals(RouteStatus.Finding, c.directions.value?.status)
            assertEquals(hotel, c.directions.value?.from)
            advanceUntilIdle()
            assertEquals(listOf(Triple(hotel.point, kunsthaus, TravelMode.Car)), routing.asked)
            // A fix that comes now does not ask again, nor does the position timeout end anything.
            c.located(here)
            advanceTimeBy(60_000)
            advanceUntilIdle()
            assertEquals(1, routing.asked.size)
            assertTrue(c.directions.value?.status is RouteStatus.Found)
            // Another place, a known position: still from the hotel.
            c.close()
            c.start("osm:node/2", kunsthaus, here)
            advanceUntilIdle()
            assertEquals(Triple(hotel.point, kunsthaus, TravelMode.Car), routing.asked.last())
        }

    @Test
    fun theChosenStartsOwnDirectionsStartFromTheUser() =
        runTest {
            val routing = FakeRouting { RouteAnswer.Found(route, "s") }
            val c = controller(routing)
            c.chooseStart(hotel)
            assertNull(c.startFor(hotel.placeUri, hotel.point))
            assertEquals(hotel, c.startFor("osm:node/1", kunsthaus))
            // The hotel through another link (its card, an OSM URL): the same point, the same place.
            assertNull(c.startFor("https://www.openstreetmap.org/node/9", GeoPoint(47.36672, 8.54503)))
            c.start(hotel.placeUri, hotel.point, null)
            assertEquals(RouteStatus.Locating, c.directions.value?.status)
            assertNull(c.directions.value?.from)
            c.located(here)
            advanceUntilIdle()
            assertEquals(listOf(Triple(here, hotel.point, TravelMode.Car)), routing.asked)
        }

    @Test
    fun aChosenStartIsSwappedRetriedAndAskedByEachMode() =
        runTest {
            var answer: RouteAnswer = RouteAnswer.Unreachable
            val routing = FakeRouting { answer }
            val c = controller(routing)
            c.chooseStart(hotel)
            c.start("osm:node/1", kunsthaus, null)
            advanceUntilIdle()
            assertEquals(RouteStatus.Unreachable, c.directions.value?.status)
            answer = RouteAnswer.Found(route, "s")
            c.retry()
            advanceUntilIdle()
            assertTrue(c.directions.value?.status is RouteStatus.Found)
            c.swap()
            advanceUntilIdle()
            assertEquals(Triple(kunsthaus, hotel.point, TravelMode.Car), routing.asked.last())
            c.selectMode(TravelMode.Walk)
            advanceUntilIdle()
            assertEquals(Triple(kunsthaus, hotel.point, TravelMode.Walk), routing.asked.last())
            assertEquals(4, routing.asked.size)
        }

    @Test
    fun aClearedStartLeavesTheNextDirectionsToTheUsersPosition() =
        runTest {
            val routing = FakeRouting { RouteAnswer.Found(route, "s") }
            val c = controller(routing)
            c.chooseStart(hotel)
            c.start("osm:node/1", kunsthaus, null)
            advanceUntilIdle()
            c.clearStart()
            assertNull(c.chosenStart.value)
            // The open directions stay as they are until the caller starts them again.
            assertEquals(hotel, c.directions.value?.from)
            c.start("osm:node/1", kunsthaus, null)
            assertEquals(RouteStatus.Locating, c.directions.value?.status)
            assertNull(c.directions.value?.from)
            c.located(here)
            advanceUntilIdle()
            assertEquals(Triple(here, kunsthaus, TravelMode.Car), routing.asked.last())
        }

    @Test
    fun fromYourLocationClearsTheStartTheDirectionsCameFromButNotOneChosenSince() =
        runTest {
            val c = controller(FakeRouting { RouteAnswer.Found(route, "s") })
            c.chooseStart(hotel)
            c.start("osm:node/1", kunsthaus, null)
            c.leaveStart()
            assertNull(c.chosenStart.value)
            // From the hotel to the Kunsthaus, then Start here on the Kunsthaus: the card still says the
            // hotel; leaving it keeps the Kunsthaus as the start.
            c.chooseStart(hotel)
            c.start("osm:node/1", kunsthaus, null)
            val museum = RouteStart("osm:node/1", kunsthaus, "Kunsthaus")
            c.chooseStart(museum)
            c.leaveStart()
            assertEquals(museum, c.chosenStart.value)
            // Without directions from a start nothing is cleared.
            c.close()
            c.leaveStart()
            assertEquals(museum, c.chosenStart.value)
        }

    @Test
    fun aStartChosenBeforeItsCardCameTakesTheCardsName() =
        runTest {
            val c = controller(FakeRouting { RouteAnswer.Found(route, "s") })
            c.chooseStart(hotel.copy(name = "47.3667, 8.545"))
            c.renameStart("osm:node/2", "Kunsthaus")
            assertEquals("47.3667, 8.545", c.chosenStart.value?.name)
            c.renameStart(hotel.placeUri, "Hotel Adler")
            assertEquals(hotel, c.chosenStart.value)
        }

    @Test
    fun onlyARouteFromTheUsersPositionToThePlaceCanGuideThem() =
        runTest {
            val c = controller(FakeRouting { RouteAnswer.Found(route, "s") })
            c.start("osm:node/1", kunsthaus, here)
            assertEquals(false, c.directions.value?.canGuide)
            advanceUntilIdle()
            assertEquals(true, c.directions.value?.canGuide)
            c.swap()
            advanceUntilIdle()
            // Back from the place to the user: nothing to guide them along.
            assertEquals(false, c.directions.value?.canGuide)
            c.startGuidance(here)
            assertEquals(false, c.directions.value?.guiding)
            c.swap()
            c.chooseStart(hotel)
            c.start("osm:node/1", kunsthaus, here)
            advanceUntilIdle()
            assertEquals(false, c.directions.value?.canGuide)
        }

    @Test
    fun startedGuidanceTracksTheUserAlongTheRouteAndClosesTheSteps() =
        runTest {
            val c = controller(FakeRouting { RouteAnswer.Found(route, "s") })
            c.start("osm:node/1", kunsthaus, here)
            advanceUntilIdle()
            c.showSteps(true)
            c.showStep(0)
            c.startGuidance(here)
            val started = assertNotNull(c.directions.value)
            assertTrue(started.guiding)
            assertEquals(false, started.stepsShown)
            assertNull(started.markedStep)
            assertEquals(0, started.progress?.segment)
            // Half way: half the route left.
            val half = GeoPoint((here.lat + kunsthaus.lat) / 2, (here.lon + kunsthaus.lon) / 2)
            c.guide(half)
            val now = assertNotNull(c.directions.value?.progress)
            assertEquals(metersBetween(half, kunsthaus), now.metersLeft, 1.0)
            assertEquals(145.0, now.secondsLeft, 1.0)
            assertEquals(half, c.directions.value?.position)
            c.endGuidance()
            assertEquals(false, c.directions.value?.guiding)
            assertNull(c.directions.value?.progress)
            assertTrue(c.directions.value?.status is RouteStatus.Found)
        }

    @Test
    fun aNewRouteFromWhereTheUserIsKeepsGuidingThemAndIsNotFitted() =
        runTest {
            val routing = FakeRouting { RouteAnswer.Found(route, "s") }
            val c = controller(routing)
            c.start("osm:node/1", kunsthaus, here)
            advanceUntilIdle()
            c.startGuidance(here)
            val off = GeoPoint(47.3800, 8.5300)
            c.guide(off)
            assertEquals(
                true,
                c.directions.value
                    ?.progress
                    ?.offRoute,
            )
            val again = Route(listOf(off, kunsthaus), seconds = 400.0, meters = 1900.0)
            routing.answer = { RouteAnswer.Found(again, "s") }
            c.routeAgainFrom(off)
            // The route the user is on stays on the map, and where they are on it, until the new one comes.
            val asking = assertNotNull(c.directions.value)
            assertEquals(RouteStatus.Found(route, "s"), asking.status)
            assertTrue(asking.rerouting)
            assertEquals(true, asking.progress?.offRoute)
            advanceUntilIdle()
            assertEquals(Triple(off, kunsthaus, TravelMode.Car), routing.asked.last())
            val d = assertNotNull(c.directions.value)
            assertTrue(d.guiding)
            assertEquals(RouteStatus.Found(again, "s"), d.status)
            assertEquals(false, d.rerouting)
            assertEquals(false, d.progress?.offRoute)
            assertEquals(400.0, d.progress?.secondsLeft ?: 0.0, 1.0)
            // The map follows the user: the new route is not fitted over that.
            assertNull(d.routeToFit)
            // Not while not guiding.
            c.endGuidance()
            c.routeAgainFrom(here)
            assertEquals(2, routing.asked.size)
        }

    @Test
    fun aNewRouteStillAskedForWhenGuidanceEndsIsFittedOnceFound() =
        runTest {
            val routing = FakeRouting { RouteAnswer.Found(route, "s") }
            val c = controller(routing)
            c.start("osm:node/1", kunsthaus, here)
            advanceUntilIdle()
            c.startGuidance(here)
            val gate = CompletableDeferred<Unit>()
            routing.gate = gate
            c.routeAgainFrom(GeoPoint(47.3800, 8.5300))
            runCurrent()
            c.endGuidance()
            // The card waits for the new route, not one from where the user was.
            assertEquals(RouteStatus.Finding, c.directions.value?.status)
            assertEquals(false, c.directions.value?.rerouting)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(false, c.directions.value?.guiding)
            assertNotNull(c.directions.value?.routeToFit)
        }

    /** Guidance on [route] from [here], with one position off it already (no new route asked yet). */
    private fun TestScope.guidedOffOnce(routing: FakeRouting): DirectionsController {
        val c = controller(routing)
        c.start("osm:node/1", kunsthaus, here)
        advanceUntilIdle()
        c.startGuidance(here)
        c.guide(off)
        return c
    }

    private val off = GeoPoint(47.3800, 8.5300)

    /** [p] moved [meters] east. */
    private fun east(
        p: GeoPoint,
        meters: Double,
    ) = GeoPoint(p.lat, p.lon + meters / (111_320.0 * kotlin.math.cos(p.lat * kotlin.math.PI / 180)))

    @Test
    fun offTheRouteTwiceInARowAsksForANewRouteByItselfAndKeepsGuidingMeanwhile() =
        runTest {
            val routing = FakeRouting { RouteAnswer.Found(route, "s") }
            val c = guidedOffOnce(routing)
            // One position off the route may be a fix that jumped: nothing asked yet.
            assertEquals(1, routing.asked.size)
            assertEquals(false, c.directions.value?.rerouting)
            val again = Route(listOf(off, kunsthaus), seconds = 400.0, meters = 1900.0)
            routing.answer = { RouteAnswer.Found(again, "s") }
            val gate = CompletableDeferred<Unit>()
            routing.gate = gate
            val further = east(off, 5.0)
            c.guide(further)
            runCurrent()
            assertEquals(Triple(further, kunsthaus, TravelMode.Car), routing.asked.last())
            val asking = assertNotNull(c.directions.value)
            assertTrue(asking.rerouting)
            assertEquals(RouteStatus.Found(route, "s"), asking.status)
            // Positions meanwhile still go on the route the user is on, and ask nothing more.
            c.guide(off)
            assertEquals(2, routing.asked.size)
            assertEquals(off, c.directions.value?.position)
            assertEquals("Finding a new route…", guidanceText(assertNotNull(c.directions.value), "K").title)
            gate.complete(Unit)
            advanceUntilIdle()
            val d = assertNotNull(c.directions.value)
            assertEquals(RouteStatus.Found(again, "s"), d.status)
            assertEquals(false, d.rerouting)
            assertEquals(false, d.progress?.offRoute)
            assertTrue(d.guiding)
            assertNull(d.routeToFit)
        }

    @Test
    fun theFixGuidanceStartedFromSentAgainIsNotASecondPositionOffTheRoute() =
        runTest {
            val routing = FakeRouting { RouteAnswer.Found(route, "s") }
            val c = controller(routing)
            c.start("osm:node/1", kunsthaus, here)
            advanceUntilIdle()
            // Start off the route (the route begins on the road): the map's location sends the same fix again.
            val at = Instant.parse("2026-11-28T05:00:00Z")
            c.startGuidance(off, measuredAt = at)
            c.guide(off, measuredAt = at)
            advanceUntilIdle()
            assertEquals(1, routing.asked.size)
            // A new fix at the same point (the user stands there) is another position.
            c.guide(off, measuredAt = at + 1.seconds)
            advanceUntilIdle()
            assertEquals(2, routing.asked.size)
        }

    @Test
    fun oneFixOffTheRouteBetweenTwoOnItAsksForNothing() =
        runTest {
            val routing = FakeRouting { RouteAnswer.Found(route, "s") }
            val c = guidedOffOnce(routing)
            c.guide(here)
            c.guide(off)
            advanceUntilIdle()
            assertEquals(1, routing.asked.size)
            assertEquals(
                true,
                c.directions.value
                    ?.progress
                    ?.offRoute,
            )
        }

    @Test
    fun backOnTheRouteBeforeTheNewOneCameItIsNoLongerAskedFor() =
        runTest {
            val routing = FakeRouting { RouteAnswer.Found(route, "s") }
            val c = guidedOffOnce(routing)
            val gate = CompletableDeferred<Unit>()
            routing.gate = gate
            routing.answer = { RouteAnswer.Found(Route(listOf(off, kunsthaus), seconds = 400.0, meters = 1900.0), "s") }
            c.guide(off)
            runCurrent()
            assertEquals(true, c.directions.value?.rerouting)
            c.guide(here)
            gate.complete(Unit)
            advanceUntilIdle()
            val d = assertNotNull(c.directions.value)
            assertEquals(false, d.rerouting)
            assertEquals(RouteStatus.Found(route, "s"), d.status)
            assertEquals(false, d.progress?.offRoute)
        }

    @Test
    fun noNewRouteKeepsTheRouteGuidingAndSaysWhy() =
        runTest {
            val health = ServiceHealth()
            val routing = FakeRouting { RouteAnswer.Found(route, "s") }
            val c = controller(routing, health)
            c.start("osm:node/1", kunsthaus, here)
            advanceUntilIdle()
            c.startGuidance(here)
            routing.answer = { RouteAnswer.Unreachable }
            c.guide(off)
            c.guide(off)
            advanceUntilIdle()
            val d = assertNotNull(c.directions.value)
            assertTrue(d.guiding)
            assertEquals(RouteStatus.Found(route, "s"), d.status)
            assertEquals(false, d.rerouting)
            assertEquals(RouteStatus.Unreachable, d.noNewRoute)
            assertTrue(ServicePart.Routing in health.missing.value)
            assertEquals(
                GuidanceText(
                    "No new route",
                    "Can't reach the routing server. Check your connection.",
                    GuidanceIcon.Other,
                    routeAgain = true,
                ),
                guidanceText(d, "K"),
            )
            // Back on the route, it guides them as before.
            c.guide(here)
            assertNull(c.directions.value?.noNewRoute)
            assertEquals(
                false,
                c.directions.value
                    ?.progress
                    ?.offRoute,
            )
        }

    @Test
    fun aNewRouteIsAskedByItselfAgainOnlyAfterTheSpacingAndOnceTheUserMovedOn() =
        runTest {
            // The server's new route starts on a road out of the user's reach: still off it.
            val routing = FakeRouting { RouteAnswer.Found(route, "s") }
            val c = guidedOffOnce(routing)
            c.guide(off)
            advanceUntilIdle()
            assertEquals(2, routing.asked.size)
            assertEquals(
                true,
                c.directions.value
                    ?.progress
                    ?.offRoute,
            )
            // Standing there, long after: the same route would come again.
            advanceTimeBy(REROUTE_SPACING * 3)
            repeat(3) { c.guide(east(off, 10.0)) }
            advanceUntilIdle()
            assertEquals(2, routing.asked.size)
            // Find a new route asks at once.
            c.routeAgainFrom(off)
            advanceUntilIdle()
            assertEquals(3, routing.asked.size)
            // Moved on, but within the spacing of the last one; then after it.
            val moved = east(off, 80.0)
            c.guide(moved)
            c.guide(moved)
            advanceUntilIdle()
            assertEquals(3, routing.asked.size)
            advanceTimeBy(REROUTE_SPACING)
            c.guide(moved)
            advanceUntilIdle()
            assertEquals(4, routing.asked.size)
            assertEquals(Triple(moved, kunsthaus, TravelMode.Car), routing.asked.last())
        }
}
