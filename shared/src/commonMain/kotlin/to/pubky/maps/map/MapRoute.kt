package to.pubky.maps.map

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.maplibre.compose.camera.CameraFit
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.LocationIndicatorLayer
import org.maplibre.compose.location.LocationMeasurement
import org.maplibre.compose.location.LocationPermission
import org.maplibre.compose.location.LocationState
import org.maplibre.compose.location.LocationTrackingStatus
import org.maplibre.compose.location.rememberDefaultLocationProvider
import org.maplibre.compose.location.rememberLocationState
import org.maplibre.compose.location.rememberSystemSettingsLauncher
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.overlay.DisappearingCompassButton
import org.maplibre.compose.overlay.DisappearingScaleBar
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import to.pubky.maps.config.ServerLists
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.LinkProvider
import to.pubky.maps.links.LinkedPlaceState
import to.pubky.maps.offline.AreaBounds
import to.pubky.maps.offline.OfflineMaps
import to.pubky.maps.offline.fitsInOneDownload
import to.pubky.maps.place.GeoBox
import to.pubky.maps.place.OpenInApps
import to.pubky.maps.place.OpeningLinkCard
import to.pubky.maps.place.PlaceCard
import to.pubky.maps.place.PlacePageActions
import to.pubky.maps.place.PlacePageContext
import to.pubky.maps.place.PlacePageController
import to.pubky.maps.place.PlacePageState
import to.pubky.maps.place.PlacePanel
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceRefSaver
import to.pubky.maps.place.PlaceServices
import to.pubky.maps.place.PlaceSheet
import to.pubky.maps.place.PlaceWriteActions
import to.pubky.maps.place.SheetStop
import to.pubky.maps.routing.DirectionsUi
import to.pubky.maps.routing.RouteStatus
import to.pubky.maps.search.RecentSearch
import to.pubky.maps.search.SearchActions
import to.pubky.maps.search.SearchArea
import to.pubky.maps.search.SearchMode
import to.pubky.maps.search.SearchServices
import to.pubky.maps.search.SearchState
import to.pubky.maps.search.SourceState
import to.pubky.maps.search.distanceMeters
import to.pubky.maps.search.distanceText
import to.pubky.maps.status.ServicePart
import to.pubky.maps.ui.SystemBack
import to.pubky.maps.ui.rememberSafeLinkOpener
import to.pubky.maps.write.OwnData
import to.pubky.maps.write.WriteController
import to.pubky.maps.write.WriteIntent
import to.pubky.maps.write.WriteMessages
import to.pubky.maps.write.WriteTarget
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.time.Clock

/** Where the map opens on a first start (no view stored yet): the whole world. */
private val WorldCamera = CameraPosition(center = Position(longitude = 0.0, latitude = 30.0), zoom = 1.0)

/** The zoom the map moves to when it shows the user's location. */
private const val MY_LOCATION_ZOOM = 15.0

/** The zoom a place from a link is shown at when its link has none (a street, its buildings). */
private const val PLACE_ZOOM = 16.0

/**
 * The live map: the first style of the server list for the current theme that loads, the user's
 * location on request, search ([search], null where it is not available), and the [MapScreen]
 * controls, with the [missing] parts of the app named on top and the place a link opened
 * ([linkedPlace]) pinned, centred and shown in a card.
 */
