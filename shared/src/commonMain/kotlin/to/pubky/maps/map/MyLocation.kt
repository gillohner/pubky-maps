package to.pubky.maps.map

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.compose.location.LocationPermission
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What a tap on the my-location button does. */
internal enum class MyLocationStep {
    /** Move the map to the last known position. */
    MoveToLastFix,

    /** Move the map as soon as the first position arrives. */
    CenterOnNextFix,

    /** Location stopped (e.g. turned off): start it again and move once a position arrives. */
    RetryThenCenter,

    /** The permission is refused for good; only the system settings can change that. */
    ExplainPermissionOff,

    /** Ask for the permission, then move once a position arrives. */
    RequestPermission,
}

/** How long a restarted location may stay unavailable before the app says location is off. */
internal val LocationRetryGrace = 3.seconds

internal fun myLocationStep(
    permission: LocationPermission,
    trackingUnavailable: Boolean,
    hasFix: Boolean,
): MyLocationStep =
    when {
        permission is LocationPermission.Granted && trackingUnavailable -> MyLocationStep.RetryThenCenter
        permission is LocationPermission.Granted && hasFix -> MyLocationStep.MoveToLastFix
        permission is LocationPermission.Granted -> MyLocationStep.CenterOnNextFix
        permission is LocationPermission.NotGranted && permission.canRequest == false -> MyLocationStep.ExplainPermissionOff
        else -> MyLocationStep.RequestPermission
    }

/**
 * Restarts location with [retry] and reports whether it recovered: true as soon as [unavailable]
 * reports false within [grace], false when it still reports true after [grace].
 */
internal suspend fun retryRecovers(
    retry: () -> Unit,
    unavailable: Flow<Boolean>,
    grace: Duration = LocationRetryGrace,
): Boolean {
    retry()
    return withTimeoutOrNull(grace) { unavailable.first { !it } } != null
}
