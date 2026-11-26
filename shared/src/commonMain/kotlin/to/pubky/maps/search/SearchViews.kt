package to.pubky.maps.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
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
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filterNotNull
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
    /** Removes one recent search (a long press on it); null where the history cannot be changed. */
    val onRemoveRecent: ((RecentSearch) -> Unit)? = null,
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
 * what is missing. [userLocation] gives each result its distance. [onFirstRowEnd], when given, hears
 * how far (in pixels, from the list's top) the first result reaches, with the lines above it, each
 * time it is laid out with the list at its top (the first row of any kind while there is no result).
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
    onFirstRowEnd: ((Int) -> Unit)? = null,
) {
    val shownRecents = recentsFor(state, recents)
    val listState = rememberLazyListState()
    if (onFirstRowEnd != null) {
        val report by rememberUpdatedState(onFirstRowEnd)
        val firstKey = if (shownRecents == null) state.results.firstOrNull()?.let { resultKey(it) } else null
        LaunchedEffect(listState, firstKey) {
            snapshotFlow { firstRowEnd(listState.layoutInfo, firstKey) }.filterNotNull().collect { report(it) }
        }
    }
    LazyColumn(modifier = modifier, state = listState, contentPadding = contentPadding) {
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
            items(shownRecents, key = { "recent:${it.key}" }) { entry ->
                RecentRow(
                    entry = entry,
                    userLocation = userLocation,
                    onClick = { actions.onSelectRecent(entry) },
                    onRemove = actions.onRemoveRecent?.let { remove -> { remove(entry) } },
                )
            }
        } else {
            items(state.results, key = ::resultKey) { result ->
                ResultRow(result, userLocation, selected = result == state.selected) { actions.onSelect(result) }
            }
            // The list may cover the map and its credits: the results' sources are named under them.
            if (state.results.isNotEmpty() && credits.isNotEmpty()) item(key = "credits") { CreditsLine(credits) }
        }
    }
}

private fun resultKey(result: SearchResult): String = "result:${result.place.uri}"

/**
 * Where the row with [key] (else the list's first row) ends, from the list's top, or null while the
 * list is scrolled away from its top. While that row is not laid out yet (the rows above it fill a
 * low sheet), a pixel past the last row laid out: a sheet grown to that lays out the next row, and so
 * reaches the one with [key].
 */
private fun firstRowEnd(
    layout: LazyListLayoutInfo,
    key: String?,
): Int? {
    val shown = layout.visibleItemsInfo
    val top = shown.firstOrNull() ?: return null
    if (top.index != 0 || top.offset != 0) return null
    val row = if (key == null) top else shown.firstOrNull { it.key == key }
    val end = (row ?: shown.last()).let { it.offset + it.size - layout.viewportStartOffset }
    return if (row == null) end + 1 else end
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
        state.offline && state.offlineMaps == SourceState.Answered -> {
            if (state.results.isEmpty() && state.resultsAreCurrent) {
                "Search is offline, and nothing in your offline maps matches “${state.query.trim()}”."
            } else {
                "Search is offline: these places are from your offline maps. Addresses need a connection."
            }
        }

        state.offline -> {
            "Search is offline: neither Photon nor Pinakes is answering. Check your connection or the server lists in Settings."
        }

        state.photon == SourceState.Unreachable && state.offlineMaps == SourceState.Answered -> {
            "Photon is not answering: places and streets come from your offline maps, addresses are missing."
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

/**
 * A search result: its name, then its kind and address. At normal size a usual name and address
 * take one line each. They may wrap (name to 2 lines, kind and address to 3) so that at Android's
 * largest font a row still names the place and its town in full, as Google Maps' and OsmAnd's result
 * rows grow: the kind fills the first line and the address needs two more at 200 % beside the
 * distance. The caps keep one long name or address from filling the list.
 */
@Composable
private fun ResultRow(
    result: SearchResult,
    userLocation: GeoPoint?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val place = result.place
    ListItem(
        headlineContent = { Text(place.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent =
            resultDetail(result).takeIf { it.isNotEmpty() }?.let { detail ->
                { Text(detail, maxLines = 3, overflow = TextOverflow.Ellipsis) }
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

/** The words of the long-press menu on a recent search, and of its TalkBack action. */
internal const val REMOVE_RECENT = "Remove from recent searches"

/** What TalkBack says a long press on a recent search does: it opens the menu. */
internal const val RECENT_OPTIONS = "Show options"

/**
 * A past search. A long press opens a menu to remove it ([onRemove], when the history can change): the
 * row keeps the distance as its only trailing item, as quiet as Google Maps' and CoMaps' history rows.
 * Its name and its kind and address stay on one line each, also at a large font: a recent search is a
 * place the user picked before and knows by name, and short rows keep more of the history in view.
 * TalkBack lists "Remove from recent searches" among the row's actions, which removes it at once.
 */
@Composable
private fun RecentRow(
    entry: RecentSearch,
    userLocation: GeoPoint?,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    val place = entry.place
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(place?.title ?: entry.query, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent =
                place?.let { p ->
                    listOfNotNull(p.category, p.address).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { detail ->
                        { Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                },
            leadingContent = { Icon(AppIcons.History, contentDescription = null) },
            trailingContent =
                distanceText(userLocation, place?.point)?.let { d -> { Text(d, style = MaterialTheme.typography.labelMedium) } },
            // The row the menu is for stays marked while the menu is open.
            colors =
                ListItemDefaults.colors(
                    containerColor = if (menuOpen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                ),
            modifier =
                Modifier
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onRemove?.let { { menuOpen = true } },
                        onLongClickLabel = onRemove?.let { RECENT_OPTIONS },
                    ).semantics {
                        if (onRemove != null) {
                            customActions =
                                listOf(
                                    CustomAccessibilityAction(REMOVE_RECENT) {
                                        onRemove()
                                        true
                                    },
                                )
                        }
                    },
        )
        if (onRemove != null) {
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                offset = DpOffset(x = 56.dp, y = 0.dp),
            ) {
                DropdownMenuItem(
                    text = { Text(REMOVE_RECENT) },
                    leadingIcon = { Icon(AppIcons.Close, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onRemove()
                    },
                )
            }
        }
    }
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