@Composable
fun MapRoute(
    servers: ServerLists,
    onOpenSettings: () -> Unit,
    missing: Set<ServicePart> = emptySet(),
    linkedPlace: LinkedPlaceState = LinkedPlaceState(),
    onSharePlace: ((PlaceRef) -> Unit)? = null,
    onClosePlace: () -> Unit = {},
    linkMessages: Flow<String> = emptyFlow(),
    search: SearchServices? = null,
    places: PlaceServices? = null,
    openIn: OpenInApps? = null,
    /** The user's writes about places; null where there are none. */
    writes: WriteController? = null,
    /** Opens a write page (tag, post, photo, lists) for a place, or the sign-in first. */
    onWrite: (WriteTarget, WriteIntent) -> Unit = { _, _ -> },
    /** Opens the Saved screen; null where there is none. */
    onOpenSaved: (() -> Unit)? = null,
    /** Offline maps: the map shows the area picker while they ask for one; null where there are none. */
    offline: OfflineMaps? = null,
    /** Shows the offline maps once a download started from the picker. */
    onDownloadStarted: () -> Unit = {},
    /** Called once the map has drawn its first whole view of a style from the list. */
    onMapDrawn: () -> Unit = {},
    /** Where the map opens: where the user left it at the last start; null for the whole world. */
    startView: MapView? = null,
    /** Called with each view the camera rests at, and the view when the map leaves the screen, to open there at the next start. */
    onViewRested: (MapView) -> Unit = {},
) {
    val dark = isSystemInDarkTheme()
    val sources = servers.mapStyles(dark)
    // Saved, so a trip to Settings does not start again from a style that already failed.
    var fallbackIndex by rememberSaveable(sources) { mutableIntStateOf(0) }
    val fallback = StyleFallback(sources, fallbackIndex)
    val locationState = rememberLocationState(provider = rememberDefaultLocationProvider())
    // A point of interest or Pubky place tapped on the map; kept on rotation.
    var tapped by rememberSaveable(stateSaver = PlaceRefSaver) { mutableStateOf<PlaceRef?>(null) }
    // Only a place is pinned: a map view or a search near a point only moves the camera.
    val pinnedPoint by rememberUpdatedState(linkedPlace.place?.takeIf { it.pinned }?.point ?: tapped?.point)
    val layerState =
        places
            ?.layer
            ?.state
            ?.collectAsStateWithLifecycle()
            ?.value
    val pubkyPlaces by rememberUpdatedState(layerState?.takeUnless { it.hidden }?.places.orEmpty())
    val saved =
        writes
            ?.own
            ?.collectAsStateWithLifecycle()
            ?.value
            ?.saved
            .orEmpty()
    val savedPins by rememberUpdatedState(remember(saved) { savedPins(saved) })
    var clusterTarget by remember { mutableStateOf<Position?>(null) }
    val scope = rememberCoroutineScope()
    val searchState =
        search
            ?.controller
            ?.state
            ?.collectAsStateWithLifecycle()
            ?.value
    val recents =
        search
            ?.recents
            ?.entries
            ?.collectAsStateWithLifecycle()
            ?.value
            .orEmpty()
    val directions = places?.directions
    val directionsState =
        directions
            ?.directions
            ?.collectAsStateWithLifecycle()
            ?.value
    val foundRoute = directionsState?.status as? RouteStatus.Found
    val routePoints by rememberUpdatedState(foundRoute?.route?.points)
    // Pins while search is open; the picked result also when its card shows.
    val searchPins by rememberUpdatedState(searchState?.takeIf { it.mode != SearchMode.Idle })
    val mapState =
        rememberMapState(
            baseStyle = fallback.current?.let { BaseStyle.Uri(it.url) } ?: BaseStyle.Empty,
            // Read once: later views are kept by the map's own saved state (rotation, Settings).
            initialCameraPosition = remember { startView?.toCameraPosition() ?: WorldCamera },
        ) {
            // Under the location dot and every pin.
            routePoints?.let { RouteLine(it) }
            LocationIndicatorLayer(id = "user-location", locationState = locationState)
            if (pubkyPlaces.isNotEmpty()) {
                MapPlacesPins(
                    pubkyPlaces,
                    onSelect = { place ->
                        search?.controller?.close()
                        onClosePlace()
                        tapped = place.toPlaceRef()
                    },
                    onCluster = { clusterTarget = it },
                )
            }
            // Above the Pubky places, under the opened place's pin (drawn on a saved place when it is opened).
            if (savedPins.isNotEmpty()) {
                SavedPins(
                    savedPins,
                    onSelect = { entry ->
                        search?.controller?.close()
                        onClosePlace()
                        tapped = entry.toPlaceRef()
                    },
                )
            }
            pinnedPoint?.let { PlacePin(it) }
            searchPins?.let { pins ->
                if (search != null) SearchPins(pins.results, pins.selected, onSelect = search.controller::select)
            }
        }

    // A style that fails to load hands over to the next one in the list.
    LaunchedEffect(mapState, sources) {
        snapshotFlow { mapState.style.loadState to mapState.style.baseStyle }
            .collect { (loadState, style) ->
                if (loadState is StyleLoadState.Failed && style is BaseStyle.Uri) {
                    fallbackIndex = StyleFallback(sources, fallbackIndex).failed(style.uri).index
                }
            }
    }

    // The first whole view of the map, for the start time (once per map: Android takes the first
    // report of an activity and ignores the later ones, e.g. back from Settings). A start that leaves
    // the map before it drew (a link to another page) reports when the user first comes back to it,
    // so such a start counts more than its map.
    val mapDrawn by rememberUpdatedState(onMapDrawn)
    LaunchedEffect(mapState) {
        val styleReady =
            snapshotFlow { mapState.style.loadState is StyleLoadState.Ready && mapState.style.baseStyle is BaseStyle.Uri }
        if (awaitMapDrawn(mapState.events, styleReady)) mapDrawn()
    }

    // The next start opens where the camera last rested, or where the user had moved it when the map
    // left the screen.
    val viewRested by rememberUpdatedState(onViewRested)
    LaunchedEffect(mapState) {
        val cameras =
            snapshotFlow {
                CameraReport(mapState.cameraPosition, mapState.isCameraMoving, mapState.cameraMoveReason == CameraMoveReason.Gesture)
            }
        saveRestingViews(cameras) { viewRested(it) }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val myLocation = rememberMyLocationAction(locationState, mapState, snackbarHostState)

    val place = linkedPlace.place
    LaunchedEffect(linkMessages) { linkMessages.collect { snackbarHostState.showOnly(it) } }
    // A write that could not be made, or that waits for a connection, says so.
    if (writes != null) WriteMessages(writes, snackbarHostState)
    // A place from a link or a search replaces a tapped one.
    LaunchedEffect(linkedPlace.placedAt, linkedPlace.opening) { if (linkedPlace.opening || linkedPlace.place != null) tapped = null }
    LaunchedEffect(clusterTarget) {
        val target = clusterTarget ?: return@LaunchedEffect
        clusterTarget = null
        mapState.animateCamera(CameraUpdate(center = target, zoom = mapState.cameraPosition.zoom + 2))
    }
    // The Pubky places layer asks Pinakes when the camera rests.
    // Held while the page of typed coordinates is open and the user has not moved the map (see layerMayAsk).
    val layerHeld = remember { mutableStateOf(false) }
    if (places != null) {
        LaunchedEffect(mapState, places) {
            snapshotFlow { if (mapState.isCameraMoving) null else mapState.viewport }
                .filterNotNull()
                .collectLatest { viewport ->
                    delay(CAMERA_REST_MS)
                    if (layerHeld.value) return@collectLatest
                    val view =
                        viewBox(
                            viewport.visibleBounds.west,
                            viewport.visibleBounds.south,
                            viewport.visibleBounds.east,
                            viewport.visibleBounds.north,
                        )
                    // A view across the antimeridian is left out (no box can say it to Pinakes).
                    if (view != null) places.layer.onCameraIdle(view, viewport.cameraPosition.zoom)
                }
        }
    }

    val mapSearch =
        if (search != null && searchState != null) {
            rememberMapSearch(search, searchState, recents, mapState, locationState, onClosePlace, onOpenSaved)
                .copy(
                    // Places from the offline maps are the map's own data: its credits name them.
                    credits =
                        if (searchState.offlineMaps ==
                            SourceState.Answered
                        ) {
                            servers.mapAttribution()
                        } else {
                            servers.searchAttribution()
                        },
                )
        } else {
            null
        }
    val selected = searchState?.selected?.takeIf { searchState.mode == SearchMode.Selected }
    // Searching closes a tapped place.
    val searchOpen = searchState != null && searchState.mode != SearchMode.Idle
    LaunchedEffect(searchOpen) { if (searchOpen) tapped = null }

    // The place page: a search pick, else a place a link opened, else a tapped one.
    val shown: ShownPlace? =
        when {
            places == null -> null
            selected != null -> ShownPlace(selected.place, SheetStop.Half, search.controller::closeCard)
            linkedPlace.opening -> null
            place != null && !place.viewOnly -> ShownPlace(place, SheetStop.Half, onClosePlace, linkedPlace.provider)
            tapped != null -> tapped?.let { ShownPlace(it, SheetStop.Peek, onClose = { tapped = null }) }
            else -> null
        }
    var movedSinceShown by remember(shown?.place?.uri) { mutableStateOf(false) }
    LaunchedEffect(shown?.place?.uri) {
        // The first value is the reason of the move that is already over.
        snapshotFlow { mapState.cameraMoveReason }.drop(1).collect { if (it == CameraMoveReason.Gesture) movedSinceShown = true }
    }
    val held = !layerMayAsk(shown?.place?.source, movedSinceShown)
    SideEffect { layerHeld.value = held }
    // The map that a place page leaves in view (MapScreen reports it), with the place it was shown
    // for: a room measured for the place shown before is not this one's.
    var uncoveredMap by remember { mutableStateOf<Pair<String, DpRect>?>(null) }

    fun roomsOf(uri: String): Flow<DpRect?> = snapshotFlow { uncoveredMap?.takeIf { it.first == uri }?.second }

    // Directions belong to the place they were opened on: another place, or none, closes them.
    val shownUri = shown?.place?.uri
    LaunchedEffect(directions, shownUri) {
        val open = directions?.directions?.value ?: return@LaunchedEffect
        if (open.placeUri != shownUri) directions.close()
    }
    // A found route is fitted into the map its place page leaves in view, once per route
    // (Directions.routeToFit): coming back from Settings or a recreated activity (dark mode, language)
    // keeps the user's pan.
    LaunchedEffect(foundRoute) {
        val open = directionsState ?: return@LaunchedEffect
        val route = open.routeToFit?.route ?: return@LaunchedEffect
        directions.routeFitted(route)
        val room = uncoveredMap?.takeIf { it.first == open.placeUri }?.second
        mapState.animateCameraToBounds(
            BoundingBox(
                west = route.points.minOf { it.lon },
                south = route.points.minOf { it.lat },
                east = route.points.maxOf { it.lon },
                north = route.points.maxOf { it.lat },
            ),
            CameraFit(fitPadding = routeFitPadding(room, mapState.viewport?.size)),
        )
    }
    val startDirections = directions?.let { rememberStartDirections(it, locationState) }
    val settingsLauncher = rememberSystemSettingsLauncher()
    val directionsUi =
        if (directions != null && directionsState != null && startDirections != null) {
            DirectionsUi(
                directions = directionsState,
                onMode = directions::selectMode,
                onClose = directions::close,
                onRetry = {
                    if (directionsState.from == null) {
                        startDirections(directionsState.placeUri, directionsState.to)
                    } else {
                        directions.retry()
                    }
                },
                onOpenSettings =
                    if (settingsLauncher.canOpenApplicationSettings) {
                        (
                            {
                                settingsLauncher.openApplicationSettings()
                            }
                        )
                    } else {
                        null
                    },
                credits = routeCredits(servers, foundRoute?.server),
            )
        } else {
            null
        }

    // A place opened from a link: the map moves to it once per opening, into the middle of the map
    // its page leaves in view. Saved, so returning from Settings or recreating the activity keeps
    // where the user has moved the map since.
    var centredAt by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(linkedPlace.placedAt) {
        if (linkedPlace.placedAt == centredAt) return@LaunchedEffect
        centredAt = linkedPlace.placedAt
        val point = place?.point ?: return@LaunchedEffect
        mapState.moveToPlace(point, place.zoom ?: PLACE_ZOOM, rooms = roomsOf(place.uri).takeIf { shown?.place?.uri == place.uri })
    }
    // A picked search result: the map moves to it, the same way, once per pick (SearchState.pickToMoveTo):
    // going back to its card or coming back from Settings keeps where the user has moved the map since.
    // Leaving the card (Back, the bar, a pin, a link) stops a move still waiting for the page's room.
    // A move cut short (the card left, Settings opened or the screen rotated during the wait or the
    // animation) counts as done: moving later, when the user may have moved the map, would undo that.
    LaunchedEffect(searchState?.pickToMoveTo) {
        val pick = searchState?.pickToMoveTo ?: return@LaunchedEffect
        val picked = selected?.place ?: return@LaunchedEffect
        try {
            val point = picked.point ?: return@LaunchedEffect
            mapState.moveToPlace(point, picked.zoom ?: PLACE_ZOOM, rooms = roomsOf(picked.uri).takeIf { shown?.place?.uri == picked.uri })
        } finally {
            search.controller.mapMoved(pick)
        }
    }
    // A place tapped on the map opens its sheet or panel over the map, maybe over the place: once the
    // sheet has opened, a place it hides moves to the middle of the map left in view, as in Google
    // Maps (keepTappedInView). Once per tap and map size (saved, see keptInViewKey); not after the user
    // moved the map or the sheet. Search picks and links move the map themselves (moveToPlace).
    var keptInView by rememberSaveable { mutableStateOf<String?>(null) }
    // The user moved the open place's sheet (a drag, a tap on its handle) since it was tapped.
    var sheetMoved by remember { mutableStateOf(false) }
    LaunchedEffect(tapped == null) { if (tapped == null) keptInView = null }
    val tappedShown = tapped?.takeIf { shown?.place?.uri == it.uri }
    LaunchedEffect(tappedShown) {
        val place = tappedShown ?: return@LaunchedEffect
        val point = place.point ?: return@LaunchedEffect
        val pages = places?.pages ?: return@LaunchedEffect
        sheetMoved = false
        keepTappedInView(
            uri = place.uri,
            point = point,
            rooms = roomsOf(place.uri),
            pageDone = pages.state.map { page -> page?.place?.uri == place.uri && page.loading.isEmpty() },
            userMoved =
                snapshotFlow { sheetMoved || (mapState.isCameraMoving && mapState.cameraMoveReason == CameraMoveReason.Gesture) },
            map = mapState.keepInViewMap(),
            kept = { keptInView },
            onKept = { keptInView = it },
        )
    }
    val placePage =
        places?.let {
            rememberPlacePage(it, shown, mapSearch?.userLocation, onSharePlace, openIn, writes, onWrite, startDirections, directionsUi) {
                sheetMoved = true
            }
        }
    // The area on screen goes offline in the style on screen and the other theme's of the same server.
    val areaPicker =
        offline?.let {
            rememberAreaPicker(
                offline = it,
                mapState = mapState,
                styleUrls = listOfNotNull(fallback.current?.url, fallback.otherTheme(servers.mapStyles(!dark))?.url),
                onDownloadStarted = onDownloadStarted,
            )
        }
    LaunchedEffect(areaPicker != null) {
        if (areaPicker == null) return@LaunchedEffect
        search?.controller?.close()
        onClosePlace()
        tapped = null
    }

    MapScreen(
        attribution = (fallback.current ?: sources.first()).attribution,
        mapUnavailable = fallback.allFailed,
        onRetryMap = { fallbackIndex = fallback.retry().index },
        onMyLocation = myLocation,
        onOpenSettings = onOpenSettings,
        missing = missing,
        snackbarHostState = snackbarHostState,
        search = mapSearch,
        placePage = placePage,
        areaPicker = areaPicker,
        onUncoveredMap = { room -> uncoveredMap = room?.let { r -> shown?.place?.uri?.let { it to r } } },
        placeCard =
            when {
                placePage != null -> {
                    null
                }

                selected != null -> {
                    {
                        PlaceCard(
                            place = selected.place,
                            provider = null,
                            onShare = onSharePlace?.let { share -> { share(selected.place) } },
                            onClose = search.controller::closeCard,
                            distance = distanceText(mapSearch?.userLocation, selected.place.point),
                        )
                    }
                }

                linkedPlace.opening -> {
                    { OpeningLinkCard() }
                }

                place != null && !place.viewOnly -> {
                    {
                        PlaceCard(
                            place = place,
                            provider = linkedPlace.provider,
                            onShare = onSharePlace?.let { share -> { share(place) } },
                            onClose = onClosePlace,
                        )
                    }
                }

                else -> {
                    null
                }
            },
    ) {
        MaplibreMap(
            state = mapState,
            interactions =
                remember(mapState, places) {
                    MapInteractions {
                        callbacks {
                            click {
                                onUnhandled { event ->
                                    if (places != null) {
                                        scope.launch {
                                            val poi = mapState.tappedPoi(event.screenOffset) ?: return@launch
                                            search?.controller?.close()
                                            onClosePlace()
                                            tapped = poi
                                        }
                                    }
                                    ClickResult.Pass
                                }
                            }
                        }
                    }
                },
        ) {
            // Under the search bar, or under the area picker's bar while it is open. No parent consumes
            // insets, so this inset is the one safeDrawingPadding applies below and the picker's bar
            // is placed under.
            val controlsTop = mapTopControlsPadding(areaPicker?.frame, WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding())
            Box(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(MapControlMargin),
            ) {
                DisappearingScaleBar(
                    metersPerDp = { mapState.viewport?.metersPerDpAtCenter ?: 0.0 },
                    zoom = { mapState.cameraPosition.zoom },
                    modifier = Modifier.align(Alignment.TopStart).padding(top = controlsTop),
                )
                DisappearingCompassButton(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = controlsTop),
                    size = TopControlSize,
                )
            }
        }
    }
}

