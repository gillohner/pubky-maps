package to.pubky.maps.map

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.location.LocationPermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class MyLocationTest {
    private val granted = LocationPermission.Granted(accuracy = null)

    @Test
    fun aTapWhileLocationIsUnavailableRetriesAndWaitsForAFixInsteadOfComplaining() {
        assertEquals(MyLocationStep.RetryThenCenter, myLocationStep(granted, trackingUnavailable = true, hasFix = false))
        // An old position is not used while location is off: the map waits for a fresh one.
        assertEquals(MyLocationStep.RetryThenCenter, myLocationStep(granted, trackingUnavailable = true, hasFix = true))
    }

    @Test
    fun aTapWithPermissionMovesToTheLastFixOrWaitsForTheFirst() {
        assertEquals(MyLocationStep.MoveToLastFix, myLocationStep(granted, trackingUnavailable = false, hasFix = true))
        assertEquals(MyLocationStep.CenterOnNextFix, myLocationStep(granted, trackingUnavailable = false, hasFix = false))
    }

    @Test
    fun aTapWithoutPermissionAsksForItUnlessItIsRefusedForGood() {
        val askable = LocationPermission.NotGranted(canRequest = true)
        val unknown = LocationPermission.NotGranted(canRequest = null)
        val refused = LocationPermission.NotGranted(canRequest = false)

        assertEquals(MyLocationStep.RequestPermission, myLocationStep(LocationPermission.NotDetermined, false, false))
        assertEquals(MyLocationStep.RequestPermission, myLocationStep(askable, false, false))
        assertEquals(MyLocationStep.RequestPermission, myLocationStep(unknown, false, false))
        assertEquals(MyLocationStep.ExplainPermissionOff, myLocationStep(refused, false, false))
    }

    @Test
    fun aRetryThatRecoversWithinTheGraceIsNotReportedAsUnavailable() =
        runTest {
            val unavailable = MutableStateFlow(true)
            var retried = false
            var recovered: Boolean? = null

            launch { recovered = retryRecovers(retry = { retried = true }, unavailable = unavailable, grace = 3.seconds) }
            advanceTimeBy(1.seconds)
            unavailable.value = false
            advanceTimeBy(1.seconds)

            assertTrue(retried)
            assertEquals(true, recovered)
        }

    @Test
    fun aRetryStillUnavailableAfterTheGraceIsReported() =
        runTest {
            val unavailable = MutableStateFlow(true)
            var recovered: Boolean? = null

            launch { recovered = retryRecovers(retry = {}, unavailable = unavailable, grace = 3.seconds) }
            advanceTimeBy(2.seconds)
            assertNull(recovered, "no verdict before the grace ends")
            advanceTimeBy(2.seconds)

            assertEquals(false, recovered)
        }

    @Test
    fun aRetryThatIsAvailableAtOnceRecovers() =
        runTest {
            assertTrue(retryRecovers(retry = {}, unavailable = MutableStateFlow(false)))
            assertFalse(retryRecovers(retry = {}, unavailable = MutableStateFlow(true)))
        }
}
