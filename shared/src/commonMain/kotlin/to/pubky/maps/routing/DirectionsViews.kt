package to.pubky.maps.routing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import to.pubky.maps.config.AttributionLink
import to.pubky.maps.search.formatDistance
import to.pubky.maps.ui.AppIcons
import to.pubky.maps.ui.rememberSafeLinkOpener
import kotlin.math.roundToInt

/** The directions shown on a place page, and what the user can do with them. */
data class DirectionsUi(
    val directions: Directions,
    val onMode: (TravelMode) -> Unit,
    /** Opens (true) or closes the list of the route's steps. */
    val onSteps: (Boolean) -> Unit,
    /** Shows the step at this index of the list on the map. */
    val onStep: (Int) -> Unit,
    val onClose: () -> Unit,
    /** Swaps the route's start and destination ([Directions.reversed]). */
    val onSwap: () -> Unit,
    /** Asks again: for the route after a failure, for the position when it was missing. */
    val onRetry: () -> Unit,
    /** Opens the app's system settings, where location access is turned on. */
    val onOpenSettings: (() -> Unit)?,
    /** Opens the phone's location setting, where location is turned on; null where it cannot be opened. */
    val onTurnOnLocation: (() -> Unit)? = null,
    /** The routing server's credits, shown with a found route. */
    val credits: List<AttributionLink>,
    /** Starts the directions from the user's position again, instead of the chosen start ([Directions.from]). */
    val onFromYourLocation: () -> Unit,
    /** The found route guides the user ([DirectionsController.startGuidance]); null: the card has no Start. */
    val onStart: (() -> Unit)? = null,
)

/** "18 min", "1 h 5 min", "2 h": a route's time, at least a minute. */
fun durationText(seconds: Double): String {
    val minutes = (seconds / 60).roundToInt().coerceAtLeast(1)
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "$minutes min"
        rest == 0 -> "$hours h"
        else -> "$hours h $rest min"
    }
}

/** "by car", "by bike", "on foot". */
val TravelMode.phrase: String
    get() =
        when (this) {
            TravelMode.Car -> "by car"
            TravelMode.Bike -> "by bike"
            TravelMode.Walk -> "on foot"
        }

private val TravelMode.label: String
    get() =
        when (this) {
            TravelMode.Car -> "Car"
            TravelMode.Bike -> "Bike"
            TravelMode.Walk -> "Walk"
        }

/** "Step 2 of 9 · 50 m": where the step at [index] of [count] is in the route, and how far the route then goes. */
fun stepPositionText(
    index: Int,
    count: Int,
    meters: Double,
): String {
    val position = "Step ${index + 1} of $count"
    // Below 5 m the distance would read "0 m", as in the list.
    return if (meters >= 5) "$position · ${formatDistance(meters)}" else position
}

/** "your location", or the name of the chosen start ([Directions.from]): the route's other end than the place. */
private val Directions.originName: String get() = from?.name ?: "your location"

/** "By car from your location", "On foot to Hotel Adler": which way a found route goes. */
fun routeWayText(directions: Directions): String {
    val way = if (directions.reversed) "to ${directions.originName}" else "from ${directions.originName}"
    return "${directions.mode.phrase.replaceFirstChar { it.uppercase() }} $way"
}

/** What the directions say while they have no route to show; null once a route is found. */
fun routeStatusText(directions: Directions): String? =
    when (val status = directions.status) {
        RouteStatus.Locating -> {
            "Finding your location…"
        }

        is RouteStatus.NoLocation -> {
            val needed = if (directions.reversed) "Directions end at your location." else "Directions start from your location."
            if (status.permissionOff) {
                "$needed Location access is off for Pubky Maps."
            } else {
                "$needed Is location turned on?"
            }
        }

        RouteStatus.Finding -> {
            "Finding a route ${directions.mode.phrase}…"
        }

        is RouteStatus.Found -> {
            null
        }

        is RouteStatus.NoRoute -> {
            when (status.reason) {
                NoRouteReason.TooFar -> {
                    "Too far for a route ${directions.mode.phrase}."
                }

                NoRouteReason.NoRoadNear -> {
                    "No road or path ${directions.mode.phrase} near ${directions.originName} or the place."
                }

                NoRouteReason.NotConnected -> {
                    if (directions.reversed) {
                        "No route ${directions.mode.phrase} from this place to ${directions.originName}."
                    } else {
                        "No route ${directions.mode.phrase} from ${directions.originName} to this place."
                    }
                }
            }
        }

        RouteStatus.Unreachable -> {
            "Can't reach the routing server. Check your connection."
        }
    }