/**
 * The area picker on the live map while [offline] asks for an area: the frame's area is measured
 * whenever the camera rests (and unknown while it moves, so Download never takes an area the frame
 * has left), back closes the picker, Download starts the download in [styleUrls], named after the
 * town the map labels in the frame.
 */
@Composable
private fun rememberAreaPicker(
    offline: OfflineMaps,
    mapState: MapState,
    styleUrls: List<String>,
    onDownloadStarted: () -> Unit,
): AreaPickerUi? {
    val scope = rememberCoroutineScope()
    val picking by offline.picking.collectAsStateWithLifecycle()
    SystemBack(enabled = picking) { offline.stopPicking() }
    if (!picking) return null
    var frame by remember { mutableStateOf<DpRect?>(null) }
    // Download was tapped and the labels are being read: a second tap starts nothing.
    var naming by remember { mutableStateOf(false) }
    val area by produceState<AreaBounds?>(null, mapState) {
        // The viewport changes with every camera move and arrives once the map (attached again on
        // the way back from Settings) can convert screen points.
        snapshotFlow { if (mapState.isCameraMoving) null else mapState.viewport?.let { it to frame } }
            .collect { resting -> value = resting?.second?.let { mapState.areaIn(it) } }
    }
    return AreaPickerUi(
        fits = area?.let { fitsInOneDownload(it) },
        frame = frame,
        onFrame = { frame = it },
        onDownload = {
            val bounds = area?.takeIf { fitsInOneDownload(it) }
            val shownFrame = frame
            if (bounds != null && shownFrame != null && !naming) {
                naming = true
                scope.launch {
                    try {
                        // The labels the map draws now, read before the picker closes.
                        val place = mapState.placeNameIn(shownFrame)
                        // Back or Cancel while they were read: the pick is over, nothing downloads.
                        if (!offline.picking.value) return@launch
                        offline.download(bounds, styleUrls, place = place)
                        onDownloadStarted()
                    } finally {
                        naming = false
                    }
                }
            }
        },
        onCancel = offline::stopPicking,
    )
}

