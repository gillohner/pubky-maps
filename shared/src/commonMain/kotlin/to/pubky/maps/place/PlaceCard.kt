package to.pubky.maps.place

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.pubky.maps.links.LinkProvider
import to.pubky.maps.ui.AppIcons
import to.pubky.maps.ui.MapSurfaces

/** The widest the place card gets; on a tablet it stays a card instead of a banner. */
private val MaxCardWidth = 560.dp

/**
 * The card of a place opened from a link or picked in search: its name, what it is and how far,
 * where it is, and Share. The place page replaces it later; until then it is the one place surface.
 * [distance] is how far the place is from the user ("1.2 km"), null when unknown.
 */
@Composable
fun PlaceCard(
    place: PlaceRef,
    provider: LinkProvider?,
    onShare: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    distance: String? = null,
) {
    Surface(
        modifier = modifier.widthIn(max = MaxCardWidth).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MapSurfaces.card,
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = place.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(top = 8.dp)
                            .semantics { heading() },
                )
                IconButton(onClick = onClose) { Icon(AppIcons.Close, contentDescription = "Close") }
            }
            val facts = listOfNotNull(place.category, distance)
            if (facts.isNotEmpty()) {
                Text(
                    text = facts.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            val details = listOfNotNull(place.detailLine(), provider?.let { "From ${it.withArticle} link" })
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            place.offlineNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                )
            }
            if (place.point == null && !place.shortLinkUnfollowed) {
                Text(
                    text = "This link names no point on the map.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                )
            }
            if (onShare != null) {
                Spacer(Modifier.size(12.dp))
                FilledTonalButton(onClick = onShare) {
                    Icon(AppIcons.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Share")
                }
            }
        }
    }
}

/** Shown while a shared link is read (a short link followed, Pinakes asked). */
@Composable
fun OpeningLinkCard(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(max = MaxCardWidth).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MapSurfaces.card,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
            Text("Opening the link…", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Why the card shows less than it could, when a network step failed. */
internal val PlaceRef.offlineNote: String?
    get() =
        when {
            shortLinkUnfollowed -> "Offline: the short link could not be followed."
            offline -> "Offline: shown from the link alone."
            else -> null
        }

/**
 * The address, else the district and town, else the coordinates of a pinned place unless the title
 * already is them (a view or a search has none).
 */
internal fun PlaceRef.detailLine(): String? {
    (address ?: locality)?.let { return it }
    if (!pinned) return null
    val point = point ?: return null
    val coordinates = "${formatCoordinate(point.lat)}, ${formatCoordinate(point.lon)}"
    return coordinates.takeIf { it != title }
}

/** The provider's name as people know it. */
val LinkProvider.title: String
    get() =
        when (this) {
            // geo: links come from any map app.
            LinkProvider.Geo -> "map"

            LinkProvider.Google -> "Google Maps"

            LinkProvider.Apple -> "Apple Maps"

            LinkProvider.Waze -> "Waze"

            LinkProvider.OpenStreetMap -> "OpenStreetMap"

            LinkProvider.OsmAnd -> "OsmAnd"

            LinkProvider.OrganicMaps -> "Organic Maps"

            LinkProvider.CoMaps -> "CoMaps"

            LinkProvider.Here -> "HERE WeGo"
        }

/** "a Google Maps", "an OsmAnd". */
internal val LinkProvider.withArticle: String
    get() = if (title.first().lowercaseChar() in "aeiou") "an $title" else "a $title"
