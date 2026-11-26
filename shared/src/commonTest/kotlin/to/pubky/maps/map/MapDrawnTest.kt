package to.pubky.maps.map

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.map.MapEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MapDrawnTest {
    private val events = MutableSharedFlow<MapEvent>(extraBufferCapacity = 64)
    private val ready = MutableStateFlow(false)

    @Test
    fun theMapIsDrawnWhenItIsIdleWithItsStyleReady() =
        runTest {
            val drawn = async { awaitMapDrawn(events, ready) }
            runCurrent()
            events.emit(MapEvent.StyleLoaded)
            ready.value = true
            runCurrent()
            assertFalse(drawn.isCompleted, "drawn before the engine was idle")
            events.emit(MapEvent.Idle)
            runCurrent()
            assertTrue(drawn.isCompleted && drawn.await())
        }

    @Test
    fun anIdleMapIsDrawnWhenItsStyleTurnsReadyAfterwards() =
        runTest {
            // The engine went idle before the composition marked the style ready, and nothing
            // redraws after that: the map has drawn all the same.
            val drawn = async { awaitMapDrawn(events, ready) }
            runCurrent()
            events.emit(MapEvent.StyleLoaded)
            events.emit(MapEvent.Idle)
            runCurrent()
            assertFalse(drawn.isCompleted, "drawn without a style")
            ready.value = true
            runCurrent()
            assertTrue(drawn.isCompleted && drawn.await())
        }

    @Test
    fun anEngineBusyAgainIsNotDrawnUntilItIsIdleAgain() =
        runTest {
            val drawn = async { awaitMapDrawn(events, ready) }
            runCurrent()
            events.emit(MapEvent.Idle)
            events.emit(MapEvent.CameraMoved)
            runCurrent()
            ready.value = true
            runCurrent()
            assertFalse(drawn.isCompleted, "drawn while the camera moved")
            events.emit(MapEvent.Idle)
            runCurrent()
            assertTrue(drawn.isCompleted && drawn.await())
        }

    @Test
    fun aMapClosedBeforeItDrewIsNotReportedAsDrawn() =
        runTest {
            assertFalse(awaitMapDrawn(flowOf(MapEvent.StyleLoaded, MapEvent.CameraMoved), flowOf(true)))
            assertFalse(awaitMapDrawn(flowOf(MapEvent.StyleLoaded, MapEvent.Idle), flowOf(false)))
        }
}
