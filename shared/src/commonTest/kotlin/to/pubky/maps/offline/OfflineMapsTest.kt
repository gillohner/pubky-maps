package to.pubky.maps.offline

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineMapsTest {
    private class Pack(
        val styleUrl: String,
    )

    /** Packs in memory; the test sets their progress as MapLibre would report it. */
    private class FakeStore : PackStore {
        val packs = MutableStateFlow<PackStoreState>(PackStoreState.Ready(emptyList()))
        val resumed = ArrayList<String>()
        val paused = ArrayList<String>()
        var failCreate: String? = null
        val deleted = ArrayList<String>()

        /** Runs while the pack of a style is being created. */
        var whileCreating: (String) -> Unit = {}

        /** Whether the controller said a download runs at the moment each pack was resumed. */
        var downloadingAtResume = ArrayList<Boolean>()
        var controller: OfflineMaps? = null

        private val ready get() = (packs.value as PackStoreState.Ready).packs

        override val state = packs

        override suspend fun create(
            styleUrl: String,
            bounds: AreaBounds,
            maxZoom: Int,
            metadata: PackMetadata,
        ): Any {
            if (failCreate == styleUrl) error("disk full")
            whileCreating(styleUrl)
            val pack = Pack(styleUrl)
            packs.value = PackStoreState.Ready(ready + StoredPack(pack, metadata, PackProgress(PackState.Paused)))
            return pack
        }

        override fun resume(handle: Any) {
            resumed += (handle as Pack).styleUrl
            downloadingAtResume += controller?.downloading == true
        }

        /** As MapLibre, reports the pause later: the test sets it with [progress]. */
        override fun pause(handle: Any) {
            paused += (handle as Pack).styleUrl
        }

        override suspend fun delete(handle: Any) {
            deleted += (handle as Pack).styleUrl
            packs.value = PackStoreState.Ready(ready.filter { it.handle !== handle })
        }

        override suspend fun setMetadata(
            handle: Any,
            metadata: PackMetadata,
        ) = update(handle) { it.copy(metadata = metadata) }

        fun update(
            handle: Any,
            change: (StoredPack) -> StoredPack,
        ) {
            packs.value = PackStoreState.Ready(ready.map { if (it.handle === handle) change(it) else it })
        }

        fun progress(
            styleUrl: String,
            progress: PackProgress,
        ) = update(ready.first { (it.handle as Pack).styleUrl == styleUrl }.handle) { it.copy(progress = progress) }
    }

    private val zurich = AreaBounds(west = 8.448, south = 47.32, east = 8.625, north = 47.435)
    private val light = "https://tiles.example.org/styles/light"
    private val dark = "https://tiles.example.org/styles/dark"
    /** A millisecond later at each download. */
    private val clock =
        object : Clock {
            var millis = 1_800_000_000_000

            override fun now() = Instant.fromEpochMilliseconds(millis++)
        }

    private fun TestScope.offlineMaps(store: FakeStore): OfflineMaps {
        var ids = 0
        return OfflineMaps(store, backgroundScope, clock) { "area-${++ids}" }.also { store.controller = it }
    }

    private fun OfflineMaps.maps() = (state.value as OfflineMapsState.Ready).maps

    @Test
    fun downloadsTheAreaInEveryStyleAndClosesThePicker() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore()
            val offline = offlineMaps(store)
            offline.startPicking()
            offline.download(zurich, listOf(light, dark, light))
            assertFalse(offline.picking.value)
            assertEquals(listOf(light, dark), store.resumed)
            // The throttle is on before MapLibre sends the first request of a pack.
            assertEquals(listOf(true, true), store.downloadingAtResume)
            val map = offline.maps().single()
            assertEquals("area-1", map.id)
            assertEquals("Map around 47.38° N, 8.54° E", map.name)
            assertEquals(1_800_000_000_000, map.createdAt)
            assertEquals(OfflineMapStatus.Paused(0), map.status)
        }

    @Test
    fun anAreaIsReadyOnceEveryStyleIsCompleteAndTheThrottleStops() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore()
            val offline = offlineMaps(store)
            offline.download(zurich, listOf(light, dark))
            store.progress(light, PackProgress(PackState.Downloading, 50, 100, 2_000_000, 40, 1_500_000))
            store.progress(dark, PackProgress(PackState.Downloading, 10, 100, 1_600_000, 40, 1_500_000, error = "No connection"))
            val downloading = offline.maps().single()
            assertEquals(OfflineMapStatus.Downloading(30, retrying = "No connection"), downloading.status)
            // The tiles both styles share count once.
            assertEquals(1_500_000 + 500_000 + 100_000, downloading.bytes)
            assertTrue(offline.downloading)

            store.progress(light, PackProgress(PackState.Complete, 100, 100, 3_000_000, 80, 2_500_000))
            assertTrue(offline.downloading)
            store.progress(dark, PackProgress(PackState.Complete, 100, 100, 2_600_000, 80, 2_500_000))
            assertEquals(OfflineMapStatus.Ready, offline.maps().single().status)
            assertFalse(offline.downloading)
        }

    @Test
    fun aDownloadOverTheTileOrByteLimitIsStopped() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore()
            val offline = offlineMaps(store)
            offline.download(zurich, listOf(light))
            store.progress(light, PackProgress(PackState.Downloading, 900, 2_000, 30_000_000, 900, 47_000_000))
            assertEquals(emptyList(), store.paused)
            // Stopped short of 50 MB: 4 more tiles may be on their way.
            store.progress(light, PackProgress(PackState.Downloading, 901, 2_000, 30_000_000, 901, 48_000_000))
            assertEquals(listOf(light), store.paused)
            assertEquals(OfflineMapStatus.TooLarge, offline.maps().single().status)
            assertTrue(offline.downloading)
            store.progress(light, PackProgress(PackState.Paused, 901, 2_000, 30_000_000, 901, 48_000_000))
            assertFalse(offline.downloading)

            offline.download(zurich, listOf(dark))
            store.progress(dark, PackProgress(PackState.Downloading, 2_995, 4_000, 1_000_000, 2_995, 1_000_000))
            assertEquals(listOf(light), store.paused)
            store.progress(dark, PackProgress(PackState.Downloading, 2_996, 4_000, 1_000_000, 2_996, 1_000_000))
            assertEquals(listOf(light, dark), store.paused)
        }

    @Test
    fun aPausedDownloadIsThrottledUntilMapLibreHasPausedIt() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore()
            val offline = offlineMaps(store)
            offline.download(zurich, listOf(light))
            store.progress(light, PackProgress(PackState.Downloading, 10, 100))
            offline.pause("area-1")
            assertEquals(listOf(light), store.paused)
            store.progress(light, PackProgress(PackState.Downloading, 11, 100))
            assertTrue(offline.downloading)
            store.progress(light, PackProgress(PackState.Paused, 11, 100))
            assertFalse(offline.downloading)
            assertEquals(OfflineMapStatus.Paused(11), offline.maps().single().status)
        }

    @Test
    fun aPausedAreaResumesItsUnfinishedStylesOnly() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore()
            val offline = offlineMaps(store)
            offline.download(zurich, listOf(light, dark))
            store.progress(light, PackProgress(PackState.Complete, 100, 100))
            store.progress(dark, PackProgress(PackState.Paused, 40, 100))
            assertEquals(OfflineMapStatus.Paused(70), offline.maps().single().status)
            store.resumed.clear()
            offline.resume("area-1")
            assertEquals(listOf(dark), store.resumed)
            assertTrue(offline.downloading)
        }

    @Test
    fun deletingAnAreaDeletesEveryStyleAndStopsTheThrottle() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore()
            val offline = offlineMaps(store)
            offline.download(zurich, listOf(light, dark))
            offline.download(AreaBounds(7.4, 46.9, 7.5, 47.0), listOf(light))
            // Newest first.
            assertEquals(listOf("area-2", "area-1"), offline.maps().map { it.id })
            offline.delete("area-1")
            assertEquals(listOf("area-2"), offline.maps().map { it.id })
            assertTrue(offline.downloading)
            offline.delete("area-2")
            assertEquals(emptyList(), offline.maps())
            assertFalse(offline.downloading)
        }

    @Test
    fun aRefusedDownloadSaysWhyUntilTheNextOne() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore()
            val offline = offlineMaps(store)
            store.failCreate = light
            offline.download(zurich, listOf(light))
            assertEquals("Couldn't start the download: disk full", offline.problem.value)
            assertFalse(offline.downloading)
            store.failCreate = null
            offline.download(zurich, listOf(light))
            assertNull(offline.problem.value)
        }

    @Test
    fun anAreaWhoseSecondStyleFailsIsNotKeptHalf() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore()
            val offline = offlineMaps(store)
            store.failCreate = dark
            offline.download(zurich, listOf(light, dark))
            assertEquals(listOf(light), store.deleted)
            assertEquals(emptyList(), offline.maps())
            assertFalse(offline.downloading)
        }

    @Test
    fun aStyleCreatedAfterItsAreaWasDeletedIsDeletedToo() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore()
            val offline = offlineMaps(store)
            store.whileCreating = { if (it == dark) offline.delete("area-1") }
            offline.download(zurich, listOf(light, dark))
            assertEquals(listOf(light, dark), store.deleted)
            assertEquals(listOf(light), store.resumed)
            assertEquals(emptyList(), offline.maps())
            assertFalse(offline.downloading)
        }

    @Test
    fun theRowSaysWhatTheDownloadDoes() {
        fun text(
            status: OfflineMapStatus,
            bytes: Long = 6_300_000,
        ) = statusText(OfflineMap("a", "Map", 1, status, bytes))
        assertEquals("Ready · 6.3\u00A0MB", text(OfflineMapStatus.Ready))
        assertEquals(
            "Downloading · 42% · 6.3\u00A0MB\nTrying again: No connection",
            text(OfflineMapStatus.Downloading(42, "No connection")),
        )
        assertEquals("Paused at 64%", text(OfflineMapStatus.Paused(64)))
        val two = OfflineMapsState.Ready(List(2) { OfflineMap("$it", "Map", 1, OfflineMapStatus.Ready, 0) })
        assertEquals("2 areas on this phone.", offlineMapsSummary(two))
    }

    @Test
    fun anAreaTooLargeForOneDownloadIsRefused() =
        runTest(UnconfinedTestDispatcher()) {
            val offline = offlineMaps(FakeStore())
            assertFailsWith<IllegalArgumentException> { offline.download(AreaBounds(5.95, 45.81, 10.5, 47.81), listOf(light)) }
        }

    @Test
    fun theStoreStatesShowAsTheListsStates() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore()
            store.packs.value = PackStoreState.Loading
            val offline = offlineMaps(store)
            assertEquals(OfflineMapsState.Loading, offline.state.value)
            store.packs.value = PackStoreState.Failed("database locked")
            assertIs<OfflineMapsState.Failed>(offline.state.value)
        }

    @Test
    fun areasAreNamedAfterTheirCentre() {
        assertEquals("Map around 33.87° S, 151.20° E", areaName(AreaBounds(151.1, -33.9, 151.3, -33.84)))
        assertEquals("Map around 40.71° N, 74.01° W", areaName(AreaBounds(-74.05, 40.68, -73.97, 40.74)))
    }

    @Test
    fun metadataSurvivesTheDatabaseAndForeignBytesAreNotOurs() {
        val metadata = PackMetadata("a", "Map", 1, 8.0, 47.0, 9.0, 48.0, stopped = true)
        assertEquals(metadata, PackMetadata.decode(metadata.encode()))
        assertNull(PackMetadata.decode("Seattle".encodeToByteArray()))
        assertNull(PackMetadata.decode(null))
    }
}
