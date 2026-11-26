package to.pubky.maps.offline

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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

    private fun TestScope.offlineMaps(
        store: FakeStore,
        unmetered: () -> Boolean = { true },
    ): OfflineMaps {
        var ids = 0
        return OfflineMaps(store, backgroundScope, clock, { "area-${++ids}" }, unmetered).also { store.controller = it }
    }

    /** The packs whose update a check started and has not finished. */
    private fun FakeStore.updating() =
        (packs.value as PackStoreState.Ready).packs.filter { it.metadata?.updating == true }.map { (it.handle as Pack).styleUrl }

    private fun OfflineMaps.maps() = (state.value as OfflineMapsState.Ready).maps

    @Test
    fun downloadsTheAreaInEveryStyleAndClosesThePicker() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore()
            val offline = offlineMaps(store)
            offline.startPicking()
            offline.download(zurich, listOf(light, dark, light))
            assertFalse(offline.picking.value)
            assertEquals(listOf(light), store.resumed)
            // The throttle is on before MapLibre sends the first request of a pack.
            assertEquals(listOf(true), store.downloadingAtResume)
            val map = offline.maps().single()
            assertEquals("area-1", map.id)
            assertEquals("Map around 47.38° N, 8.54° E", map.name)
            assertEquals(1_800_000_000_000, map.createdAt)
            assertEquals(OfflineMapStatus.Paused(0), map.status)
        }

    @Test
    fun theSecondStyleStartsOnceTheFirstIsComplete() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore()
            val offline = offlineMaps(store)
            offline.download(zurich, listOf(light, dark))
            store.progress(light, PackProgress(PackState.Downloading, 60, 100))
            assertEquals(listOf(light), store.resumed)
            // Each style is half of the area.
            assertEquals(OfflineMapStatus.Downloading(30), offline.maps().single().status)

            store.progress(light, PackProgress(PackState.Complete, 100, 100))
            assertEquals(listOf(light, dark), store.resumed)
            assertEquals(listOf(true, true), store.downloadingAtResume)
            store.progress(dark, PackProgress(PackState.Downloading, 20, 100))
            assertEquals(OfflineMapStatus.Downloading(60), offline.maps().single().status)
        }

    @Test
    fun aPausedAreaDoesNotStartItsNextStyle() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore()
            val offline = offlineMaps(store)
            offline.download(zurich, listOf(light, dark))
            store.progress(light, PackProgress(PackState.Downloading, 10, 100))
            offline.pause("area-1")
            store.progress(light, PackProgress(PackState.Paused, 10, 100))
            assertFalse(offline.downloading)
            assertEquals(listOf(light), store.resumed)

            // A download stopped at the limit does not go on with the next style either.
            offline.resume("area-1")
            assertEquals(listOf(light, light), store.resumed)
            store.progress(light, PackProgress(PackState.Downloading, 2_996, 4_000, 1_000_000, 2_996, 1_000_000))
            store.progress(light, PackProgress(PackState.Paused, 2_996, 4_000, 1_000_000, 2_996, 1_000_000))
            assertEquals(listOf(light, light), store.resumed)
            assertEquals(OfflineMapStatus.TooLarge, offline.maps().single().status)
            assertFalse(offline.downloading)
        }

    /** An area downloaded in both styles, as MapLibre reports it once complete. */
    private fun TestScope.readyArea(): Pair<FakeStore, OfflineMaps> {
        val store = FakeStore()
        val offline = offlineMaps(store)
        offline.download(zurich, listOf(light, dark))
        store.progress(light, PackProgress(PackState.Complete, 100, 100, 3_000_000, 80, 2_500_000))
        store.progress(dark, PackProgress(PackState.Complete, 100, 100, 2_600_000, 80, 2_500_000))
        assertFalse(offline.downloading)
        assertEquals(listOf(light, dark), store.paused)
        store.resumed.clear()
        return store to offline
    }

    @Test
    fun aReadyAreaIsWalkedAgainAndStaysReadyWhenNothingChanged() =
        runTest(UnconfinedTestDispatcher()) {
            val (store, offline) = readyArea()
            advanceTimeBy(FIRST_CHECK)
            runCurrent()
            assertEquals(listOf(light), store.resumed)
            assertTrue(offline.downloading)
            // MapLibre counts the walk from 0; the row stays as it was.
            store.progress(light, PackProgress(PackState.Downloading, 20, 100, 500_000, 10, 400_000))
            store.progress(light, PackProgress(PackState.Complete, 100, 100, 3_000_000, 80, 2_500_000))
            val ready = offline.maps().single()
            assertEquals(OfflineMapStatus.Ready, ready.status)
            assertEquals(3_100_000, ready.bytes)
            // A Complete during the first seconds may be the report from before the walk.
            assertEquals(listOf(light), store.resumed)

            advanceTimeBy(QUIET_CHECK)
            runCurrent()
            assertEquals(listOf(light, dark), store.resumed)
            advanceTimeBy(QUIET_CHECK)
            runCurrent()
            assertFalse(offline.downloading)
            assertEquals(OfflineMapStatus.Ready, offline.maps().single().status)

            assertEquals(emptyList(), store.updating())

            // And again later: MapLibre walks only a pack that is not active, so each rests once complete.
            assertEquals(listOf(light, dark, light, dark), store.paused)
            advanceTimeBy(CHECK_INTERVAL - QUIET_CHECK * 2)
            runCurrent()
            assertEquals(listOf(light, dark, light), store.resumed)
        }

    @Test
    fun aWalkThatFindsNewMapDataShowsAsAnUpdate() =
        runTest(UnconfinedTestDispatcher()) {
            val (store, offline) = readyArea()
            advanceTimeBy(FIRST_CHECK)
            runCurrent()
            // The map server moved its tiles: the walk downloads the area at their new addresses.
            store.progress(light, PackProgress(PackState.Downloading, 21, 100, 500_000, 20, 400_000))
            advanceTimeBy(QUIET_CHECK)
            runCurrent()
            assertEquals(OfflineMapStatus.Downloading(60, update = true), offline.maps().single().status)
            assertEquals("Updating · 60% · 3.1\u00A0MB", statusText(offline.maps().single()))
            assertEquals(listOf(light), store.resumed)
            assertTrue(offline.downloading)

            store.progress(light, PackProgress(PackState.Complete, 100, 100, 3_000_000, 80, 2_500_000))
            assertEquals(listOf(light, dark), store.resumed)
            store.progress(dark, PackProgress(PackState.Complete, 100, 100, 2_600_000, 80, 2_500_000))
            advanceTimeBy(QUIET_CHECK)
            runCurrent()
            assertEquals(OfflineMapStatus.Ready, offline.maps().single().status)
            assertFalse(offline.downloading)
        }

    @Test
    fun aPausedUpdateIsNotReadyUntilItIsResumedAndFinished() =
        runTest(UnconfinedTestDispatcher()) {
            val (store, offline) = readyArea()
            advanceTimeBy(FIRST_CHECK)
            runCurrent()
            // Both styles are marked before the first walk starts.
            assertEquals(listOf(light, dark), store.updating())
            assertEquals(OfflineMapStatus.Ready, offline.maps().single().status)
            store.progress(light, PackProgress(PackState.Downloading, 21, 100))
            advanceTimeBy(QUIET_CHECK)
            runCurrent()
            offline.pause("area-1")
            // MapLibre counts the release before's tiles too, so it reports a paused update complete.
            store.progress(light, PackProgress(PackState.Complete, 100, 100, 3_000_000, 80, 2_500_000))
            assertEquals(OfflineMapStatus.Paused(100, update = true), offline.maps().single().status)
            assertEquals(
                "Update paused. Until it finishes, parts of this map may not show without a connection.",
                statusText(offline.maps().single()),
            )
            assertFalse(offline.downloading)
            // The user paused it: later checks leave it.
            advanceTimeBy(CHECK_INTERVAL)
            runCurrent()
            assertEquals(listOf(light), store.resumed)

            // Resume walks both styles again, also the one MapLibre says is complete.
            offline.resume("area-1")
            assertEquals(listOf(light, light), store.resumed)
            store.progress(light, PackProgress(PackState.Downloading, 30, 100))
            assertEquals(OfflineMapStatus.Downloading(65, update = true), offline.maps().single().status)
            advanceTimeBy(QUIET_CHECK)
            runCurrent()
            store.progress(light, PackProgress(PackState.Complete, 100, 100, 3_000_000, 80, 2_500_000))
            assertEquals(listOf(dark), store.updating())
            assertEquals(listOf(light, light, dark), store.resumed)
            advanceTimeBy(QUIET_CHECK)
            runCurrent()
            assertEquals(emptyList(), store.updating())
            assertEquals(OfflineMapStatus.Ready, offline.maps().single().status)
            assertFalse(offline.downloading)
        }

    @Test
    fun anUpdateTheAppWasClosedDuringGoesOnAtTheFirstCheck() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore()
            val metadata = PackMetadata("area-1", "Map", 1, 8.448, 47.32, 8.625, 47.435, updating = true)
            val complete = PackProgress(PackState.Complete, 100, 100, 3_000_000, 80, 2_500_000)
            store.packs.value =
                PackStoreState.Ready(
                    listOf(
                        StoredPack(Pack(light), metadata, complete),
                        StoredPack(Pack(dark), metadata.copy(updating = false), complete),
                    ),
                )
            val offline = offlineMaps(store)
            assertEquals(OfflineMapStatus.Paused(100, update = true), offline.maps().single().status)

            advanceTimeBy(FIRST_CHECK)
            runCurrent()
            // Only the style whose update did not finish.
            assertEquals(listOf(light), store.resumed)
            store.progress(light, PackProgress(PackState.Downloading, 50, 100))
            assertEquals(OfflineMapStatus.Downloading(75, update = true), offline.maps().single().status)
            advanceTimeBy(QUIET_CHECK)
            runCurrent()
            store.progress(light, complete)
            assertEquals(OfflineMapStatus.Ready, offline.maps().single().status)
            assertEquals(listOf(light), store.resumed)
            assertFalse(offline.downloading)
        }

    @Test
    fun onMobileDataACheckOnlyFindsOutThatThereIsAnUpdate() =
        runTest(UnconfinedTestDispatcher()) {
            var unmetered = false
            val store = FakeStore()
            val offline = offlineMaps(store) { unmetered }
            offline.download(zurich, listOf(light))
            store.progress(light, PackProgress(PackState.Complete, 100, 100))
            store.resumed.clear()
            store.paused.clear()
            advanceTimeBy(FIRST_CHECK)
            runCurrent()
            // Nothing new: the walk ends as on Wi-Fi.
            assertEquals(listOf(light), store.resumed)
            advanceTimeBy(QUIET_CHECK)
            runCurrent()
            assertEquals(OfflineMapStatus.Ready, offline.maps().single().status)
            assertEquals(listOf(light), store.paused)

            // A new release: the walk is paused once it is still downloading.
            advanceTimeBy(CHECK_INTERVAL - QUIET_CHECK)
            runCurrent()
            assertEquals(listOf(light, light), store.resumed)
            store.progress(light, PackProgress(PackState.Downloading, 5, 100))
            advanceTimeBy(QUIET_CHECK)
            runCurrent()
            assertEquals(listOf(light, light), store.paused)
            store.progress(light, PackProgress(PackState.Complete, 100, 100))
            assertEquals(OfflineMapStatus.Paused(100, update = true), offline.maps().single().status)
            assertFalse(offline.downloading)
            advanceTimeBy(CHECK_INTERVAL)
            runCurrent()
            assertEquals(2, store.resumed.size)

            // On Wi-Fi the next check goes on with it.
            unmetered = true
            advanceTimeBy(CHECK_INTERVAL)
            runCurrent()
            assertEquals(listOf(light, light, light), store.resumed)
        }

    @Test
    fun anUpdateStoppedAtTheLimitDoesNotGoOnWithTheNextStyle() =
        runTest(UnconfinedTestDispatcher()) {
            val (store, offline) = readyArea()
            advanceTimeBy(FIRST_CHECK)
            runCurrent()
            store.progress(light, PackProgress(PackState.Downloading, 21, 100))
            advanceTimeBy(QUIET_CHECK)
            runCurrent()
            store.progress(light, PackProgress(PackState.Downloading, 2_996, 4_000, 1_000_000, 2_996, 1_000_000))
            assertEquals(listOf(light, dark, light), store.paused)
            // MapLibre counts the release before's tiles too and may report the stopped update complete.
            store.progress(light, PackProgress(PackState.Complete, 4_000, 4_000, 1_000_000, 4_000, 1_000_000))
            assertEquals(listOf(light), store.resumed)
            assertEquals(OfflineMapStatus.TooLarge, offline.maps().single().status)
            assertFalse(offline.downloading)
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
            // The dark style starts now.
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
            assertEquals(setOf(light, dark), store.deleted.toSet())
            assertEquals(2, store.deleted.size)
            assertEquals(emptyList(), store.resumed)
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
        assertEquals(
            "Update paused. Until it finishes, parts of this map may not show without a connection.",
            text(OfflineMapStatus.Paused(64, update = true)),
        )
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
        val metadata = PackMetadata("a", "Map", 1, 8.0, 47.0, 9.0, 48.0, stopped = true, updating = true)
        assertEquals(metadata, PackMetadata.decode(metadata.encode()))
        assertNull(PackMetadata.decode("Seattle".encodeToByteArray()))
        assertNull(PackMetadata.decode(null))
    }
}
