package to.pubky.maps.map

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.spatialk.geojson.Position
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LastMapViewTest {
    private val zurich = MapView(lat = 47.3769, lon = 8.5417, zoom = 14.5, bearing = 30.0, pitch = 40.0)
    private val lugano = MapView(lat = 46.0037, lon = 8.9511, zoom = 12.0)

    @Test
    fun theFileKeepsTheWholeView() {
        assertEquals(StoredView(zurich), decodeStoredView(encodeStoredView(StoredView(zurich))))
        assertEquals(StoredView(null), decodeStoredView(encodeStoredView(StoredView(null))))
        // A later version's extra fields do not lose the view.
        assertEquals(StoredView(lugano), decodeStoredView("""{"version":2,"view":{"lat":46.0037,"lon":8.9511,"zoom":12.0,"roll":3}}"""))
    }

    @Test
    fun aViewTheMapCannotShowIsDropped() {
        for (view in listOf(
            """{"lat":95,"lon":8,"zoom":10}""",
            """{"lat":47,"lon":8,"zoom":30}""",
            """{"lat":47,"lon":8,"zoom":10,"pitch":-1}""",
        )) {
            assertNull(decodeStoredView("""{"version":1,"view":$view}""").view, view)
        }
        assertFalse(MapView(lat = Double.NaN, lon = 8.0, zoom = 10.0).isValid)
    }

    @Test
    fun aViewIsTakenWithinOneTurnAroundTheWorld() {
        val view =
            MapView.of(
                CameraPosition(center = Position(longitude = 190.0, latitude = 47.0), zoom = 3.0, bearing = -30.0, pitch = 20.0),
            )
        assertEquals(-170.0, view.lon, 1e-9)
        assertEquals(330.0, view.bearing, 1e-9)
        assertEquals(listOf(47.0, 3.0, 20.0), listOf(view.lat, view.zoom, view.pitch))
        assertTrue(view.isValid)
        assertEquals(zurich, MapView.of(zurich.toCameraPosition()))
    }

    @Test
    fun aViewIsStoredOnceTheCameraHasRestedAtIt() =
        runTest {
            val camera = MutableStateFlow<CameraPosition?>(zurich.toCameraPosition())
            val stored = mutableListOf<MapView>()
            val job = launch { restingViews(camera).collect { stored += it } }
            advanceTimeBy(VIEW_REST_MS - 1)
            runCurrent()
            assertEquals(emptyList(), stored, "stored before the camera had rested")
            // The user starts moving the map before the rest is over: nothing is stored on the way.
            camera.value = null
            advanceTimeBy(5_000)
            camera.value = MapView(47.0, 8.0, 10.0).toCameraPosition()
            advanceTimeBy(300)
            camera.value = null
            advanceTimeBy(300)
            camera.value = lugano.toCameraPosition()
            advanceTimeBy(VIEW_REST_MS + 1)
            assertEquals(listOf(lugano), stored)
            // A move that ends where the map already was stores nothing again.
            camera.value = null
            advanceTimeBy(300)
            camera.value = lugano.toCameraPosition()
            advanceTimeBy(VIEW_REST_MS + 1)
            assertEquals(listOf(lugano), stored)
            job.cancel()
        }

    private val fileSystem = FakeFileSystem()
    private val path = "/data/no_backup/map-view.json".toPath()

    @AfterTest
    fun noFileLeftOpen() {
        fileSystem.checkNoOpenFiles()
    }

    /** Runs [block] with a store on the test file, then closes it (DataStore allows one per file). */
    private suspend fun TestScope.withStore(block: suspend (LastMapViewStore) -> Unit) {
        val job = Job()
        val store = DataStoreLastMapViewStore(fileSystem, { path }, CoroutineScope(coroutineContext + job))
        try {
            block(store)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun theNextStartOpensWhereTheUserLeftTheMap() =
        runTest {
            fileSystem.createDirectories(path.parent!!)
            withStore { store ->
                assertEquals(StartView.Reading, store.startView.value)
                advanceUntilIdle()
                // A first start: no view yet, the map opens at the whole world.
                assertEquals(StartView.Read(null), store.startView.value)
                store.save(zurich)
                store.save(lugano)
                // A map opened again in this process (Back, then the app again) opens there too.
                assertEquals(StartView.Read(lugano), store.startView.value)
                advanceUntilIdle()
            }
            withStore { store ->
                advanceUntilIdle()
                assertEquals(StartView.Read(lugano), store.startView.value)
            }
        }

    @Test
    fun aViewSavedWhileTheFileIsReadWinsOverTheFile() =
        runTest {
            fileSystem.createDirectories(path.parent!!)
            fileSystem.write(path) { writeUtf8(encodeStoredView(StoredView(zurich))) }
            withStore { store ->
                assertEquals(StartView.Reading, store.startView.value)
                store.save(lugano)
                advanceUntilIdle()
                assertEquals(StartView.Read(lugano), store.startView.value)
            }
            assertEquals(StoredView(lugano), decodeStoredView(fileSystem.read(path) { readUtf8() }))
        }

    @Test
    fun aViewTheMapCannotShowIsNotStored() =
        runTest {
            fileSystem.createDirectories(path.parent!!)
            withStore { store ->
                advanceUntilIdle()
                store.save(lugano)
                store.save(MapView(lat = 47.0, lon = 8.0, zoom = 40.0))
                assertEquals(StartView.Read(lugano), store.startView.value)
                advanceUntilIdle()
            }
            withStore { store ->
                advanceUntilIdle()
                assertEquals(StartView.Read(lugano), store.startView.value)
            }
        }

    @Test
    fun anUnreadableFileOpensTheWorldAndIsReplacedByTheNextView() =
        runTest {
            fileSystem.createDirectories(path.parent!!)
            fileSystem.write(path) { writeUtf8("{ this is not json") }
            withStore { store ->
                advanceUntilIdle()
                assertEquals(StartView.Read(null), store.startView.value)
                store.save(zurich)
                advanceUntilIdle()
            }
            assertEquals(StoredView(zurich), decodeStoredView(fileSystem.read(path) { readUtf8() }))
        }

    @Test
    fun aFileThatCannotBeOpenedOpensTheWorld() =
        runTest {
            // A folder where the file should be: it cannot be read or written, and the map still opens.
            fileSystem.createDirectories(path)
            withStore { store ->
                advanceUntilIdle()
                assertEquals(StartView.Read(null), store.startView.value)
                store.save(zurich)
                advanceUntilIdle()
                // The map opened again in this process still opens at the view.
                assertEquals(StartView.Read(zurich), store.startView.value)
            }
        }
}
