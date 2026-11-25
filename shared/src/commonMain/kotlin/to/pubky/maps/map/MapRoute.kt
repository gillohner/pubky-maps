package to.pubky.maps.map

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraFit
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
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
import org.maplibre.spatialk.geojson.Position
import to.pubky.maps.config.ServerLists
import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.LinkedPlaceState
import to.pubky.maps.place.OpeningLinkCard
import to.pubky.maps.place.PlaceCard
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.search.RecentSearch
import to.pubky.maps.search.SearchActions
import to.pubky.maps.search.SearchArea
import to.pubky.maps.search.SearchMode
import to.pubky.maps.search.SearchServices
import to.pubky.maps.search.SearchState
import to.pubky.maps.search.distanceMeters
import to.pubky.maps.search.distanceText
import to.pubky.maps.status.ServicePart
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

/** Where the map opens before it knows anything about the user: the whole world. */
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
) {
    val sources = servers.mapStyles(isSystemInDarkTheme())
    // Saved, so a trip to Settings does not start again from a style that already failed.
    var fallbackIndex by rememberSaveable(sources) { mutableIntStateOf(0) }
    val fallback = StyleFallback(sources, fallbackIndex)
    val locationState = rememberLocationState(provider = rememberDefaultLocationProvider())
    // Only a place is pinned: a map view or a search near a point only moves the camera.
    val pinnedPoint by rememberUpdatedState(linkedPlace.place?.takeIf { it.pinned }?.point)
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
    // Pins while search is open; the picked result also when its card shows.
    val searchPins by rememberUpdatedState(searchState?.takeIf { it.mode != SearchMode.Idle })
    val mapState =
        rememberMapState(
            baseStyle = fallback.current?.let { BaseStyle.Uri(it.url) } ?: BaseStyle.Empty,
            initialCameraPosition = WorldCamera,
        ) {
            LocationIndicatorLayer(id = "user-location", locationState = locationState)
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

    val snackbarHostState = remember { SnackbarHostState() }
    val myLocation = rememberMyLocationAction(locationState, mapState, snackbarHostState)

    // A place opened from a link: the map moves to it once per opening. Saved, so returning from
    // Settings or recreating the activity keeps where the user has moved the map since.
    val place = linkedPlace.place
    var centredAt by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(linkedPlace.placedAt) {
        if (linkedPlace.placedAt == centredAt) return@LaunchedEffect
        centredAt = linkedPlace.placedAt
        val point = place?.point ?: return@LaunchedEffect
        mapState.animateCamera(
            CameraUpdate(
                center = Position(longitude = point.lon, latitude = point.lat),
                zoom = place.zoom ?: PLACE_ZOOM,
            ),
        )
    }
    LaunchedEffect(linkMessages) { linkMessages.collect { snackbarHostState.showOnly(it) } }

    val mapSearch =
        if (search != null && searchState != null) {
            rememberMapSearch(search, searchState, recents, mapState, locationState, onClosePlace)
                .copy(credits = servers.searchAttribution())
        } else {
            null
        }
    val selected = searchState?.selected?.takeIf { searchState.mode == SearchMode.Selected }

    MapScreen(
        attribution = (fallback.current ?: sources.first()).attribution,
        mapUnavailable = fallback.allFailed,
        onRetryMap = { fallbackIndex = fallback.retry().index },
        onMyLocation = myLocation,
        onOpenSettings = onOpenSettings,
        missing = missing,
        snackbarHostState = snackbarHostState,
        search = mapSearch,
        placeCard =
            when {
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
        MaplibreMap(state = mapState) {
            Box(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(MapControlMargin),
            ) {
                DisappearingScaleBar(
                    metersPerDp = { mapState.viewport?.metersPerDpAtCenter ?: 0.0 },
                    zoom = { mapState.cameraPosition.zoom },
                    // Under the search bar.
                    modifier = Modifier.align(Alignment.TopStart).padding(top = TopControlSize + MapControlMargin),
                )
                DisappearingCompassButton(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = TopControlSize + MapControlMargin),
                    size = TopControlSize,
                )
            }
        }
    }
}

/**
 * Search on the live map: asks around the map's centre, moves the map to a picked result and fits
 * it to the results of a search, and takes the back action while search is open.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun rememberMapSearch(
    search: SearchServices,
    state: SearchState,
    recents: List<RecentSearch>,
    mapState: MapState,
    locationState: LocationState,
    onCloseLinkedPlace: () -> Unit,
): MapSearch {
    val controller = search.controller

    BackHandler(enabled = state.mode != SearchMode.Idle) { controller.back() }

    // Search takes the bottom of the screen: a place opened from a link makes way.
    val searching = state.mode != SearchMode.Idle
    LaunchedEffect(searching) { if (searching) onCloseLinkedPlace() }
    // A picked result: the map moves to it.
    val selected = state.selected?.takeIf { state.mode == SearchMode.Selected }
    LaunchedEffect(selected) {
        val place = selected?.place ?: return@LaunchedEffect
        val point = place.point ?: return@LaunchedEffect
        mapState.animateCamera(
            CameraUpdate(center = Position(longitude = point.lon, latitude = point.lat), zoom = place.zoom ?: PLACE_ZOOM),
        )
    }
    // The results of a search: the map shows those near the area searched, once per search (not
    // again when a slower source adds results, after the user moved the map, or back from a place).
    var fittedQuery by remember { mutableStateOf<String?>(null) }
    val shownResults = state.results.takeIf { state.mode == SearchMode.Results && state.resultsAreCurrent }
    if (state.mode == SearchMode.Idle) fittedQuery = null
    LaunchedEffect(shownResults) {
        val points = shownResults?.mapNotNull { it.place.point }.orEmpty()
        if (points.isEmpty() || fittedQuery == state.resultsQuery.trim()) return@LaunchedEffect
        fittedQuery = state.resultsQuery.trim()
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
        remember(controller, mapState) {
            SearchActions(
                onStartEditing = controller::startEditing,
                onQueryChange = { controller.onQueryChange(it, mapState.searchArea()) },
                onSubmit = { controller.submit(mapState.searchArea()) },
                onSelect = controller::select,
                onSelectRecent = { controller.selectRecent(it, mapState.searchArea()) },
                onClearQuery = controller::clearQuery,
                onBack = { controller.back() },
            )
        }
    return MapSearch(state = state, recents = recents, userLocation = userLocation, actions = actions)
}

/** About 100 m: the box a lone result is shown in, a street's worth around it. */
private const val LONE_RESULT_MARGIN_DEGREES = 0.001

/**
 * The room around fitted results: beside the panel on a wide screen, above the half-open results
 * sheet on a phone, and under the search bar on both.
 */
private fun resultsFitPadding(size: DpSize?): DpPadding =
    if (size != null && size.width >= SidePanelMinWidth) {
        DpPadding(left = SidePanelWidth + 48.dp, top = 96.dp, right = 48.dp, bottom = 120.dp)
    } else {
        // Half the screen for the sheet, plus my location and the credits riding on its edge.
        DpPadding(left = 48.dp, top = 96.dp, right = 48.dp, bottom = (size?.height ?: 800.dp) / 2 + 120.dp)
    }

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
