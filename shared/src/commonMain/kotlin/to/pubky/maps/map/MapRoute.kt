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
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
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
import org.maplibre.spatialk.geojson.Position
import to.pubky.maps.config.ServerLists
import to.pubky.maps.links.LinkedPlaceState
import to.pubky.maps.place.OpeningLinkCard
import to.pubky.maps.place.PlaceCard
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.status.ServicePart
import kotlin.math.max

/** Where the map opens before it knows anything about the user: the whole world. */
private val WorldCamera = CameraPosition(center = Position(longitude = 0.0, latitude = 30.0), zoom = 1.0)

/** The zoom the map moves to when it shows the user's location. */
private const val MY_LOCATION_ZOOM = 15.0

/** The zoom a place from a link is shown at when its link has none (a street, its buildings). */
private const val PLACE_ZOOM = 16.0

/**
 * The live map: the first style of the server list for the current theme that loads, the user's
 * location on request, and the [MapScreen] controls, with the [missing] parts of the app named on top
 * and the place a link opened ([linkedPlace]) pinned, centred and shown in a card.
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
) {
    val sources = servers.mapStyles(isSystemInDarkTheme())
    // Saved, so a trip to Settings does not start again from a style that already failed.
    var fallbackIndex by rememberSaveable(sources) { mutableIntStateOf(0) }
    val fallback = StyleFallback(sources, fallbackIndex)
    val locationState = rememberLocationState(provider = rememberDefaultLocationProvider())
    val pinnedPoint by rememberUpdatedState(linkedPlace.place?.point)
    val mapState =
        rememberMapState(
            baseStyle = fallback.current?.let { BaseStyle.Uri(it.url) } ?: BaseStyle.Empty,
            initialCameraPosition = WorldCamera,
        ) {
            LocationIndicatorLayer(id = "user-location", locationState = locationState)
            pinnedPoint?.let { PlacePin(it) }
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

    // A place opened from a link: the map moves to it once, when it arrives.
    val place = linkedPlace.place
    LaunchedEffect(place) {
        val point = place?.point ?: return@LaunchedEffect
        mapState.animateCamera(
            CameraUpdate(
                center = Position(longitude = point.lon, latitude = point.lat),
                zoom = place.zoom ?: PLACE_ZOOM,
            ),
        )
    }
    LaunchedEffect(linkMessages) { linkMessages.collect { snackbarHostState.showOnly(it) } }

    MapScreen(
        attribution = (fallback.current ?: sources.first()).attribution,
        mapUnavailable = fallback.allFailed,
        onRetryMap = { fallbackIndex = fallback.retry().index },
        onMyLocation = myLocation,
        onOpenSettings = onOpenSettings,
        missing = missing,
        snackbarHostState = snackbarHostState,
        placeCard =
            when {
                linkedPlace.opening -> {
                    { OpeningLinkCard() }
                }

                place != null -> {
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
                    modifier = Modifier.align(Alignment.TopStart),
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
