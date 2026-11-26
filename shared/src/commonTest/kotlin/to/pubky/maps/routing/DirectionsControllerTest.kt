package to.pubky.maps.routing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.status.ServiceHealth
import to.pubky.maps.status.ServicePart
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
