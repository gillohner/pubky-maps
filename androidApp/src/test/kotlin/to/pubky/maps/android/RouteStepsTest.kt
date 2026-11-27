package to.pubky.maps.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.routing.Directions
import to.pubky.maps.routing.DirectionsCard
import to.pubky.maps.routing.DirectionsUi
import to.pubky.maps.routing.Route
import to.pubky.maps.routing.RouteStatus
import to.pubky.maps.routing.RouteStep
import to.pubky.maps.routing.TravelMode
import to.pubky.maps.routing.Turn
import to.pubky.maps.ui.PubkyMapsTheme

/** A found route's steps open and close behind the Steps button; each reads as one sentence with its distance. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp")
class RouteStepsTest {
    @get:Rule
    val compose = createComposeRule()

    private val here = GeoPoint(47.3779, 8.5403)
    private val kunsthaus = GeoPoint(47.3702, 8.5486)

    private fun found(steps: List<RouteStep>) = RouteStatus.Found(Route(listOf(here, kunsthaus), 289.0, 1401.0, steps), "s")

    private val steps =
        listOf(
            RouteStep("Drive north.", Turn.Depart, 35.0),
            RouteStep("Turn right onto Museumstrasse.", Turn.Right, 52.0),
            RouteStep("Your destination is on the right.", Turn.Arrive, 0.0),
        )

    private fun card(
        status: RouteStatus,
        onSteps: (Boolean) -> Unit,
        shown: () -> Boolean,
    ) {
        compose.setContent {
            PubkyMapsTheme(darkTheme = false) {
                DirectionsCard(
                    DirectionsUi(
                        Directions("osm:node/1", kunsthaus, TravelMode.Car, here, status, stepsShown = shown()),
                        onMode = {},
                        onSteps = onSteps,
                        onClose = {},
                        onRetry = {},
                        onOpenSettings = null,
                        credits = emptyList(),
                    ),
                )
            }
        }
    }

    @Test
    fun stepsOpenAndCloseBehindTheButton() {
        var shown by mutableStateOf(false)
        val asked = ArrayList<Boolean>()
        card(found(steps), {
            asked += it
            shown = it
        }, { shown })
        compose.onNodeWithText("Turn right onto Museumstrasse.").assertDoesNotExist()
        compose.onNodeWithText("Steps").assertHasClickAction().performClick()
        compose.waitForIdle()
        // One node per step: its sentence and how far the route then goes; the end has no distance.
        compose.onNode(hasText("Turn right onto Museumstrasse.") and hasText("50 m")).assertExists()
        compose.onNodeWithText("Your destination is on the right.").assertExists()
        compose.onNodeWithText("0 m").assertDoesNotExist()
        compose.onNodeWithText("Hide steps").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Drive north.").assertDoesNotExist()
        assertEquals(listOf(true, false), asked)
    }

    @Test
    fun aRouteWithoutStepsHasNoStepsButton() {
        card(found(emptyList()), {}, { false })
        compose.onNodeWithText("5 min · 1.4 km").assertExists()
        compose.onNodeWithText("Steps").assertDoesNotExist()
    }

    @Test
    fun noStepsWhileTheRouteIsBeingFound() {
        card(RouteStatus.Finding, {}, { true })
        compose.onNodeWithText("Steps").assertDoesNotExist()
        compose.onNodeWithText("Hide steps").assertDoesNotExist()
        compose.onNodeWithText("Drive north.").assertDoesNotExist()
    }
}
