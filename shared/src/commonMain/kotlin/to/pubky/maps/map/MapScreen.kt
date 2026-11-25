package to.pubky.maps.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.pubky.maps.config.AttributionLink
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.search.RecentSearch
import to.pubky.maps.search.SearchActions
import to.pubky.maps.search.SearchBar
import to.pubky.maps.search.SearchDivider
import to.pubky.maps.search.SearchList
import to.pubky.maps.search.SearchMode
import to.pubky.maps.search.SearchState
import to.pubky.maps.status.ServicePart
import to.pubky.maps.ui.AppIcons
import to.pubky.maps.ui.rememberSafeLinkOpener

/** Space between the screen's safe area and the map controls. */
internal val MapControlMargin = 12.dp

/** Size of the round buttons at the top end of the map (settings, compass). */
internal val TopControlSize = 48.dp

/** From this width on, search shows beside the map in a side panel (tablets, phones in landscape). */
internal val SidePanelMinWidth = 600.dp

/** Width of the search bar and its panel beside the map. */
internal val SidePanelWidth = 400.dp

/** The tallest the results list over the map gets on a phone, as a share of the screen. */
private const val RESULTS_SHEET_HEIGHT_SHARE = 0.4f

/** What the map screen shows of search: plain state, so screenshots need no network. */
data class MapSearch(
    val state: SearchState,
    val recents: List<RecentSearch>,
    /** The user's last known position, for distances; null when unknown. */
    val userLocation: GeoPoint?,
    val actions: SearchActions,
)

/**
 * The map screen: [map] fills the screen, the controls sit on top of it inside the safe area.
 * The search bar top start with Settings beside it, my location bottom end, the data credits bottom
 * start, and [placeCard] (a place opened from a link or picked in search) under them at the bottom.
 * [missing] parts of the app (a service whose every server failed) show in one line under the bar.
 * Search: typing opens a full-screen page on a phone and a panel beside the map on a wide screen;
 * results show as pins and in a list (a sheet at the bottom on a phone, the panel on a wide screen).
 * [search] is null where search is not available.
 */
@Composable
fun MapScreen(
    attribution: List<AttributionLink>,
    mapUnavailable: Boolean,
    onRetryMap: () -> Unit,
    onMyLocation: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    missing: Set<ServicePart> = emptySet(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    placeCard: (@Composable () -> Unit)? = null,
    search: MapSearch? = null,
    map: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= SidePanelMinWidth
        val mode = search?.state?.mode ?: SearchMode.Idle
        map()
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(MapControlMargin),
        ) {
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // The bar takes the width a phone has; on a wide screen it keeps the panel's width. While
                // a phone types on the search page, that page has the only search field.
                if (search != null && !wide) {
                    if (mode != SearchMode.Editing) {
                        SearchBar(state = search.state, actions = search.actions, modifier = Modifier.weight(1f))
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.width(MapControlMargin))
                } else {
                    if (search != null) SearchBar(state = search.state, actions = search.actions, modifier = Modifier.width(SidePanelWidth))
                    Spacer(Modifier.weight(1f))
                }
                MapButton(
                    icon = AppIcons.Settings,
                    contentDescription = "Settings",
                    onClick = onOpenSettings,
                    size = TopControlSize,
                )
            }
            val panelShown = search != null && wide && (mode == SearchMode.Editing || mode == SearchMode.Results)
            Column(
                modifier =
                    Modifier
                        .align(if (panelShown) Alignment.TopEnd else Alignment.TopCenter)
                        .padding(top = TopControlSize + MapControlMargin)
                        .padding(start = if (panelShown) SidePanelWidth + MapControlMargin else 0.dp)
                        .widthIn(max = 480.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (mapUnavailable) MapUnavailableNotice(onRetry = onRetryMap)
                if (missing.isNotEmpty()) DegradedBanner(missing)
            }
            if (panelShown && search != null) {
                Surface(
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(top = TopControlSize + 8.dp)
                            .width(SidePanelWidth)
                            .heightIn(max = maxHeight - TopControlSize - 8.dp - 56.dp - MapControlMargin),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 3.dp,
                ) {
                    SearchList(
                        search.state,
                        search.recents,
                        search.userLocation,
                        search.actions,
                        contentPadding = PaddingValues(vertical = 8.dp),
                    )
                }
            }
            val sheetMaxHeight = maxHeight * RESULTS_SHEET_HEIGHT_SHARE
            // Bottom up: the place card or the results, the credits and my-location row above it, then snackbars.
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SnackbarHost(hostState = snackbarHostState, modifier = Modifier.padding(bottom = MapControlMargin))
                Box(Modifier.fillMaxWidth()) {
                    MapButton(
                        icon = AppIcons.MyLocation,
                        contentDescription = "Show my location",
                        onClick = onMyLocation,
                        size = 56.dp,
                        tinted = true,
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                    AttributionBar(
                        links = attribution,
                        modifier =
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(end = 56.dp + MapControlMargin),
                    )
                }
                if (placeCard != null) {
                    Box(Modifier.padding(top = MapControlMargin)) { placeCard() }
                } else if (search != null && !wide && mode == SearchMode.Results) {
                    ResultsSheet(search, maxHeight = sheetMaxHeight)
                }
            }
        }
        if (search != null && !wide && mode == SearchMode.Editing) {
            SearchPage(search)
        }
    }
}

/** The results over the map on a phone, at the bottom like Google Maps' list sheet. */
@Composable
private fun ResultsSheet(
    search: MapSearch,
    maxHeight: Dp,
) {
    Surface(
        modifier = Modifier.padding(top = MapControlMargin).fillMaxWidth().heightIn(max = maxHeight),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 3.dp,
    ) {
        SearchList(search.state, search.recents, search.userLocation, search.actions, contentPadding = PaddingValues(vertical = 8.dp))
    }
}

/** Typing on a phone: a page over the map with the bar on top and the list under it. */
@Composable
private fun SearchPage(search: MapSearch) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            SearchBar(
                state = search.state,
                actions = search.actions,
                elevated = false,
                modifier = Modifier.padding(MapControlMargin),
            )
            SearchDivider()
            SearchList(
                search.state,
                search.recents,
                search.userLocation,
                search.actions,
                modifier = Modifier.weight(1f).imePadding(),
            )
        }
    }
}

@Composable
private fun MapButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: Dp,
    modifier: Modifier = Modifier,
    tinted: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = if (tinted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription)
        }
    }
}

/** The credits for the map's data and tiles, always visible, each a link to its source. */
@Composable
internal fun AttributionBar(
    links: List<AttributionLink>,
    modifier: Modifier = Modifier,
) {
    val openLink = rememberSafeLinkOpener()
    val linkStyles = TextLinkStyles(style = SpanStyle(color = MaterialTheme.colorScheme.onSurface))
    val text =
        buildAnnotatedString {
            links.forEachIndexed { i, link ->
                if (i > 0) append("  ")
                withLink(LinkAnnotation.Url(link.url, linkStyles) { openLink(link.url) }) { append(link.label) }
            }
        }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun MapUnavailableNotice(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "The map could not load. Check your connection.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}
