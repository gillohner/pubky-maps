package to.pubky.maps.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDateTime
import to.pubky.maps.config.AttributionLink
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.routing.GuidanceBanner
import to.pubky.maps.routing.GuidanceBar
import to.pubky.maps.routing.GuidanceUi
import to.pubky.maps.search.RecentSearch
import to.pubky.maps.search.SearchActions
import to.pubky.maps.search.SearchBar
import to.pubky.maps.search.SearchDivider
import to.pubky.maps.search.SearchList
import to.pubky.maps.search.SearchMode
import to.pubky.maps.search.SearchState
import to.pubky.maps.status.ServicePart
import to.pubky.maps.ui.AppIcons
import to.pubky.maps.ui.MapSurfaces
import to.pubky.maps.ui.NoteWithAction
import to.pubky.maps.ui.rememberSafeLinkOpener

/** Space between the screen's safe area and the map controls. */
internal val MapControlMargin = 12.dp

/** Size of the round buttons at the top end of the map (settings, compass). */
internal val TopControlSize = 48.dp

/** From this width on, search shows beside the map in a side panel (tablets, phones in landscape). */
internal val SidePanelMinWidth = 600.dp

/** Width of the search bar and its panel beside the map. */
internal val SidePanelWidth = 400.dp

/** My location over a line of credits: the panel beside the map ends above them. */
private val BottomControlsHeight = 96.dp

/**
 * The place page in the map screen: [sheet] at the bottom of a phone (given its largest height and the
 * system bar inset under it), [panel] beside the map on a wide screen. [full] is true while the phone
 * sheet takes the whole screen.
 */
class PlacePageSlot(
    val full: Boolean,
    val sheet: @Composable (maxHeight: Dp, bottomInset: Dp) -> Unit,
    val panel: @Composable (modifier: Modifier) -> Unit,
)

/** What the map screen shows of search: plain state, so screenshots need no network. */
data class MapSearch(
    val state: SearchState,
    val recents: List<RecentSearch>,
    /** The user's last known position, for distances; null when unknown. */
    val userLocation: GeoPoint?,
    val actions: SearchActions,
    /** The sources of search's results (Photon, OpenStreetMap), named under the results. */
    val credits: List<AttributionLink> = emptyList(),
    /** The phone's local time, for whether results are open now; null shows no opening hours. */
    val now: LocalDateTime? = null,
)

