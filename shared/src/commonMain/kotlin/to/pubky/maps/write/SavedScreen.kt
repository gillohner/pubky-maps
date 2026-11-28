package to.pubky.maps.write

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
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
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.place.formatCoordinate
import to.pubky.maps.ui.AppIcons
import to.pubky.maps.ui.SystemBack

/** A bookmark or a collection entry on the Bookmarks page: the place as stored, its name when known. */
data class SavedRow(
    val place: String,
    val name: String?,
    val kept: Kept,
    val pending: Boolean = false,
    /** Where it is, when known: told under its name when another place has its name and its summary does not tell them apart. */
    val point: GeoPoint? = null,
    /** What and where it is ("Café · Napfgasse 4, 8001 Zürich"), told under its name; null when not known. */
    val summary: String? = null,
)

/** What the Bookmarks page shows. */
data class SavedScreenState(
    val places: List<SavedRow>,
    val lists: List<OwnList>,
    /** Names of places, for collection entries. */
    val names: Map<String, String> = emptyMap(),
    /** What and where places are ([SavedRow.summary]), for collection entries. */
    val summaries: Map<String, String> = emptyMap(),
    /** The homeserver is being read again. */
    val refreshing: Boolean = false,
    /** Signed in: bookmarks and collections on the homeserver are shown too. */
    val signedIn: Boolean = false,
)

private val MaxSavedWidth = 720.dp

/**
 * The user's bookmarks and collections, from this phone's copy (refreshed from the homeserver when the
 * screen opens). The bookmarks are one row at the top, beside the collections, and open as their own
 * page ([BookmarkedPlacesPage]), as pubky.app's collections page has its Bookmarks card first and
 * Google Maps, CoMaps and OsmAnd keep starred places, bookmarks and favourites in a list among the
 * others: a long run of bookmarks no longer pushes the collections off the screen. A tap on a
 * collection opens its own page ([CollectionPage]); back (the arrow or the system back) returns here.
 * A tap on a place opens it on the map, with the name its row shows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(
    state: SavedScreenState,
    onOpen: (place: String, name: String?) -> Unit,
    onBack: () -> Unit,
) {
    var openList by rememberSaveable { mutableStateOf<String?>(null) }
    var bookmarksOpen by rememberSaveable { mutableStateOf(false) }
    // Read from the state each time, so the page shows the collection as it changes; a collection
    // that is gone (removed on another device) leaves its page for the Bookmarks page.
    val open = openList?.let { uri -> state.lists.firstOrNull { it.uri == uri } }
    // Forgotten once the collections are read without it, so it does not come back on screen by
    // itself if a later read has it again. While there are none yet (a restored screen before this
    // phone's copy is read) it is kept, so the page comes back.
    LaunchedEffect(open, state.lists) { if (open == null && state.lists.isNotEmpty()) openList = null }
    SystemBack(enabled = open != null || bookmarksOpen) {
        openList = null
        bookmarksOpen = false
    }
    // Here, not in the pages, which leave while another is open: back finds each list where it was.
    val overviewScroll = rememberLazyListState()
    val bookmarksScroll = rememberLazyListState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        open?.name ?: if (bookmarksOpen) BOOKMARKED_PLACES else "Bookmarks",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            when {
                                open != null -> openList = null
                                bookmarksOpen -> bookmarksOpen = false
                                else -> onBack()
                            }
                        },
                    ) {
                        Icon(AppIcons.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when {
                open != null -> CollectionPage(open, state, padding, onOpen)
                bookmarksOpen -> BookmarkedPlacesPage(state, padding, bookmarksScroll, onOpen)
                else -> BookmarksOverview(state, padding, overviewScroll, { bookmarksOpen = true }, { openList = it })
            }
        }
    }
}

/** The row and the page title for the user's bookmarks, as pubky.app's Bookmarks card names its posts. */
private const val BOOKMARKED_PLACES = "Bookmarked places"

private const val NO_BOOKMARKS_HINT = "Places you bookmark show here. Tap Bookmark on a place's page."

/**
 * The Bookmarks page: one row for the bookmarks (how many, how many still wait to be sent), then the
 * collections. Without bookmarks the row says how to make one and opens nothing: an empty page would
 * be a tap for nothing.
 */