/**
 * The name of the most important town the map labels in [frame] ([areaPlaceName]), counting only
 * places whose point is in the frame; null without one.
 */
private suspend fun MapState.placeNameIn(frame: DpRect): String? {
    val layers =
        style.layers
            .filter { it.sourceLayer in PlaceLabelSourceLayers }
            .map { it.id }
            .toSet()
    if (layers.isEmpty()) return null
    val features =
        try {
            queryRenderedFeatures(frame, layers)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A map that cannot answer (its style changing) names the area after its centre.
            return null
        }
    val centreX = (frame.left + frame.right) / 2
    val centreY = (frame.top + frame.bottom) / 2
    val labels =
        features.mapNotNull { feature ->
            val at = (feature.geometry as? Point)?.coordinates?.let { screenLocationFromPosition(it) }
            // A label that only reaches into the frame is of a place outside the area.
            if (at != null && (at.x < frame.left || at.x > frame.right || at.y < frame.top || at.y > frame.bottom)) return@mapNotNull null
            PlaceLabel(
                properties = feature.properties.strings(),
                distance = at?.let { hypot((it.x - centreX).value, (it.y - centreY).value).toDouble() } ?: Double.MAX_VALUE,
            )
        }
    return areaPlaceName(labels)
}

/** The box around the map under [frame]; null while the map cannot tell. */
private fun MapState.areaIn(frame: DpRect): AreaBounds? =
    runCatching<AreaBounds?> {
        val corners =
            listOf(
                DpOffset(frame.left, frame.top),
                DpOffset(frame.right, frame.top),
                DpOffset(frame.left, frame.bottom),
                DpOffset(frame.right, frame.bottom),
            ).map { positionFromScreenLocation(it) ?: return null }
        AreaBounds(
            west = corners.minOf { it.longitude },
            south = corners.minOf { it.latitude },
            east = corners.maxOf { it.longitude },
            north = corners.maxOf { it.latitude },
        )
    }.getOrNull()

