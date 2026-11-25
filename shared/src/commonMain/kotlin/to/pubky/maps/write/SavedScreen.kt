package to.pubky.maps.write

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.pubky.maps.place.PlaceIcons
import to.pubky.maps.ui.AppIcons

/** A saved place or a list entry on the Saved screen: the place as stored, its name when known. */
data class SavedRow(
    val place: String,
    val name: String?,
    val kept: Kept,
    val pending: Boolean = false,
)

/** What the Saved screen shows. */
data class SavedScreenState(
    val places: List<SavedRow>,
    val lists: List<OwnList>,
    /** Names of places, for list entries. */
    val names: Map<String, String> = emptyMap(),
    /** The homeserver is being read again. */
    val refreshing: Boolean = false,
    /** Signed in: saved places and lists on the homeserver are shown too. */
    val signedIn: Boolean = false,
)

private val MaxSavedWidth = 720.dp

/**
 * The user's saved places and lists, from this phone's copy (refreshed from the homeserver when the
 * screen opens). A tap opens the place on the map.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(
    state: SavedScreenState,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    var openList by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(contentPadding = padding, modifier = Modifier.widthIn(max = MaxSavedWidth).fillMaxWidth()) {
                if (state.refreshing) item(key = "refreshing") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item(key = "places-title") { Title("Places") }
                if (state.places.isEmpty()) {
                    item(key = "places-empty") {
                        Hint("Places you save show here. Tap Save on a place's page.")
                    }
                }
                items(state.places, key = { "place:${it.kept}:${it.place}" }) { row ->
                    PlaceRow(row, onOpen)
                }
                item(key = "lists-title") { Title("Your lists") }
                if (state.lists.isEmpty()) {
                    item(key = "lists-empty") { Hint("Lists you make show here. Tap Add to list on a place's page.") }
                }
                state.lists.forEach { list ->
                    item(key = "list:${list.uri}") {
                        val expanded = openList == list.uri
                        ListItem(
                            headlineContent = { Text(list.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    KeptBadge(list.kept)
                                    Text(if (list.items.size == 1) "1 place" else "${list.items.size} places")
                                }
                            },
                            leadingContent = { Icon(WriteIcons.AddToList, contentDescription = null) },
                            trailingContent = {
                                Icon(
                                    if (expanded) PlaceIcons.ExpandLess else PlaceIcons.ExpandMore,
                                    contentDescription = if (expanded) "Hide its places" else "Show its places",
                                )
                            },
                            modifier = Modifier.clickable { openList = if (expanded) null else list.uri },
                        )
                    }
                    if (openList == list.uri) {
                        items(list.items, key = { "list:${list.uri}:$it" }) { place ->
                            val name = list.names[place] ?: state.names[place]
                            ListItem(
                                headlineContent = { Text(name ?: placeText(place), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent =
                                    name?.let {
                                        {
                                            Text(
                                                placeText(place),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    },
                                modifier = Modifier.padding(start = 40.dp).clickable { onOpen(place) },
                            )
                        }
                    }
                }
                if (!state.signedIn) {
                    item(key = "signed-out") {
                        Hint("Sign in to see the places and lists you keep on your homeserver.")
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(
    row: SavedRow,
    onOpen: (String) -> Unit,
) {
    ListItem(
        headlineContent = { Text(row.name ?: placeText(row.place), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                KeptBadge(row.kept)
                Text(
                    if (row.pending) {
                        "Not sent yet"
                    } else if (row.name != null) {
                        placeText(row.place)
                    } else {
                        ""
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = { Icon(WriteIcons.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable { onOpen(row.place) },
    )
}

@Composable
private fun Title(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** A place's link in words, for one whose name is not known: "OpenStreetMap node 270803095", "47.37687, 8.54169". */
internal fun placeText(place: String): String {
    val osm = Regex("^https://www\\.openstreetmap\\.org/(node|way|relation)/(\\d+)$").matchEntire(place)
    if (osm != null) return "OpenStreetMap ${osm.groupValues[1]} ${osm.groupValues[2]}"
    if (place.startsWith("geo:")) {
        return place
            .removePrefix("geo:")
            .substringBefore('?')
            .substringBefore(';')
            .replace(",", ", ")
    }
    return place.removePrefix("https://").removePrefix("http://").removePrefix("www.")
}