@Composable
private fun BookmarksOverview(
    state: SavedScreenState,
    padding: PaddingValues,
    scroll: LazyListState,
    onOpenBookmarks: () -> Unit,
    onOpenList: (uri: String) -> Unit,
) {
    LazyColumn(state = scroll, contentPadding = padding, modifier = Modifier.widthIn(max = MaxSavedWidth).fillMaxWidth()) {
        if (state.refreshing) item(key = "refreshing") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item(key = "bookmarks") {
            val empty = state.places.isEmpty()
            ListItem(
                headlineContent = { Text(BOOKMARKED_PLACES, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(if (empty) NO_BOOKMARKS_HINT else bookmarksFacts(state.places)) },
                leadingContent = { Icon(WriteIcons.Bookmark, contentDescription = null) },
                modifier = if (empty) Modifier else Modifier.clickable(onClickLabel = "Open bookmarks", onClick = onOpenBookmarks),
            )
        }
        item(key = "lists-title") { Title("Your collections") }
        if (state.lists.isEmpty()) {
            item(key = "lists-empty") { Hint("Collections you make show here. Tap Add to collection on a place's page.") }
        }
        items(state.lists, key = { "list:${it.uri}" }) { list ->
            ListItem(
                headlineContent = { Text(list.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Column {
                        // Under the name, as pubky.app's collection cards show it: two lines here, so a
                        // long one does not push the next collection away; all of it on its page.
                        list.description?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        CollectionFacts(list)
                    }
                },
                leadingContent = { Icon(WriteIcons.SavedList, contentDescription = null) },
                modifier = Modifier.clickable(onClickLabel = "Open collection") { onOpenList(list.uri) },
            )
        }
        if (!state.signedIn) {
            item(key = "signed-out") {
                Hint("Sign in to see the bookmarks and collections you keep on your homeserver.")
            }
        }
    }
}

/**
 * How many places are bookmarked and how many of them wait to be sent ("5 places · 1 not sent yet"),
 * counted as the bookmarks page lists them.
 */
internal fun bookmarksFacts(places: List<SavedRow>): String {
    val count = places.size
    val pending = places.count { it.pending }
    val total = if (count == 1) "1 place" else "$count places"
    return if (pending == 0) total else "$total · $pending not sent yet"
}

/** The bookmarked places, each with what and where it is, how it is kept and whether it waits to be sent. */
@Composable
private fun BookmarkedPlacesPage(
    state: SavedScreenState,
    padding: PaddingValues,
    scroll: LazyListState,
    onOpen: (place: String, name: String?) -> Unit,
) {
    val placesToTellApart = rowsToTellApart(state.places.map { RowText(it.place, it.name, it.summary) })
    val points = state.places.mapNotNull { row -> row.point?.let { row.place to it } }.toMap()
    LazyColumn(state = scroll, contentPadding = padding, modifier = Modifier.widthIn(max = MaxSavedWidth).fillMaxWidth()) {
        if (state.refreshing) item(key = "refreshing") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        // Removed on another device while the page is open: the page stays and says how to add one.
        if (state.places.isEmpty()) {
            item(key = "places-empty") { Hint(NO_BOOKMARKS_HINT) }
        }
        items(state.places, key = { "place:${it.kept}:${it.place}" }) { row ->
            val where =
                whereText(row.place, row.point ?: points[row.place])
                    .takeIf { RowText(row.place, row.name, row.summary).key() in placesToTellApart }
            PlaceRow(row, savedLine(row.summary, where), onOpen)
        }
    }
}

/**
 * One collection's page: all of its description, where it is kept and how many places it holds, then
 * its places (a tap opens one on the map) and a line for the items that are not places. Its name is
 * the top bar's title, as Google Maps' list page puts the list's name at the top.
 */
@Composable
private fun CollectionPage(
    list: OwnList,
    state: SavedScreenState,
    padding: PaddingValues,
    onOpen: (place: String, name: String?) -> Unit,
) {
    val points = state.places.mapNotNull { row -> row.point?.let { row.place to it } }.toMap()
    val names = list.items.associateWith { list.names[it] ?: state.names[it] }
    val entriesToTellApart = rowsToTellApart(list.items.map { RowText(it, names[it], state.summaries[it]) })
    LazyColumn(contentPadding = padding, modifier = Modifier.widthIn(max = MaxSavedWidth).fillMaxWidth()) {
        if (state.refreshing) item(key = "refreshing") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item(key = "about") {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            ) {
                list.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                CollectionFacts(list)
            }
        }
        if (list.items.isEmpty()) {
            item(key = "empty") { Hint("No places in it yet. Tap Add to collection on a place's page.") }
        }
        // Keyed by position: a collection made elsewhere may hold the same link twice, and two equal keys crash the list.
        itemsIndexed(list.items, key = { index, _ -> "item:$index" }) { _, place ->
            val name = names[place]
            val summary = state.summaries[place]
            val where = whereText(place, points[place]).takeIf { RowText(place, name, summary).key() in entriesToTellApart }
            val line = savedLine(summary, where)
            ListItem(
                headlineContent = { Text(name ?: placeText(place), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = line?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                leadingContent = { Icon(AppIcons.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { onOpen(place, name) },
            )
        }
        // Another app's posts or profiles in the collection (pubky.app shares the folder): counted, not listed.
        if (list.otherItems > 0) {
            item(key = "others") {
                val why = if (list.otherItems == 1) "it is not a place" else "they are not places"
                Hint("${list.otherItemsText} not shown: $why.")
            }
        }
    }
}

/** Where a collection is kept and how many places it holds ("Public · 2 places"). */
@Composable
private fun CollectionFacts(list: OwnList) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        KeptBadge(list.kept)
        Text(if (list.items.size == 1) "1 place" else "${list.items.size} places")
    }
}

@Composable
private fun PlaceRow(
    row: SavedRow,
    line: String?,
    onOpen: (place: String, name: String?) -> Unit,
) {
    ListItem(
        headlineContent = { Text(row.name ?: placeText(row.place), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                // Above the badge, as a map app's saved list puts what and where a place is under its name.
                if (line != null) Text(line, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    KeptBadge(row.kept)
                    if (row.pending) Text("Not sent yet", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        leadingContent = { Icon(WriteIcons.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable { onOpen(row.place, row.name) },
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

/** A row's place, name and summary, as [rowsToTellApart] compares rows. */
internal data class RowText(
    val place: String,
    val name: String?,
    val summary: String?,
) {
    /** What tells this row from another: its place, then its name and summary compared as [sameName] does. */
    internal fun key(): Triple<String, String?, String?> = Triple(place, sameName(name), sameName(summary))
}

/**
 * The rows among [rows] ([RowText.key]) that need [whereText] under their name: another place has the
 * same name and the row's summary does not tell them apart, because the row has none or the other
 * place has the same one. Two Coops on different streets are told apart by their summaries; a Coop
 * with a summary and one without by the bare one's point (the other's summary tells it already, and
 * the bare row would otherwise have no line at all); two with the same summary or none by both points.
 * A place kept twice (on the phone and on the homeserver) is one place; a place without a name shows
 * its link and is never one.
 */
internal fun rowsToTellApart(rows: List<RowText>): Set<Triple<String, String?, String?>> {
    val named = rows.map { it.key() }.filter { it.second != null }.distinct()
    val byName = named.groupBy { it.second }
    return named
        .filter { (place, name, summary) ->
            byName.getValue(name).any { (otherPlace, _, otherSummary) ->
                otherPlace != place && (summary == null || otherSummary == summary)
            }
        }.toSet()
}

/** A name as the eye compares two rows: case and the spaces around it do not tell places apart. */
internal fun sameName(name: String?): String? = name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

/**
 * The line under a row's name: what and where the place is ("Café · Napfgasse 4, 8001 Zürich"), then
 * [where] when the row needs it to be told apart from a same-named one; null when there is neither.
 */
internal fun savedLine(
    summary: String?,
    where: String?,
): String? = listOfNotNull(summary, where).joinToString(" · ").ifEmpty { null }

/**
 * Where a place is, for a row its name and summary do not tell from another place ([rowsToTellApart]): its point ("47.37689,
 * 8.54169"), else its link in words. The point is what tells two Coops with no stored address apart.
 */
internal fun whereText(
    place: String,
    point: GeoPoint?,
): String = point?.let { "${formatCoordinate(it.lat)}, ${formatCoordinate(it.lon)}" } ?: placeText(place)

/**
 * A place's link in words: "OpenStreetMap node 270803095", "47.37687, 8.54169". It is a row's name only
 * for a place whose name is not known, and the line under a shared name only when its point is not
 * known: a named place shows its name alone, as map apps' saved lists do, because the link is the
 * app's reference, not something the user reads.
 */
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