/**
 * Search on the live map: asks around the map's centre, fits the map to the results of a search
 * (MapRoute moves it to a picked one), and takes the back action while search is open.
 */
@Composable
private fun rememberMapSearch(
    search: SearchServices,
    state: SearchState,
    recents: List<RecentSearch>,
    mapState: MapState,
    locationState: LocationState,
    onCloseLinkedPlace: () -> Unit,
    onOpenSaved: (() -> Unit)?,
): MapSearch {
    val controller = search.controller

    SystemBack(enabled = state.mode != SearchMode.Idle) { controller.back() }

    // Search takes the bottom of the screen: a place opened from a link makes way.
    val searching = state.mode != SearchMode.Idle
    LaunchedEffect(searching) { if (searching) onCloseLinkedPlace() }
    // The results of a search: the map shows those near the area searched, once per search
    // (SearchState.resultsToFit: not again when a slower source adds results, back from a place or
    // from Settings, after the user moved the map). Keyed on the shown results, not on resultsToFit,
    // so marking the fit done does not cancel its animation.
    val shownResults = state.results.takeIf { state.mode == SearchMode.Results && state.resultsAreCurrent }
    LaunchedEffect(shownResults) {
        val points = state.resultsToFit?.mapNotNull { it.place.point } ?: return@LaunchedEffect
        controller.resultsFitted(state.resultsQuery)
        val area = mapState.searchArea()
        // The results near the area searched; when none is (the map was elsewhere), those near the best one.
        val near =
            points.filter { distanceMeters(area.centre, it) <= area.pinakesRadiusMeters }.ifEmpty {
                points.filter { distanceMeters(points.first(), it) <= area.pinakesRadiusMeters }
            }
        // A lone result gets a small box around it, so it too lands clear of the sheet or the panel (the
        // fit padding is for this move only; camera padding would stay for every later move).
        val margin = if (near.size == 1) LONE_RESULT_MARGIN_DEGREES else 0.0
        mapState.animateCameraToBounds(
            BoundingBox(
                west = near.minOf { it.lon } - margin,
                south = near.minOf { it.lat } - margin,
                east = near.maxOf { it.lon } + margin,
                north = near.maxOf { it.lat } + margin,
            ),
            CameraFit(fitPadding = resultsFitPadding(mapState.viewport?.size)),
        )
    }

    val userLocation = locationState.lastLocation?.position?.let { GeoPoint(it.latitude, it.longitude) }
    val actions =
        remember(controller, search.recents, mapState, onOpenSaved) {
            SearchActions(
                onOpenSaved = onOpenSaved,
                onRemoveRecent = search.recents::remove,
                onStartEditing = controller::startEditing,
                onQueryChange = { controller.onQueryChange(it, mapState.searchArea()) },
                onSubmit = { controller.submit(mapState.searchArea()) },
                onSelect = controller::select,
                onSelectRecent = { controller.selectRecent(it, mapState.searchArea()) },
                onClearQuery = controller::clearQuery,
                onBack = { controller.back() },
            )
        }
    // The clock ticks only while a row has hours to say open or closed by.
    val now =
        if (state.results.any { it.openingHours != null }) {
            val instant by produceState(Clock.System.now()) {
                while (true) {
                    delay(CLOCK_TICK_MS)
                    value = Clock.System.now()
                }
            }
            instant.toLocalDateTime(TimeZone.currentSystemDefault())
        } else {
            null
        }
    return MapSearch(state = state, recents = recents, userLocation = userLocation, actions = actions, now = now)
}

