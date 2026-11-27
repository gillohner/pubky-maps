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
    ) = DirectionsController(routing, this, health)

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
}
