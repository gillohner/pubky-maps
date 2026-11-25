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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
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
import to.pubky.maps.config.ServerConfig
import kotlin.math.max

/** Where the map opens before it knows anything about the user: the whole world. */
private val WorldCamera = CameraPosition(center = Position(longitude = 0.0, latitude = 30.0), zoom = 1.0)

/** The zoom the map moves to when it shows the user's location. */
private const val MY_LOCATION_ZOOM = 15.0

/**
 * The live map: the first style of the server list for the current theme that loads, the user's
 * location on request, and the [MapScreen] controls.
 */
@Composable
fun MapRoute(
    serverConfig: ServerConfig,
    onOpenSettings: () -> Unit,
) {
    val sources = serverConfig.mapStyles.forTheme(isSystemInDarkTheme())
    var fallback by remember(sources) { mutableStateOf(StyleFallback(sources)) }
    val locationState = rememberLocationState(provider = rememberDefaultLocationProvider())
    val mapState =
        rememberMapState(
            baseStyle = fallback.current?.let { BaseStyle.Uri(it.styleUrl) } ?: BaseStyle.Empty,
            initialCameraPosition = WorldCamera,
        ) {
            LocationIndicatorLayer(id = "user-location", locationState = locationState)
        }

    // A style that fails to load hands over to the next one in the list.
    LaunchedEffect(mapState, sources) {
        snapshotFlow { mapState.style.loadState to mapState.style.baseStyle }
            .collect { (loadState, style) ->
                if (loadState is StyleLoadState.Failed && style is BaseStyle.Uri) {
                    fallback = fallback.failed(style.uri)
                }
            }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val myLocation = rememberMyLocationAction(locationState, mapState, snackbarHostState)

    MapScreen(
        attribution = (fallback.current ?: sources.first()).attribution,
        mapUnavailable = fallback.allFailed,
        onRetryMap = { fallback = fallback.retry() },
        onMyLocation = myLocation,
        onOpenSettings = onOpenSettings,
        snackbarHostState = snackbarHostState,
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

/**
 * What the my-location button does: ask for the location permission only now, then move the map to
 * the user's position as soon as it is known.
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
            .collect { if (it is LocationPermission.NotGranted) centerOnNextFix = false }
    }

    return {
        val permission = locationState.permission
        val lastLocation = locationState.lastLocation
        when {
            permission is LocationPermission.Granted && lastLocation != null -> {
                moveTo(lastLocation)
            }

            permission is LocationPermission.Granted -> {
                centerOnNextFix = true
                if (locationState.status is LocationTrackingStatus.Unavailable) {
                    locationState.retry()
                    scope.launch { snackbarHostState.showSnackbar("Your location is not available. Is location turned on?") }
                }
            }

            permission is LocationPermission.NotGranted && permission.canRequest == false -> {
                scope.launch {
                    val result =
                        snackbarHostState.showSnackbar(
                            message = "Location access is off for Pubky Maps.",
                            actionLabel = if (settings.canOpenApplicationSettings) "Settings" else null,
                            duration = SnackbarDuration.Long,
                        )
                    if (result == SnackbarResult.ActionPerformed) settings.openApplicationSettings()
                }
            }

            else -> {
                centerOnNextFix = true
                locationState.requestPermission()
            }
        }
    }
}
