package to.pubky.maps.routing

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
    val onClose: () -> Unit,
    /** Asks again: for the route after a failure, for the position when it was missing. */
    val onRetry: () -> Unit,
    /** Opens the app's system settings, where location access is turned on. */
    val onOpenSettings: (() -> Unit)?,
    /** The routing server's credits, shown with a found route. */
    val credits: List<AttributionLink>,
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

/** What the directions say while they have no route to show; null once a route is found. */
fun routeStatusText(directions: Directions): String? =
    when (val status = directions.status) {
        RouteStatus.Locating -> {
            "Finding your location…"
        }

        is RouteStatus.NoLocation -> {
            if (status.permissionOff) {
                "Directions start from your location. Location access is off for Pubky Maps."
            } else {
                "Directions start from your location. Is location turned on?"
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
                NoRouteReason.TooFar -> "Too far for a route ${directions.mode.phrase}."
                NoRouteReason.NoRoadNear -> "No road or path ${directions.mode.phrase} near your location or the place."
                NoRouteReason.NotConnected -> "No route ${directions.mode.phrase} from your location to this place."
            }
        }

        RouteStatus.Unreachable -> {
            "Can't reach the routing server. Check your connection."
        }
    }

/**
 * The directions on a place page: the modes, then the route's time and length (the map draws its
 * line) or what stands in its way, with the routing server's credits under a found route.
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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
            Column(
                Modifier.padding(end = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val found = directions.status as? RouteStatus.Found
                if (found != null) {
                    Text(
                        "${durationText(found.route.seconds)} · ${formatDistance(found.route.meters)}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "${directions.mode.phrase.replaceFirstChar { it.uppercase() }} from your location",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    RouteCredits(ui.credits)
                } else {
                    routeStatusText(directions)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    val status = directions.status
                    val settings = ui.onOpenSettings
                    when {
                        status is RouteStatus.NoLocation && status.permissionOff && settings != null -> {
                            TextButton(onClick = settings) { Text("Settings") }
                        }

                        status is RouteStatus.NoLocation || status == RouteStatus.Unreachable -> {
                            TextButton(onClick = ui.onRetry) { Text("Try again") }
                        }

                        else -> {}
                    }
                }
            }
        }
    }
}

/** The routing server's credits, each a link. */
@Composable
private fun RouteCredits(credits: List<AttributionLink>) {
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
