package to.pubky.maps.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import to.pubky.maps.config.AttributionLink
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.ui.AppIcons
import to.pubky.maps.ui.rememberSafeLinkOpener
import to.pubky.maps.write.WriteIcons

/** Height of the search bar, as Google Maps and CoMaps draw theirs. */
internal val SearchBarHeight = 48.dp

/** The text the bar shows when empty. */
internal const val SEARCH_HINT = "Search here"

/** What the search screens can do; the map route wires these to a [SearchController]. */
data class SearchActions(
    val onStartEditing: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onSelect: (SearchResult) -> Unit = {},
    val onSelectRecent: (RecentSearch) -> Unit = {},
    val onClearQuery: () -> Unit = {},
    val onBack: () -> Unit = {},
    /** Opens the user's saved places and lists; null where there are none. */
    val onOpenSaved: (() -> Unit)? = null,
)

/**
 * The search bar: a magnifier and "Search here" on the map; while searching, a back arrow and a
 * clear button. Focus follows the mode: typing raises the keyboard, anything else lowers it.
 */
@Composable
fun SearchBar(
    state: SearchState,
    actions: SearchActions,
    modifier: Modifier = Modifier,
    elevated: Boolean = true,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val editing = state.mode == SearchMode.Editing
    // The field keeps its own text and selection, so typing never waits for the controller's state
    // (a late value would reset the keyboard's composition); it starts again from the controller's
    // text only when the controller set the text itself (a recent search picked, the bar cleared).
    var field by remember(state.textSetBySearch) { mutableStateOf(TextFieldValue(state.query, TextRange(state.query.length))) }
    LaunchedEffect(editing) {
        if (editing) focusRequester.requestFocus() else focusManager.clearFocus()
    }
    Surface(
        modifier = modifier.heightIn(min = SearchBarHeight).fillMaxWidth(),
        shape = RoundedCornerShape(SearchBarHeight / 2),
        color = if (elevated) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = if (elevated) 3.dp else 0.dp,
    ) {
        Row(Modifier.heightIn(min = SearchBarHeight), verticalAlignment = Alignment.CenterVertically) {
            if (state.mode == SearchMode.Idle) {
                Icon(
                    AppIcons.Search,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 16.dp, end = 4.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                IconButton(onClick = actions.onBack) { Icon(AppIcons.ArrowBack, contentDescription = "Back") }
            }
            Box(Modifier.weight(1f).padding(start = if (state.mode == SearchMode.Idle) 4.dp else 0.dp)) {
                if (field.text.isEmpty()) {
                    Text(
                        SEARCH_HINT,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = field,
                    onValueChange = {
                        val textChanged = it.text != field.text
                        field = it
                        if (textChanged) actions.onQueryChange(it.text)
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, keyboardType = KeyboardType.Text),
                    keyboardActions = KeyboardActions(onSearch = { actions.onSubmit() }),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { if (it.isFocused && !editing) actions.onStartEditing() }
                            .semantics { contentDescription = SEARCH_HINT },
                )
            }
            if (field.text.isNotEmpty()) {
                IconButton(onClick = actions.onClearQuery) { Icon(AppIcons.Close, contentDescription = "Clear search") }
            } else {
                Box(Modifier.size(12.dp))
            }
        }
    }
}

/**
 * The list under the bar: recent searches while the text is short, else the results with a line on
 * what is missing. [userLocation] gives each result its distance.
 */
@Composable
fun SearchList(
    state: SearchState,
    recents: List<RecentSearch>,
    userLocation: GeoPoint?,
    actions: SearchActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    credits: List<AttributionLink> = emptyList(),
) {
    val shownRecents = recentsFor(state, recents)
    LazyColumn(modifier = modifier, contentPadding = contentPadding) {
        if (state.searching) {
            item(key = "progress") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) }
        }
        statusLine(state)?.let { line -> item(key = "status") { StatusLine(line, offline = state.offline) } }
        if (shownRecents != null) {
            val openSaved = actions.onOpenSaved
            if (openSaved != null && state.query.isBlank()) {
                item(key = "saved") {
                    ListItem(
                        headlineContent = { Text("Saved") },
                        supportingContent = { Text("Your saved places and lists") },
                        leadingContent = { Icon(WriteIcons.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable(onClick = openSaved),
                    )
                }
            }
            if (shownRecents.isNotEmpty() && state.query.isBlank()) {
                item(key = "recent-title") {
                    Text(
                        "Recent searches",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
                    )
                }
            }
            items(shownRecents, key = { "recent:${it.key}" }) { entry -> RecentRow(entry, userLocation) { actions.onSelectRecent(entry) } }
        } else {
            items(state.results, key = { "result:${it.place.uri}" }) { result ->
                ResultRow(result, userLocation, selected = result == state.selected) { actions.onSelect(result) }
            }
            // The list may cover the map and its credits: the results' sources are named under them.
            if (state.results.isNotEmpty() && credits.isNotEmpty()) item(key = "credits") { CreditsLine(credits) }
        }
    }
}

/** The sources of the results, each a link (Photon, OpenStreetMap). */
@Composable
private fun CreditsLine(credits: List<AttributionLink>) {
    val openLink = rememberSafeLinkOpener()
    val linkStyles = TextLinkStyles(style = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant))
    val text =
        buildAnnotatedString {
            credits.forEachIndexed { i, link ->
                if (i > 0) append("  ")
                withLink(LinkAnnotation.Url(link.url, linkStyles) { openLink(link.url) }) { append(link.label) }
            }
        }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * The recent searches to show: all of them for an empty bar, those starting with the text while it is
 * too short to search; null once results are what the list shows.
 */
internal fun recentsFor(
    state: SearchState,
    recents: List<RecentSearch>,
): List<RecentSearch>? {
    val text = state.query.trim()
    if (state.results.isNotEmpty() || state.photon != SourceState.Idle || state.pinakes != SourceState.Idle) return null
    if (text.isEmpty()) return recents
    if (text.length >= SearchController.MIN_QUERY_LENGTH) return null
    val prefix = normalizeForMatch(text)
    return recents.filter { r ->
        normalizeForMatch(r.query).startsWith(prefix) || r.place?.let { normalizeForMatch(it.title).startsWith(prefix) } == true
    }
}

/** One line on what the list lacks: offline, one source missing, or nothing found. */
internal fun statusLine(state: SearchState): String? =
    when {
        state.offline -> {
            "Search is offline: neither Photon nor Pinakes is answering. Check your connection or the server lists in Settings."
        }

        state.photon == SourceState.Unreachable -> {
            "Addresses and streets are missing: Photon is not answering."
        }

        state.pinakes == SourceState.Unreachable -> {
            "Places from Pubky are missing: Pinakes is not answering."
        }

        !state.searching &&
            state.resultsAreCurrent &&
            state.results.isEmpty() &&
            (state.photon == SourceState.Answered || state.pinakes == SourceState.Answered) -> {
            "No results for “${state.query.trim()}”"
        }

        else -> {
            null
        }
    }

@Composable
private fun StatusLine(
    text: String,
    offline: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (offline) Icon(AppIcons.SearchOffline, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResultRow(
    result: SearchResult,
    userLocation: GeoPoint?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val place = result.place
    ListItem(
        headlineContent = { Text(place.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent =
            resultDetail(result).takeIf { it.isNotEmpty() }?.let { detail ->
                { Text(detail, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            },
        leadingContent = { Icon(result.kind.icon, contentDescription = null) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                distanceText(userLocation, place.point)?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                if (result.onPubky) {
                    Text("On Pubky", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        colors =
            ListItemDefaults.colors(
                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            ),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun RecentRow(
    entry: RecentSearch,
    userLocation: GeoPoint?,
    onClick: () -> Unit,
) {
    val place = entry.place
    ListItem(
        headlineContent = { Text(place?.title ?: entry.query, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent =
            place?.let { p ->
                listOfNotNull(p.category, p.address).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { detail ->
                    { Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            },
        leadingContent = { Icon(AppIcons.History, contentDescription = null) },
        trailingContent = distanceText(userLocation, place?.point)?.let { d -> { Text(d, style = MaterialTheme.typography.labelMedium) } },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** "Museum · Heimplatz 1, 8001 Zürich" (or the coordinates of a typed point). */
internal fun resultDetail(result: SearchResult): String {
    val place = result.place
    val second =
        if (place.source == PlaceSource.Coordinates) {
            place.name?.let { place.point?.let { p -> "${p.lat}, ${p.lon}" } }
        } else {
            place.address
        }
    return listOfNotNull(place.category, second).joinToString(" · ")
}

/** How far [to] is from [from] ("1.2 km"), null when either is unknown. */
fun distanceText(
    from: GeoPoint?,
    to: GeoPoint?,
): String? = if (from != null && to != null) formatDistance(distanceMeters(from, to)) else null

/** A panel's divider under the bar, as in the full-screen search page. */
@Composable
internal fun SearchDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