/**
 * The directions on a place page: the modes, then the route's time and length (the map draws its line)
 * and which way it goes beside the button that swaps start and destination, then the button that opens
 * its steps ([RouteStepList]), or what stands in its way; the routing server's credits under a found
 * route. A step tapped in the list shows above the button with the
 * way to the previous and the next one ([StepPreview]). Directions from a chosen start
 * ([Directions.from]) have a button that starts them from the user's position again.
 */
@Composable
fun DirectionsCard(
    ui: DirectionsUi,
    modifier: Modifier = Modifier,
) {
    val directions = ui.directions
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 12.dp)) {
            // The modes wrap rather than scroll: at a large font a row that scrolls would cut the last
            // mode ("Wa"), one the user could not tell was there.
            Row(verticalAlignment = Alignment.Top) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (mode in TravelMode.entries) {
                        FilterChip(
                            selected = mode == directions.mode,
                            onClick = { ui.onMode(mode) },
                            label = { Text(mode.label) },
                            leadingIcon = { Icon(mode.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                }
                IconButton(onClick = ui.onClose) { Icon(AppIcons.Close, contentDescription = "Close directions") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val found = directions.status as? RouteStatus.Found
                    if (found != null) {
                        Text(
                            "${durationText(found.route.seconds)} · ${formatDistance(found.route.meters)}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(routeWayText(directions), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        routeStatusText(directions)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                // Beside the line that says which way the route goes ("By car from your location"), as
                // Google Maps' swap beside its start and destination fields: the way back from the place
                // (home from a restaurant, a one-way street the other way) without leaving the page. The
                // modes' row keeps its width for the modes at a large font.
                IconButton(
                    onClick = ui.onSwap,
                    modifier =
                        Modifier.semantics {
                            stateDescription =
                                (if (directions.reversed) "To " else "From ") + directions.originName
                        },
                ) {
                    Icon(RoutingIcons.SwapVert, contentDescription = "Swap start and destination")
                }
            }
            // Under the status, not beside it: the swap button centres on the status line alone. Moved
            // left by the button's own padding, as the Steps row below, so its label starts under the
            // status text.
            // The labels say what the tap is for, as the map's location snackbar's: "Allow location"
            // rather than "Settings", the page it opens. With the phone's location off, Turn on location
            // goes first (it is what brings the position back) and Try again stays beside it, for a
            // position that is merely slow; they wrap at a large font.
            val status = directions.status
            val settings = ui.onOpenSettings
            val turnOn = ui.onTurnOnLocation
            val underStatus = Modifier.offset(x = (-12).dp)
            when {
                status is RouteStatus.NoLocation && status.permissionOff && settings != null -> {
                    TextButton(onClick = settings, modifier = underStatus) { Text("Allow location") }
                }

                status is RouteStatus.NoLocation && status.locationOff && turnOn != null -> {
                    FlowRow(underStatus) {
                        TextButton(onClick = turnOn) { Text("Turn on location") }
                        TextButton(onClick = ui.onRetry) { Text("Try again") }
                    }
                }

                status is RouteStatus.NoLocation || status == RouteStatus.Unreachable -> {
                    TextButton(onClick = ui.onRetry, modifier = underStatus) { Text("Try again") }
                }

                else -> {}
            }
            val found = directions.status as? RouteStatus.Found
            if (found != null || directions.from != null) {
                // Outside the live region above: opening the steps is not read out as a new route.
                Column(Modifier.padding(end = 12.dp)) {
                    val marked = directions.shownStep
                    val step = directions.markedStep
                    if (found != null && marked != null && step != null) {
                        StepPreview(step, directions.stepIcon(step.turn), marked, found.route.steps.size, ui.onStep)
                    }
                    // The buttons' labels start under the route's time; they wrap at a large font.
                    FlowRow(Modifier.offset(x = (-12).dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        // First, as Google Maps' Start: the route from the user's position guides them there.
                        val start = ui.onStart
                        if (found != null && start != null && directions.canGuide) {
                            Button(onClick = start, modifier = Modifier.padding(start = 12.dp, end = 4.dp)) {
                                Icon(RoutingIcons.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Start")
                            }
                        }
                        if (found != null) StepsButton(found.route.steps, directions.stepsShown, ui.onSteps)
                        // As Google Maps' "Your location" field: back from a chosen start without
                        // opening the start's page to clear it.
                        if (directions.from != null) {
                            TextButton(onClick = ui.onFromYourLocation) {
                                Icon(AppIcons.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (directions.reversed) "To your location" else "From your location")
                            }
                        }
                    }
                    if (found != null) RouteCredits(ui.credits)
                }
            }
        }
    }
}

/**
 * The button that opens the route's steps, as in Google Maps' directions; none when the server gave no
 * steps. The list itself is [RouteStepList], in the page under the header: on a phone the header is
 * the sheet's lowest stop, which a list inside it would stretch over the map.
 */
@Composable
private fun StepsButton(
    steps: List<RouteStep>,
    shown: Boolean,
    onSteps: (Boolean) -> Unit,
) {
    if (steps.isEmpty()) return
    TextButton(
        onClick = { onSteps(!shown) },
        modifier = Modifier.semantics { stateDescription = if (shown) "Expanded" else "Collapsed" },
    ) {
        Text(if (shown) "Hide steps" else "Steps")
        Spacer(Modifier.width(4.dp))
        Icon(if (shown) RoutingIcons.ExpandLess else RoutingIcons.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

/**
 * The step the map shows ([Directions.markedStep], at [index] of [count], with its [icon]), with
 * buttons to the previous and the next one, as Google Maps' step preview: on a phone the place header is the sheet's lowest
 * stop, so the user goes through the steps on the map without raising the sheet to the list. A new
 * step is read out.
 */
@Composable
private fun StepPreview(
    step: RouteStep,
    icon: ImageVector,
    index: Int,
    count: Int,
    onStep: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(
                Modifier.weight(1f).padding(vertical = 8.dp).semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                },
            ) {
                Text(step.instruction, style = MaterialTheme.typography.bodyMedium)
                Text(stepPositionText(index, count, step.meters), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { onStep(index - 1) }, enabled = index > 0) {
                Icon(RoutingIcons.ChevronLeft, contentDescription = "Previous step")
            }
            IconButton(onClick = { onStep(index + 1) }, enabled = index < count - 1) {
                Icon(RoutingIcons.ChevronRight, contentDescription = "Next step")
            }
        }
    }
}

/**
 * The steps of the [directions]' route ([Directions.shownSteps]): each with its icon
 * ([Directions.stepIcon]), its sentence and how far the route then goes. A tap on a step shows it on
 * the map ([onStep]), as in Google Maps' steps; the tapped one ([Directions.shownStep]) is highlighted
 * while the map marks it.
 */
@Composable
fun RouteStepList(
    directions: Directions,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        val shown = directions.shownStep
        directions.shownSteps.forEachIndexed { i, step ->
            if (i > 0) HorizontalDivider(Modifier.padding(start = 56.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            val tapped = i == shown
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (tapped) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable(onClickLabel = "Show on map") { onStep(i) }
                    .semantics(mergeDescendants = true) { selected = tapped }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    directions.stepIcon(step.turn),
                    contentDescription = null,
                    tint = if (tapped) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(step.instruction, style = MaterialTheme.typography.bodyMedium)
                    // Below 5 m the distance would read "0 m".
                    if (step.meters >= 5) {
                        Text(
                            formatDistance(step.meters),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** The routing server's credits, each a link. */
@Composable
internal fun RouteCredits(credits: List<AttributionLink>) {
    if (credits.isEmpty()) return
    val openLink = rememberSafeLinkOpener()
    val styles =
        TextLinkStyles(
            style = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, textDecoration = TextDecoration.Underline),
        )
    val text =
        buildAnnotatedString {
            credits.forEachIndexed { i, link ->
                if (i > 0) append(" · ")
                withLink(LinkAnnotation.Url(link.url, styles) { openLink(link.url) }) { append(link.label) }
            }
        }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}