/**
 * The map screen: [map] fills the screen, the controls sit on top of it inside the safe area.
 * The search bar top start with Settings beside it, my location bottom end with the data credits
 * under it in the bottom start corner, and [placeCard] (a place opened from a link or picked in
 * search) under them at the bottom. A soft scrim under the status bar keeps its icons readable.
 * [missing] parts of the app (a service whose every server failed) show in one line under the bar.
 * Search: typing opens a full-screen page on a phone and a panel beside the map on a wide screen;
 * results show as pins and in a list (a sheet at the bottom on a phone, the panel on a wide screen).
 * [search] is null where search is not available. While [areaPicker] is set, the map shows only the
 * area picker over it. While a place page shows, [onUncoveredMap] is given the part of the map it
 * leaves in view (under the search bar and the notices under it, above the sheet and the controls
 * riding on it, or beside the panel and above the credits), in the map's dp; null while none shows.
 * [onSheetAtTop] is told whether the phone sheet is at its top stop.
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
    placePage: PlacePageSlot? = null,
    areaPicker: AreaPickerUi? = null,
    onUncoveredMap: (DpRect?) -> Unit = {},
    onSheetAtTop: (Boolean) -> Unit = {},
    /** A route guiding the user: the map shows only its banner and bar, no search or place page. */
    guidance: GuidanceUi? = null,
    map: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= SidePanelMinWidth
        val mode = search?.state?.mode ?: SearchMode.Idle
        val sheetShown = search != null && !wide && mode == SearchMode.Results && placeCard == null && placePage == null
        val pageSheet = placePage != null && !wide
        val pagePanel = placePage != null && wide
        val screenHeight = maxHeight
        val report by rememberUpdatedState(onUncoveredMap)
        // The map unavailable notice and the degraded banner under the bar cover the map: the room
        // starts under them (they are 0 dp tall when none shows).
        var bannersHeight by remember { mutableStateOf(0.dp) }
        val density = LocalDensity.current
        val roomTop = uncoveredMapTop(WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding(), bannersHeight)
        // The phone sheet's room is known only once the sheet is placed (it reports it then).
        val panelRoom =
            if (pagePanel && areaPicker == null) panelUncoveredMap(maxWidth, maxHeight, roomTop, LocalLayoutDirection.current) else null
        if (!pageSheet || areaPicker != null) SideEffect { report(panelRoom) }
        val sheetAtTop = pageSheet && areaPicker == null && placePage.full
        val reportAtTop by rememberUpdatedState(onSheetAtTop)
        SideEffect { reportAtTop(sheetAtTop) }
        map()
        if (areaPicker != null) {
            AreaPickerOverlay(areaPicker, attribution)
            return@BoxWithConstraints
        }
        if (guidance != null) {
            SideEffect { report(null) }
            GuidanceOverlay(guidance, attribution, missing, snackbarHostState)
            return@BoxWithConstraints
        }
        StatusBarScrim()
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
            val searchPanel = search != null && wide && (mode == SearchMode.Editing || mode == SearchMode.Results)
            val panelShown = searchPanel || pagePanel
            Column(
                modifier =
                    Modifier
                        .align(if (panelShown) Alignment.TopEnd else Alignment.TopCenter)
                        .padding(top = TopControlSize + MapControlMargin)
                        .padding(start = if (panelShown) SidePanelWidth + MapControlMargin else 0.dp)
                        .widthIn(max = 480.dp)
                        .onSizeChanged { bannersHeight = with(density) { it.height.toDp() } },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (mapUnavailable) MapUnavailableNotice(onRetry = onRetryMap)
                if (missing.isNotEmpty()) DegradedBanner(missing)
            }
            if (pagePanel) {
                placePage.panel(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(top = TopControlSize + 8.dp)
                        .width(SidePanelWidth)
                        .height(maxHeight - TopControlSize - 8.dp),
                )
            } else if (searchPanel) {
                // Floats over the map like the bar above it: the same edge in dark (MapSurfaces).
                Surface(
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(top = TopControlSize + 8.dp)
                            .width(SidePanelWidth)
                            .heightIn(max = maxHeight - TopControlSize - 8.dp - BottomControlsHeight - MapControlMargin),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = MapSurfaces.edge,
                    shadowElevation = 3.dp,
                ) {
                    SearchList(
                        search.state,
                        search.recents,
                        search.userLocation,
                        search.actions,
                        contentPadding = PaddingValues(vertical = 8.dp),
                        credits = search.credits,
                        now = search.now,
                    )
                }
            }
            // Bottom up: the place card, then the credits and my location above it, then snackbars. With
            // the results sheet they ride on its top edge instead.
            if (!sheetShown && !pageSheet) {
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Beside the place panel, which takes the start side down to the bottom.
                    BottomControls(
                        attribution,
                        onMyLocation,
                        snackbarHostState,
                        creditsStart = if (pagePanel) SidePanelWidth + MapControlMargin else 0.dp,
                    )
                    if (placeCard != null) {
                        Box(Modifier.padding(top = MapControlMargin)) { placeCard() }
                    }
                }
            }
        }
        if (sheetShown) {
            val insets = WindowInsets.safeDrawing.asPaddingValues()
            ResultsSheet(
                height = screenHeight,
                topSpace = insets.calculateTopPadding() + MapControlMargin + TopControlSize + 8.dp,
                bottomInset = insets.calculateBottomPadding(),
                above = {
                    Box(Modifier.padding(horizontal = MapControlMargin).padding(bottom = 8.dp)) {
                        BottomControls(attribution, onMyLocation, snackbarHostState)
                    }
                },
            ) { list, onFirstRowEnd ->
                SearchList(
                    search.state,
                    search.recents,
                    search.userLocation,
                    search.actions,
                    contentPadding = PaddingValues(bottom = 8.dp),
                    credits = search.credits,
                    now = search.now,
                    onFirstRowEnd = onFirstRowEnd,
                    listState = list,
                )
            }
        }
        if (pageSheet) {
            // The place sheet at the bottom edge, the credits and my location riding on it (hidden while
            // the sheet takes the whole screen), up to under the search bar.
            val insets = WindowInsets.safeDrawing.asPaddingValues()
            val underBar = insets.calculateTopPadding() + MapControlMargin + TopControlSize + 8.dp
            val screenWidth = maxWidth
            PlaceSheetWithControls(
                topSpace = underBar,
                bottomSpace = insets.calculateBottomPadding() + 8.dp,
                onMapBottom = { bottom -> report(DpRect(0.dp, roomTop, screenWidth, bottom)) },
                controls = {
                    if (!placePage.full) {
                        Box(Modifier.padding(horizontal = MapControlMargin).padding(bottom = 8.dp)) {
                            BottomControls(attribution, onMyLocation, snackbarHostState, snackbars = false)
                        }
                    }
                },
                sheet = { placePage.sheet(screenHeight - underBar, insets.calculateBottomPadding()) },
                // The sheet's own host, always laid out: a write's Undo or its failure shows at every stop.
                snackbars = { SnackbarHost(snackbarHostState, Modifier.padding(horizontal = MapControlMargin)) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (search != null && !wide && mode == SearchMode.Editing) {
            SearchPage(search)
        }
    }
}

/**
 * Where the map left in view starts, under a safe area of [safeTop]: under the search bar, or under
 * the notices below it when they are [bannersHeight] tall. Across the room's whole width, though the
 * notices are narrower on a wide screen: simpler than cutting out their shape, and it costs at most
 * their height.
 */
internal fun uncoveredMapTop(
    safeTop: Dp,
    bannersHeight: Dp,
): Dp {
    val underBar = safeTop + MapControlMargin + TopControlSize + 8.dp
    return if (bannersHeight > 0.dp) underBar + MapControlMargin + bannersHeight else underBar
}

/**
 * The map beside the place panel of a wide screen of [width] x [height]: from [top], past the
 * panel's side, above my location and the credits, inside the safe area.
 */
@Composable
private fun panelUncoveredMap(
    width: Dp,
    height: Dp,
    top: Dp,
    direction: LayoutDirection,
): DpRect {
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val left = insets.calculateLeftPadding(direction)
    val right = width - insets.calculateRightPadding(direction)
    val panelEdge = MapControlMargin + SidePanelWidth
    return DpRect(
        left = if (direction == LayoutDirection.Ltr) left + panelEdge else left,
        top = top,
        right = if (direction == LayoutDirection.Ltr) right else right - panelEdge,
        bottom = height - insets.calculateBottomPadding() - MapControlMargin - BottomControlsHeight,
    )
}

/**
 * The place sheet at the bottom with [controls] (credits, my location) riding on its top edge. As
 * with search's results sheet, the controls are left out (not drawn, not touchable) whenever they
 * would reach into [topSpace] under the search bar: at the full stop, and while the sheet is dragged
 * or animates that far. [snackbars] are always shown: above the controls while they ride on the
 * sheet, else at the bottom of the screen over the sheet, [bottomSpace] above the edge. Each time it
 * places them, [onMapBottom] is told where the map above them ends: the controls' top, else the sheet's.
 */
@Composable
internal fun PlaceSheetWithControls(
    topSpace: Dp,
    bottomSpace: Dp,
    controls: @Composable () -> Unit,
    sheet: @Composable () -> Unit,
    snackbars: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onMapBottom: (Dp) -> Unit = {},
) {
    Layout(contents = listOf(controls, sheet, snackbars), modifier = modifier) {
        (controlsMeasurables, sheetMeasurables, snackbarMeasurables),
        constraints,
        ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placedSheet = sheetMeasurables.first().measure(loose)
        val placedControls = controlsMeasurables.firstOrNull()?.measure(loose)
        val placedSnackbar = snackbarMeasurables.firstOrNull()?.measure(loose)
        layout(constraints.maxWidth, constraints.maxHeight) {
            val top = topSpace.roundToPx()
            val sheetTop = constraints.maxHeight - placedSheet.height
            placedSheet.place(0, sheetTop)
            val shownControls = placedControls?.takeIf { sheetTop - it.height >= top }
            shownControls?.place(0, sheetTop - shownControls.height)
            onMapBottom((sheetTop - (shownControls?.height ?: 0)).toDp())
            if (placedSnackbar != null) {
                val aboveControls = shownControls?.let { sheetTop - it.height - placedSnackbar.height }?.takeIf { it >= top }
                val y = aboveControls ?: (constraints.maxHeight - bottomSpace.roundToPx() - placedSnackbar.height)
                placedSnackbar.place((constraints.maxWidth - placedSnackbar.width) / 2, y)
            }
        }
    }
}

/**
 * My location at the end, and under it the data credits in the bottom start corner, on a line of
 * their own so they never run under the button; snackbars above both.
 */
@Composable
private fun BottomControls(
    attribution: List<AttributionLink>,
    onMyLocation: () -> Unit,
    snackbarHostState: SnackbarHostState,
    creditsStart: Dp = 0.dp,
    /** False where the screen shows its snackbars elsewhere (the place sheet has its own host). */
    snackbars: Boolean = true,
) {
    Column(Modifier.fillMaxWidth()) {
        if (snackbars) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = MapControlMargin),
            )
        }
        MapButton(
            icon = AppIcons.MyLocation,
            contentDescription = "Show my location",
            onClick = onMyLocation,
            size = 56.dp,
            tinted = true,
            modifier = Modifier.align(Alignment.End),
        )
        AttributionBar(links = attribution, modifier = Modifier.padding(top = 8.dp, start = creditsStart))
    }
}

