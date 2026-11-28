package to.pubky.maps.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReadyToInstallTest {
    private val update = AvailableUpdate(versionCode = 2, versionName = "0.1.0-edge.2", sizeBytes = 1)

    private fun state(
        status: UpdateStatus,
        ready: AvailableUpdate? = null,
    ) = UpdatesUiState(UpdateChannel.Edge, autoCheck = true, status = status, dialog = null, ready = ready)

    @Test
    fun onlyAnAvailableUpdateIsReady() {
        assertEquals(update, state(UpdateStatus.Available(update)).readyToInstall)
        assertNull(state(UpdateStatus.NotChecked).readyToInstall)
        assertNull(state(UpdateStatus.Checking).readyToInstall)
        assertNull(state(UpdateStatus.UpToDate("0.1.0-edge.1")).readyToInstall)
        assertNull(state(UpdateStatus.Failed("The update server could not be reached.")).readyToInstall)
    }

    @Test
    fun theUpdateFoundBeforeStaysReadyWhileACheckRunsOrGetsNoAnswer() {
        assertEquals(update, state(UpdateStatus.Checking, ready = update).readyToInstall)
        assertEquals(update, state(UpdateStatus.Failed("The update server could not be reached."), ready = update).readyToInstall)
        // A check that answered decides.
        assertNull(state(UpdateStatus.UpToDate("0.1.0-edge.1"), ready = update).readyToInstall)
        assertNull(state(UpdateStatus.NotChecked, ready = update).readyToInstall)
    }
}
