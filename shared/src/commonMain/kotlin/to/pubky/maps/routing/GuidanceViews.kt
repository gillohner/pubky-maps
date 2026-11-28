package to.pubky.maps.routing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import to.pubky.maps.config.AttributionLink
import to.pubky.maps.search.formatDistance
import to.pubky.maps.ui.AppIcons
import to.pubky.maps.ui.MapSurfaces

/** A route guiding the user ([Directions.guiding]), and what they can do while it does. */
data class GuidanceUi(
    val directions: Directions,
    /** The place the route goes to, as its page calls it. */
    val placeName: String,
    /** When the user gets there at this pace ("14:32"); null while it is not known. */
    val arrival: String?,
    /** The map follows the user; false once they moved it themselves. */
    val following: Boolean,
    /** The map follows the user again. */
    val onRecenter: () -> Unit,
    /** Asks for a new route from the user's position: off the route, or after a new route was not found. */
    val onRouteAgain: () -> Unit,
    val onEnd: () -> Unit,
    /** The routing server's credits and the link to report a map error, as under the route on its card. */
    val credits: List<AttributionLink>,
)

/**
 * What the guidance banner says: [title] large, [detail] under it, [icon] beside them; [routeAgain]
 * when it offers a new route. TalkBack reads the [title] when it changes, or the [detail] when the
 * title is the distance to the next step ([titleIsDistance]), which changes with every few metres.
 */
data class GuidanceText(
    val title: String,
    val detail: String?,
    val icon: GuidanceIcon,
    val routeAgain: Boolean = false,
    val titleIsDistance: Boolean = false,
)

/** The banner's icon: the next step's turn, the place's pin on arrival, or the arrow for anything else. */
sealed interface GuidanceIcon {
    data class Step(
        val turn: Turn,
    ) : GuidanceIcon

    data object Arrived : GuidanceIcon

    data object Other : GuidanceIcon
}

/**
 * What the banner says while [directions] guide the user to [placeName]: the next step ("150 m",
 * "Turn right onto Museumstrasse."), "Now" for it under 10 m, that they arrived, that they are off
 * the route (with a new route to ask for), that a new route is asked for, or why none came.
 */
fun guidanceText(
    directions: Directions,
    placeName: String,
): GuidanceText {
    val found = directions.status as? RouteStatus.Found
    if (found == null) {
        if (directions.status == RouteStatus.Finding) return GuidanceText("Finding a new route…", null, GuidanceIcon.Other)
        return GuidanceText("No new route", routeStatusText(directions), GuidanceIcon.Other, routeAgain = true)
    }
    val progress = directions.progress ?: return GuidanceText("Finding your location…", null, GuidanceIcon.Other)
    return when {
        progress.offRoute -> {
            GuidanceText("You're off the route", "About ${formatDistance(progress.offBy)} from it", GuidanceIcon.Other, routeAgain = true)
        }

        progress.arrived -> {
            GuidanceText("You have arrived", placeName, GuidanceIcon.Arrived)
        }

        else -> {
            val step = progress.nextStep?.let { found.route.steps.getOrNull(it) }
            if (step == null) {
                GuidanceText(formatDistance(progress.metersLeft), "To $placeName", GuidanceIcon.Arrived, titleIsDistance = true)
            } else {
                val distance = if (progress.toNextStep < 10) "Now" else formatDistance(progress.toNextStep)
                GuidanceText(distance, step.instruction, GuidanceIcon.Step(step.turn), titleIsDistance = true)
            }
        }
    }
}

/** "12 min · 3.4 km": the time and the way left; null before the user's place on the route is known. */
fun guidanceLeftText(directions: Directions): String? {
    val progress = directions.progress ?: return null
    if (directions.status !is RouteStatus.Found) return null
    return "${durationText(progress.secondsLeft)} · ${formatDistance(progress.metersLeft)}"
}

/**
 * The banner on top of the map while a route guides the user, as Google Maps' and CoMaps' next-turn
 * banner: the turn's icon, how far it is, the server's sentence for it. What it says is read out when
 * the step or the state changes, not each time a distance does ([GuidanceText.titleIsDistance]).
 */
@Composable
fun GuidanceBanner(
    ui: GuidanceUi,
    modifier: Modifier = Modifier,
) {
    val text = guidanceText(ui.directions, ui.placeName)
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.primary,
        contentColor = colors.onPrimary,
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(text.icon.vector(ui.directions), contentDescription = null, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Medium,
                        modifier =
                            Modifier.semantics {
                                heading()
                                if (!text.titleIsDistance) liveRegion = LiveRegionMode.Polite
                            },
                    )
                    if (text.detail != null) {
                        Text(
                            text.detail,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { if (text.titleIsDistance) liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
            if (text.routeAgain) {
                Button(
                    onClick = ui.onRouteAgain,
                    modifier = Modifier.padding(top = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.onPrimary, contentColor = colors.primary),
                ) { Text("Find a new route") }
            }
        }
    }
}

/**
 * The bar under the map while a route guides the user: the time and the way left and when they get
 * there, End, the routing server's credits under them while its route is on the map, and Re-centre
 * once they moved the map away from where they are.
 */
@Composable
fun GuidanceBar(
    ui: GuidanceUi,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!ui.following) {
            Button(onClick = ui.onRecenter) {
                Icon(RoutingIcons.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Re-centre")
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = MapSurfaces.edge,
            shadowElevation = 3.dp,
        ) {
            Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val left = guidanceLeftText(ui.directions)
                        Text(
                            left ?: ui.directions.mode.phrase
                                .replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        val arrival = ui.arrival?.takeIf { left != null }
                        Text(
                            if (arrival != null) "Arrive at $arrival · ${ui.placeName}" else ui.placeName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = ui.onEnd) {
                        Icon(AppIcons.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("End")
                    }
                }
                if (ui.directions.status is RouteStatus.Found) RouteCredits(ui.credits)
            }
        }
    }
}

private fun GuidanceIcon.vector(directions: Directions): ImageVector =
    when (this) {
        is GuidanceIcon.Step -> directions.stepIcon(turn)
        GuidanceIcon.Arrived -> AppIcons.Place
        GuidanceIcon.Other -> RoutingIcons.Navigation
    }