/**
 * The map while a route guides the user, as Google Maps' and CoMaps' navigation: the next step's
 * banner on top (the missing parts of the app named under it), the time left and End at the bottom
 * over the credits; no search bar or place page. On a wide screen both keep a panel's width at the
 * start, as the place panel, so the map ahead of the user stays in view.
 */
@Composable
private fun GuidanceOverlay(
    guidance: GuidanceUi,
    attribution: List<AttributionLink>,
    missing: Set<ServicePart>,
    snackbarHostState: SnackbarHostState,
) {
    StatusBarScrim()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(MapControlMargin),
    ) {
        val wide = maxWidth >= SidePanelMinWidth
        val width = if (wide) Modifier.width(SidePanelWidth) else Modifier.fillMaxWidth()
        Column(Modifier.align(Alignment.TopStart).then(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GuidanceBanner(guidance)
            if (missing.isNotEmpty()) DegradedBanner(missing)
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = MapControlMargin),
            )
            GuidanceBar(guidance, width)
            AttributionBar(links = attribution, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** A soft shade under the status bar, so its icons stay readable over any map. */
@Composable
private fun StatusBarScrim() {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val shade = MaterialTheme.colorScheme.surface
    Box(
        Modifier
            .fillMaxWidth()
            .height(top + MapControlMargin)
            .background(Brush.verticalGradient(listOf(shade.copy(alpha = 0.7f), shade.copy(alpha = 0f)))),
    )
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
                credits = search.credits,
                now = search.now,
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
        color = MapSurfaces.control,
        border = MapSurfaces.edge,
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

/**
 * Says that no map style could load, with Retry beside the text when the text fits on one line beside
 * it (a tablet at the normal font), else under it at the end: beside a wrapped text, 'connection.'
 * would go alone on a second line on a phone, and at 200 % font the text would get three lines of two
 * words. See [NoteWithAction].
 */
@Composable
private fun MapUnavailableNotice(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MapSurfaces.notice,
        border = MapSurfaces.edge,
        shadowElevation = 3.dp,
    ) {
        NoteWithAction(
            note = {
                // The end padding keeps the text 16 dp from the notice's end edge, as from its start, when
                // it takes the whole width; beside Retry it is the gap between them. The same padding
                // above and below keeps the text centred on Retry's line when they share one.
                Text(
                    text = "The map could not load. Check your connection.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp, end = 8.dp),
                )
            },
            action = { TextButton(onClick = onRetry) { Text("Retry") } },
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        )
    }
}