/** Under the search bar, plus a pin's height: pins stand above their points. */
private val RESULTS_FIT_TOP = 136.dp

/** About 100 m: the box a lone result is shown in, a street's worth around it. */
private const val LONE_RESULT_MARGIN_DEGREES = 0.001

/**
 * The room around fitted results: beside the panel on a wide screen, above the half-open results
 * sheet on a phone, and under the search bar on both.
 */
private fun resultsFitPadding(size: DpSize?): DpPadding =
    if (size != null && size.width >= SidePanelMinWidth) {
        DpPadding(left = SidePanelWidth + 48.dp, top = RESULTS_FIT_TOP, right = 48.dp, bottom = 120.dp)
    } else {
        // Half the screen for the sheet, plus my location and the credits riding on its edge.
        DpPadding(left = 48.dp, top = RESULTS_FIT_TOP, right = 48.dp, bottom = (size?.height ?: 800.dp) / 2 + 120.dp)
    }

/** How long the camera must rest before the Pubky places layer asks for its area. */
private const val CAMERA_REST_MS = 400L

/** How far from a tap a point of interest still counts as tapped. */
private val TAP_SLOP = 12.dp

/** The place page open on the map, the stop its sheet opens at, and how it closes. */
private class ShownPlace(
    val place: PlaceRef,
    val initialStop: SheetStop,
    val onClose: () -> Unit,
    val provider: LinkProvider? = null,
)

/** The base map's point of interest at [offset] (its OSM element), or null. */
private suspend fun MapState.tappedPoi(offset: DpOffset): PlaceRef? {
    val layers =
        style.layers
            .filter { it.sourceLayer in PoiSourceLayers }
            .map { it.id }
            .toSet()
    if (layers.isEmpty()) return null
    val area = DpRect(offset.x - TAP_SLOP, offset.y - TAP_SLOP, offset.x + TAP_SLOP, offset.y + TAP_SLOP)
    return queryRenderedFeatures(area, layers).firstNotNullOfOrNull { feature ->
        val point = (feature.geometry as? Point)?.coordinates ?: positionFromScreenLocation(offset) ?: return@firstNotNullOfOrNull null
        tappedPlace(
            id = feature.id?.contentOrNull?.toLongOrNull(),
            properties = feature.properties.strings(),
            point = GeoPoint(point.latitude.coerceIn(-90.0, 90.0), ((point.longitude + 540.0) % 360.0) - 180.0),
        )
    }
}

/** A tile feature's properties that are text or numbers, as text. */
private fun JsonObject?.strings(): Map<String, String> =
    this
        ?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }
        ?.toMap()
        .orEmpty()

/**
 * The place page of [shown] for the map screen: its state from [places], the sheet's stop (saved per
 * place; [onSheetMoved] when the user moves the sheet), the clock, and its actions. Null when no place is open.
 */
@Composable
private fun rememberPlacePage(
    places: PlaceServices,
    shown: ShownPlace?,
    userLocation: GeoPoint?,
    onSharePlace: ((PlaceRef) -> Unit)?,
    openIn: OpenInApps?,
    writes: WriteController?,
    onWrite: (WriteTarget, WriteIntent) -> Unit,
    startDirections: ((String, GeoPoint) -> Unit)?,
    directions: DirectionsUi?,
    onSheetMoved: () -> Unit,
): PlacePageSlot? {
    LaunchedEffect(shown?.place) {
        val place = shown?.place
        if (place != null) places.pages.open(place) else places.pages.close()
    }
    val pageState by places.pages.state.collectAsStateWithLifecycle()
    val own = writes?.own?.collectAsStateWithLifecycle()?.value
    val publicTagsExplained = writes?.publicTagsExplained?.collectAsStateWithLifecycle()?.value ?: true
    var stop by rememberSaveable(shown?.place?.uri) { mutableStateOf(shown?.initialStop ?: SheetStop.Peek) }
    val now by produceState(Clock.System.now()) {
        while (true) {
            delay(CLOCK_TICK_MS)
            value = Clock.System.now()
        }
    }
    if (shown == null) return null
    SystemBack { shown.onClose() }
    val page = pageState?.takeIf { it.place.uri == shown.place.uri } ?: PlacePageState(shown.place)
    // Built once per place and point (it asks the core and the package manager), not per recomposition.
    val openInPlace = page.place.copy(point = page.place.point ?: page.card?.point)
    val openInActions =
        remember(openIn, openInPlace.uri, openInPlace.point, openInPlace.name) { openIn?.actions?.invoke(openInPlace).orEmpty() }
    val zone = TimeZone.currentSystemDefault()
    val context =
        PlacePageContext(
            now = now.toLocalDateTime(zone),
            nowMillis = now.toEpochMilliseconds(),
            timeZone = zone,
            language = Locale.current.language,
            distance = distanceText(userLocation, shown.place.point ?: page.card?.point),
            provider = shown.provider,
        )
    if (writes != null && own != null) RememberOwnPlace(page, own, writes)
    val actions =
        PlacePageActions(
            onClose = shown.onClose,
            onShare = onSharePlace?.let { share -> { share(shown.place) } },
            openIn = openInActions,
            onOpenIn = { openIn?.open?.invoke(it) },
            onOpenLink = rememberSafeLinkOpener(),
            onLoadMorePosts = places.pages::loadMorePosts,
            writes = if (writes != null && own != null) placeWriteActions(page, own, writes, onWrite, publicTagsExplained) else null,
            onDirections =
                (page.place.point ?: page.card?.point)?.let { to ->
                    startDirections?.let { start -> { start(shown.place.uri, to) } }
                },
            directions = directions?.takeIf { it.directions.placeUri == shown.place.uri },
        )
    return PlacePageSlot(
        full = stop == SheetStop.Full,
        sheet = { maxHeight, inset ->
            PlaceSheet(
                page,
                context,
                actions,
                stop,
                {
                    stop = it
                    onSheetMoved()
                },
                maxHeight,
                bottomInset = inset,
                onDragStart = onSheetMoved,
            )
        },
        panel = { modifier -> PlacePanel(page, context, actions, modifier) },
    )
}

/**
 * Where the page's place is, kept with a save for its pin on the map: the place's own point (not a map
 * view or a search centre), else its Pinakes card's.
 */
internal val PlacePageState.placePoint: GeoPoint? get() = place.point?.takeIf { place.pinned } ?: card?.point

/** The page's place as the user's writes name it: its link, its name, where it is. */
private val PlacePageState.writeTarget: WriteTarget get() = WriteTarget(place.uri, card?.name ?: place.name, placePoint)

/** Every link of [page]'s place, spelled as the core stores places: the keys of the user's own data about it. */
private fun ownLinks(
    page: PlacePageState,
    writes: WriteController,
): Set<String> = PlacePageController.placeLinks(page.place, page.card).map(writes::normalize).toSet()

/**
 * A place the user saved or tagged keeps the name and point its page shows, when the phone has none
 * for it ([WriteController.remember] decides; the first name kept stays); asked again when the page
 * or the user's entries for it change, so a page with no name before its card loads keeps the card's.
 */
@Composable
private fun RememberOwnPlace(
    page: PlacePageState,
    own: OwnData,
    writes: WriteController,
) {
    val target = page.writeTarget
    val links = ownLinks(page, writes)
    val mine = own.forPlace(links)
    LaunchedEffect(writes, target, links, mine.saved, mine.tags) { writes.remember(target, links) }
}

/** The place page's writes: what the user has about [page]'s place (by every link of it) and what they can do. */
internal fun placeWriteActions(
    page: PlacePageState,
    own: OwnData,
    writes: WriteController,
    onWrite: (WriteTarget, WriteIntent) -> Unit,
    publicTagsExplained: Boolean,
): PlaceWriteActions {
    val links = ownLinks(page, writes)
    val ownPlace = own.forPlace(links)
    val target = page.writeTarget

    fun open(intent: WriteIntent) = if (writes.needsSignIn(intent)) writes.signInFirst(target, intent) else onWrite(target, intent)
    return PlaceWriteActions(
        own = ownPlace,
        viewer = own.viewer,
        onToggleSave = {
            when {
                ownPlace.isSaved -> writes.unsave(target, links)
                writes.needsSignIn(WriteIntent.Save) -> writes.signInFirst(target, WriteIntent.Save)
                else -> writes.save(target)
            }
        },
        onTag = { open(WriteIntent.Tag) },
        onToggleTag = { label ->
            when {
                writes.needsSignIn(WriteIntent.Tag) -> {
                    writes.signInFirst(target, WriteIntent.Tag)
                }

                ownPlace.labels?.contains(label) == true || (ownPlace.labels == null && page.tags.any { it.label == label && it.mine }) -> {
                    writes.untag(target, links, label)
                }

                else -> {
                    writes.tag(target, label)
                }
            }
        },
        onPost = { open(WriteIntent.Post) },
        onPhoto = { open(WriteIntent.Photo) },
        onAddToList = { open(WriteIntent.AddToList) },
        publicTagsExplained = publicTagsExplained,
        onPublicTagsExplained = writes::markPublicTagsExplained,
        waiting = own.offline,
        busy = own.busy,
    )
}

/** The place page's clock moves on once a minute (open now, post ages). */
private const val CLOCK_TICK_MS = 60_000L

/** A phone screen's half diagonal in dp, for a map whose size is not known yet. */
private const val FALLBACK_HALF_DIAGONAL_DP = 500.0

/** The equator's length in metres over a 512 px tile: metres per dp at zoom 0 and latitude 0. */
private const val METERS_PER_DP_AT_ZOOM_0 = 40_075_016.686 / 512

/** The area of the map on screen, for search. */
internal fun MapState.searchArea(): SearchArea {
    val camera = cameraPosition
    val centre = GeoPoint(camera.center.latitude.coerceIn(-90.0, 90.0), camera.center.longitude.coerceIn(-180.0, 180.0))
    val viewport = viewport
    val halfDiagonal =
        if (viewport != null && viewport.metersPerDpAtCenter > 0) {
            val size = viewport.size
            viewport.metersPerDpAtCenter * hypot(size.width.value.toDouble(), size.height.value.toDouble()) / 2
        } else {
            METERS_PER_DP_AT_ZOOM_0 * cos(centre.lat * PI / 180) / 2.0.pow(camera.zoom) * FALLBACK_HALF_DIAGONAL_DP
        }
    return SearchArea(centre = centre, zoom = camera.zoom, halfDiagonalMeters = halfDiagonal)
}

/** Shows [message], replacing the snackbar on screen instead of queueing behind it. */
private suspend fun SnackbarHostState.showOnly(
    message: String,
    actionLabel: String? = null,
): SnackbarResult {
    currentSnackbarData?.dismiss()
    return showSnackbar(message = message, actionLabel = actionLabel, duration = SnackbarDuration.Long)
}

/**
 * What the my-location button does: ask for the location permission only now, then move the map to
 * the user's position as soon as it is known. Says what is wrong when the permission is refused for
 * good or location is turned off. A pending request ends when the user moves the map.
 */
@Composable
private fun rememberMyLocationAction(
    locationState: LocationState,
    mapState: MapState,
    snackbarHostState: SnackbarHostState,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val settings = rememberSystemSettingsLauncher()
    var centerOnNextFix by remember { mutableStateOf(false) }

    fun moveTo(location: LocationMeasurement) {
        scope.launch {
            val zoom = max(mapState.cameraPosition.zoom, MY_LOCATION_ZOOM)
            mapState.animateCamera(CameraUpdate(center = location.position, zoom = zoom))
        }
    }

    fun explainPermissionOff() {
        scope.launch {
            val result =
                snackbarHostState.showOnly(
                    message = "Location access is off for Pubky Maps.",
                    actionLabel = if (settings.canOpenApplicationSettings) "Settings" else null,
                )
            if (result == SnackbarResult.ActionPerformed) settings.openApplicationSettings()
        }
    }

    fun explainLocationOff() {
        scope.launch {
            val result =
                snackbarHostState.showOnly(
                    message = "Your location is not available. Is location turned on?",
                    actionLabel = if (settings.canOpenLocationServicesSettings) "Turn on" else null,
                )
            if (result == SnackbarResult.ActionPerformed) settings.openLocationServicesSettings()
        }
    }

    LaunchedEffect(locationState) {
        snapshotFlow { locationState.lastLocation }
            .filterNotNull()
            .collect { location ->
                if (centerOnNextFix) {
                    centerOnNextFix = false
                    moveTo(location)
                }
            }
    }
    LaunchedEffect(locationState) {
        snapshotFlow { locationState.permission }
            .collect { permission ->
                if (permission is LocationPermission.NotGranted && centerOnNextFix) {
                    centerOnNextFix = false
                    // Android refuses without a dialog once the user has said no for good.
                    if (permission.canRequest == false) explainPermissionOff()
                }
            }
    }
    LaunchedEffect(locationState) {
        snapshotFlow { locationState.status }
            .collect { status ->
                if (status is LocationTrackingStatus.Unavailable && centerOnNextFix) {
                    centerOnNextFix = false
                    explainLocationOff()
                }
            }
    }
    LaunchedEffect(mapState) {
        snapshotFlow { mapState.cameraMoveReason }
            .collect { if (it == CameraMoveReason.Gesture) centerOnNextFix = false }
    }

    return {
        val step =
            myLocationStep(
                permission = locationState.permission,
                trackingUnavailable = locationState.status is LocationTrackingStatus.Unavailable,
                hasFix = locationState.lastLocation != null,
            )
        when (step) {
            MyLocationStep.RetryThenCenter -> {
                centerOnNextFix = true
                scope.launch {
                    val recovered =
                        retryRecovers(
                            retry = locationState::retry,
                            unavailable = snapshotFlow { locationState.status is LocationTrackingStatus.Unavailable },
                        )
                    // The status collector above may already have explained it and ended the request.
                    if (!recovered && centerOnNextFix) {
                        centerOnNextFix = false
                        explainLocationOff()
                    }
                }
            }

            MyLocationStep.MoveToLastFix -> {
                locationState.lastLocation?.let { moveTo(it) }
            }

            MyLocationStep.CenterOnNextFix -> {
                centerOnNextFix = true
            }

            MyLocationStep.ExplainPermissionOff -> {
                explainPermissionOff()
            }

            MyLocationStep.RequestPermission -> {
                centerOnNextFix = true
                locationState.requestPermission()
            }
        }
    }
}

/**
 * Moves the camera to [point] at [zoom]. With [rooms], the map its page leaves in view (see
 * [openedRoom]), the place lands in its middle ([centreShowing]): the move waits for the page to
 * open, at most [OPEN_ROOM_WAIT_MS], so it is one move and not a second one after it. A finger that
 * moved the map meanwhile has put it where the user wants it: then the map stays.
 */
private suspend fun MapState.moveToPlace(
    point: GeoPoint,
    zoom: Double,
    rooms: Flow<DpRect?>?,
) {
    val before = cameraPosition
    val room = rooms?.let { openedRoom(it) }
    if (cameraPosition != before && cameraMoveReason == CameraMoveReason.Gesture) return
    val centre = centreShowing(point, room, viewport?.size, zoom, cameraPosition.bearing)
    animateCamera(CameraUpdate(center = Position(longitude = centre.lon, latitude = centre.lat), zoom = zoom))
}

/** The camera of [this] map for [keepTappedInView]. */
private fun MapState.keepInViewMap(): KeepInViewMap =
    object : KeepInViewMap {
        override val size: DpSize? get() = viewport?.size
        override val moving: Flow<Boolean> = snapshotFlow { isCameraMoving }
        override val camera: MapView get() = MapView.of(cameraPosition)

        override fun screenPoint(point: GeoPoint): DpOffset? =
            screenLocationFromPosition(Position(longitude = point.lon, latitude = point.lat))

        override suspend fun centreOn(centre: DpOffset) {
            val target = positionFromScreenLocation(centre) ?: return
            animateCamera(CameraUpdate(center = target))
        }
    }
