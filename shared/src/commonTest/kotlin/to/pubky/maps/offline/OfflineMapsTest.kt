package to.pubky.maps.offline

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
        var failDelete = false

        /** A pack whose delete fails. */
        var failDeleteOf: Any? = null
        val deleted = ArrayList<String>()

        /** While set, a delete waits until it is completed (MapLibre's delete runs on its own thread). */
        var deleteGate: CompletableDeferred<Unit>? = null

        /** While true, metadata set is listed only at [listHeld], as MapLibre lists it after the call returns. */
        var holdListing = false
        private val held = ArrayList<() -> Unit>()

        fun listHeld() {
            holdListing = false
            held.forEach { it() }
            held.clear()
        }

        /** Styles of the packs made to replace another ([PackStore.createLike]), in turn. */
        val replacements = ArrayList<String>()

        /**
         * As MapLibre with every tile in its database: a resumed replacement is complete at once, with
         * the progress of the pack it replaces. Off, it stays downloading.
         */
        var linksAtOnce = true

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
            packs.value = PackStoreState.Ready(ready + StoredPack(pack, metadata, PackProgress(PackState.Paused), styleUrl))
            return pack
        }

        override suspend fun createLike(
            handle: Any,
            metadata: PackMetadata,
        ): Any {
            val style = (handle as Pack).styleUrl
            if (failCreate == style) error("disk full")
            replacements += style
            val pack = Pack(style)
            packs.value = PackStoreState.Ready(ready + StoredPack(pack, metadata, PackProgress(PackState.Paused), style))
            return pack
        }

        override fun resume(handle: Any) {
            val pack = ready.first { it.handle === handle }
            if (pack.metadata?.replacing == true) {
                val old = ready.first { it.style == pack.style && it.metadata?.replacing != true }
                update(handle) {
                    it.copy(progress = if (linksAtOnce) old.progress else PackProgress(PackState.Downloading, 10, 100))
                }
                return
            }
            resumed += (handle as Pack).styleUrl
            downloadingAtResume += controller?.downloading == true
        }

        /** As MapLibre, reports the pause later: the test sets it with [progress]. */
        override fun pause(handle: Any) {
            paused += (handle as Pack).styleUrl
        }

        override suspend fun delete(handle: Any) {
            deleteGate?.await()
            if (failDelete || handle === failDeleteOf) error("disk full")
            deleted += (handle as Pack).styleUrl
            packs.value = PackStoreState.Ready(ready.filter { it.handle !== handle })
        }

        override suspend fun setMetadata(
            handle: Any,
            metadata: PackMetadata,
        ) {
            if (holdListing) {
                held += {
                    update(
                        handle,
                    ) { it.copy(metadata = metadata) }
                }
            } else {
                update(handle) { it.copy(metadata = metadata) }
            }
        }

        fun update(
            handle: Any,
            change: (StoredPack) -> StoredPack,
        ) {
            packs.value = PackStoreState.Ready(ready.map { if (it.handle === handle) change(it) else it })
        }

        /** Sets the progress of the pack being made to replace the one of [styleUrl]. */
        fun replacementProgress(
            styleUrl: String,
            progress: PackProgress,
        ) = update(ready.first { it.style == styleUrl && it.metadata?.replacing == true }.handle) { it.copy(progress = progress) }

        /** Sets the progress of the pack of [styleUrl] that is part of the area (not a replacement being made). */
        fun progress(
            styleUrl: String,
            progress: PackProgress,
        ) = update(ready.first { it.style == styleUrl && it.metadata?.replacing != true }.handle) { it.copy(progress = progress) }
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
        metered: () -> Boolean = { !unmetered() },
    ): OfflineMaps {
        var ids = 0
        return OfflineMaps(store, backgroundScope, clock, { "area-${++ids}" }, unmetered, metered).also { store.controller = it }
    }

    private enum class Network { WiFi, None, Mobile }

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
            val fetching = ArrayList<Boolean>()
            backgroundScope.launch { offline.fetchingChanges.collect { fetching += it } }
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
            // MapLibre may write tiles for the whole check, also between its two walks.
            assertEquals(listOf(false, true, false), fetching)

            assertEquals(emptyList(), store.updating())
            assertEquals(0, offline.maps().single().newDataAt)
            // Nothing new: the packs stay.
            assertEquals(emptyList(), store.replacements)
            assertEquals(emptyList(), store.deleted)

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
            assertTrue(offline.maps().single().newDataAt > 0)
        }

    @Test
    fun anUpdateWalkingItsSecondStyleStillShowsAsUpdating() =
        runTest(UnconfinedTestDispatcher()) {
            val (store, offline) = readyArea()
            advanceTimeBy(FIRST_CHECK)
            runCurrent()
            store.progress(light, PackProgress(PackState.Downloading, 21, 100, 500_000, 20, 400_000))
            advanceTimeBy(QUIET_CHECK)
            runCurrent()
            store.progress(light, PackProgress(PackState.Complete, 100, 100, 3_000_000, 80, 2_500_000))
            assertEquals(listOf(light, dark), store.resumed)
            // MapLibre still reports the dark pack Complete from before its walk: the update is not
            // paused, and Resume would do nothing.
            assertEquals(OfflineMapStatus.Downloading(99, update = true), offline.maps().single().status)
            assertEquals("Updating · 99% · 3.1 MB", statusText(offline.maps().single()))
            store.progress(dark, PackProgress(PackState.Downloading, 30, 100, 300_000, 25, 250_000))
            assertEquals(OfflineMapStatus.Downloading(65, update = true), offline.maps().single().status)
            store.progress(dark, PackProgress(PackState.Complete, 100, 100, 2_600_000, 80, 2_500_000))
            assertEquals(OfflineMapStatus.Downloading(99, update = true), offline.maps().single().status)
            advanceTimeBy(QUIET_CHECK)
            runCurrent()
            assertEquals(OfflineMapStatus.Ready, offline.maps().single().status)
        }

    /** [readyArea] on the [network] it gives at each look. */
    private fun TestScope.readyArea(network: () -> Network): Pair<FakeStore, OfflineMaps> {
        val store = FakeStore()
        val offline = offlineMaps(store, unmetered = { network() == Network.WiFi }, metered = { network() == Network.Mobile })
        offline.download(zurich, listOf(light, dark))
        store.progress(light, PackProgress(PackState.Complete, 100, 100, 3_000_000, 80, 2_500_000))
        store.progress(dark, PackProgress(PackState.Complete, 100, 100, 2_600_000, 80, 2_500_000))
        store.resumed.clear()
        store.paused.clear()
        return store to offline
    }

    @Test
    fun anUpdateStartedOnWiFiPausesWhenThePhoneMovesToMobileData() =
        runTest(UnconfinedTestDispatcher()) {
            var network = Network.WiFi
            val (store, offline) = readyArea { network }
            advanceTimeBy(FIRST_CHECK)
            runCurrent()
            store.progress(light, PackProgress(PackState.Downloading, 21, 100, 500_000, 20, 400_000))
            advanceTimeBy(QUIET_CHECK)
            runCurrent()
            assertEquals(OfflineMapStatus.Downloading(60, update = true), offline.maps().single().status)
            advanceTimeBy(NETWORK_CHECK * 3)
            runCurrent()
            assertEquals(emptyList(), store.paused)
            // A moment without a network (Wi-Fi reconnecting): the download only waits.
            network = Network.None
            advanceTimeBy(NETWORK_CHECK * 3)
            runCurrent()
            assertEquals(emptyList(), store.paused)

            // On mobile data: the rest of the new release waits for Wi-Fi, as a check begun on mobile data does.
            network = Network.Mobile
            advanceTimeBy(NETWORK_CHECK)
            runCurrent()
            assertEquals(listOf(light), store.paused)
            store.progress(light, PackProgress(PackState.Paused, 40, 100, 900_000, 35, 800_000))
            assertEquals(OfflineMapStatus.Paused(70, update = true), offline.maps().single().status)
            assertFalse(offline.downloading)
            assertEquals(listOf(light, dark), store.updating())
            // The dark style is not walked on mobile data, nor is the update at the next check.
            assertEquals(listOf(light), store.resumed)
            advanceTimeBy(CHECK_INTERVAL)
            runCurrent()
            assertEquals(listOf(light), store.resumed)

            // Back on Wi-Fi, the next check goes on with it.
            network = Network.WiFi
            advanceTimeBy(CHECK_INTERVAL)
            runCurrent()
            assertEquals(listOf(light, light), store.resumed)
        }

    @Test
    fun anUpdateWhoseSecondStyleStartsOnMobileDataWaitsForWiFi() =
        runTest(UnconfinedTestDispatcher()) {
            var network = Network.WiFi
            val (store, offline) = readyArea { network }
            advanceTimeBy(FIRST_CHECK)
            runCurrent()
            store.progress(light, PackProgress(PackState.Downloading, 21, 100, 500_000, 20, 400_000))
            advanceTimeBy(QUIET_CHECK)
            runCurrent()
            network = Network.Mobile
            store.progress(light, PackProgress(PackState.Complete, 100, 100, 3_000_000, 80, 2_500_000))
            assertEquals(listOf(light, dark), store.resumed)
            // The dark walk still downloads after its quiet seconds: it has more new data, which waits.
            store.progress(dark, PackProgress(PackState.Downloading, 30, 100, 300_000, 25, 250_000))
            advanceTimeBy(QUIET_CHECK)
            runCurrent()
            // The light pack rests once its walk is complete; the dark one is paused there.
            assertEquals(listOf(light, dark), store.paused)
            store.progress(dark, PackProgress(PackState.Paused, 30, 100, 300_000, 25, 250_000))
            assertEquals(OfflineMapStatus.Paused(65, update = true), offline.maps().single().status)
            assertEquals(listOf(dark), store.updating())
        }

    @Test
    fun aCheckBegunOnMobileDataGoesOnWhenThePhoneIsOnWiFiByTheEndOfItsQuietWalk() =
        runTest(UnconfinedTestDispatcher()) {
            var network = Network.Mobile
            val (store, offline) = readyArea { network }
            advanceTimeBy(FIRST_CHECK)
            runCurrent()
            store.progress(light, PackProgress(PackState.Downloading, 21, 100, 500_000, 20, 400_000))
            network = Network.WiFi
            advanceTimeBy(QUIET_CHECK + NETWORK_CHECK * 3)
            runCurrent()
            assertEquals(emptyList(), store.paused)
            assertEquals(OfflineMapStatus.Downloading(60, update = true), offline.maps().single().status)
        }

    @Test
    fun aCheckBegunWithoutANetworkThatFindsANewReleaseSaysUpdatePaused() =
        runTest(UnconfinedTestDispatcher()) {
            var network = Network.None
            val (store, offline) = readyArea { network }
            advanceTimeBy(FIRST_CHECK)
            runCurrent()
            // The stored style already points at a new release, so the walk waits for tiles.
            store.progress(light, PackProgress(PackState.Downloading, 21, 100, 500_000, 20, 400_000))
            advanceTimeBy(QUIET_CHECK)
            runCurrent()
            assertEquals(listOf(light), store.paused)
            store.progress(light, PackProgress(PackState.Paused, 21, 100, 500_000, 20, 400_000))
            assertEquals(OfflineMapStatus.Paused(60, update = true), offline.maps().single().status)
            // Nothing runs, so the offline place reads are not held back.
            assertFalse(offline.downloading)
            assertFalse(offline.fetchingChanges.first())
            assertEquals(listOf(light, dark), store.updating())
            advanceTimeBy(CHECK_INTERVAL)
            runCurrent()
            assertEquals(listOf(light), store.resumed)

            // On Wi-Fi, the next check goes on with it.
            network = Network.WiFi
            advanceTimeBy(CHECK_INTERVAL)
            runCurrent()
            assertEquals(listOf(light, light), store.resumed)
        }

    @Test
    fun anUpdateTheUserResumedGoesOnOnMobileData() =
        runTest(UnconfinedTestDispatcher()) {
            var network = Network.WiFi
            val (store, offline) = readyArea { network }
            advanceTimeBy(FIRST_CHECK)
            runCurrent()
            store.progress(light, PackProgress(PackState.Downloading, 21, 100, 500_000, 20, 400_000))
            advanceTimeBy(QUIET_CHECK)
            runCurrent()
            network = Network.Mobile
            advanceTimeBy(NETWORK_CHECK)
            runCurrent()
            store.progress(light, PackProgress(PackState.Paused, 40, 100, 900_000, 35, 800_000))
            store.paused.clear()

            // The user asks for it: it downloads to the end on any network.
            offline.resume(offline.maps().single().id)
            store.progress(light, PackProgress(PackState.Downloading, 41, 100, 900_000, 35, 800_000))
            advanceTimeBy(QUIET_CHECK + NETWORK_CHECK * 3)
            runCurrent()
            assertEquals(emptyList(), store.paused)
            assertEquals(OfflineMapStatus.Downloading(70, update = true), offline.maps().single().status)
            store.progress(light, PackProgress(PackState.Complete, 100, 100, 3_000_000, 80, 2_500_000))
            store.progress(dark, PackProgress(PackState.Downloading, 30, 100, 300_000, 25, 250_000))
            advanceTimeBy(QUIET_CHECK + NETWORK_CHECK * 3)
            runCurrent()
            // Only the rest of the light pack, whose walk is complete.
            assertEquals(listOf(light), store.paused)
            assertEquals(listOf(light, light, dark), store.resumed)
            store.progress(dark, PackProgress(PackState.Complete, 100, 100, 2_600_000, 80, 2_500_000))
            assertEquals(OfflineMapStatus.Ready, offline.maps().single().status)
            assertEquals(emptyList(), store.updating())
        }

    /** The packs of the store, made to replace another or not. */
    private fun FakeStore.all() = (packs.value as PackStoreState.Ready).packs

    /** A check of [readyArea] whose light walk downloads a new release; returns the packs before it. */
    private fun TestScope.updateBothStyles(store: FakeStore): List<Any> {
        val before = store.all().map { it.handle }
        advanceTimeBy(FIRST_CHECK)
        runCurrent()
        store.progress(light, PackProgress(PackState.Downloading, 21, 100, 500_000, 20, 400_000))
        advanceTimeBy(QUIET_CHECK)
        runCurrent()
        store.progress(light, PackProgress(PackState.Complete, 100, 100, 3_000_000, 80, 2_500_000))
        // The dark style shares the new tiles: its walk fetches nothing.
        advanceTimeBy(QUIET_CHECK)
        runCurrent()
        return before
    }

    @Test
    fun anUpdateReplacesEveryPackOfTheAreaSoTheReleaseBeforesTilesCanGo() =
        runTest(UnconfinedTestDispatcher()) {
            val (store, offline) = readyArea()
            val fetching = ArrayList<Boolean>()
            backgroundScope.launch { offline.fetchingChanges.collect { fetching += it } }
            val before = updateBothStyles(store)

            // Both styles: the dark one kept the old release's tiles too, though its walk fetched nothing.
            assertEquals(listOf(light, dark), store.replacements)
            assertEquals(listOf(light, dark), store.deleted)
            val packs = store.all()
            assertEquals(listOf(light, dark), packs.map { it.style })
            assertTrue(packs.none { pack -> before.any { it === pack.handle } })
            assertTrue(packs.none { it.metadata?.replacing == true })
            val map = offline.maps().single()
            assertEquals("area-1", map.id)
            assertEquals("Map around 47.38° N, 8.54° E", map.name)
            assertEquals(OfflineMapStatus.Ready, map.status)
            assertEquals(3_100_000, map.bytes)
            // The new packs keep when each got new map data, so the area's places are not read twice.
            assertTrue(map.newDataAt > 0)
            assertEquals(listOf(map.newDataAt, 0L), packs.map { it.metadata?.newDataAt })
            assertTrue(packs.all { it.metadata?.updating == false && it.metadata?.newData == false })
            assertFalse(offline.downloading)
            // The tiles are read again only after the replacement, which writes the database too.
            assertEquals(listOf(false, true, false), fetching)

            // The next check walks the new packs, and finds nothing new.
            advanceTimeBy(CHECK_INTERVAL - QUIET_CHECK * 2)
            runCurrent()
            assertEquals(listOf(light, dark, light), store.resumed)
            advanceTimeBy(QUIET_CHECK * 2)
            runCurrent()
            assertEquals(listOf(light, dark), store.replacements)
        }

    @Test
    fun aReplacementThatDoesNotFinishKeepsTheOldPack() =
        runTest(UnconfinedTestDispatcher()) {
            val (store, offline) = readyArea()
            store.linksAtOnce = false
            val before = updateBothStyles(store)

            // The pack being made is not part of the area meanwhile.
            assertEquals(3, store.all().size)
            val map = offline.maps().single()
            assertEquals(OfflineMapStatus.Ready, map.status)
            assertEquals(3_100_000, map.bytes)
            assertTrue(offline.downloading)

            advanceTimeBy(REPLACE_TIMEOUT)
            runCurrent()
            assertEquals(listOf(light), store.replacements)
            assertEquals(listOf(light), store.deleted)
            assertEquals(before, store.all().map { it.handle })
            assertEquals(OfflineMapStatus.Ready, offline.maps().single().status)
            assertFalse(offline.downloading)
            assertNull(offline.problem.value)
        }

    @Test
    fun aNewPackIsNotTakenForCompleteBeforeItHasTiles() =
        runTest(UnconfinedTestDispatcher()) {
            val (store, offline) = readyArea()
            store.linksAtOnce = false
            updateBothStyles(store)
            // MapLibre may report a pack with nothing counted yet as complete.
            store.replacementProgress(light, PackProgress(PackState.Complete, 0, 0))
            assertEquals(emptyList(), store.deleted)
            store.replacementProgress(light, PackProgress(PackState.Complete, 100, 100, 3_000_000, 80, 2_500_000))
            assertEquals(listOf(light), store.deleted)
            assertEquals(listOf(light, dark), store.replacements)
        }

    @Test
    fun aReplacementThatCannotBeCreatedKeepsThePacks() =
        runTest(UnconfinedTestDispatcher()) {
            val (store, offline) = readyArea()
            store.failCreate = light
            val before = updateBothStyles(store)
            assertEquals(before, store.all().map { it.handle })
            assertEquals(emptyList(), store.deleted)
            assertEquals(OfflineMapStatus.Ready, offline.maps().single().status)
            assertFalse(offline.downloading)
            assertNull(offline.problem.value)
        }

    @Test
    fun aNewPackStaysWhenTheOldOnesDeleteFailedForTheNextStartToSettle() =
        runTest(UnconfinedTestDispatcher()) {
            val (store, offline) = readyArea()
            val oldLight = store.all().first { it.style == light }.handle
            store.failDeleteOf = oldLight
            updateBothStyles(store)
            // MapLibre may have deleted it all the same: the new pack is not deleted, and the next
            // check of this process leaves it as it is, while the list shows the old one.
            val left = store.all().filter { it.metadata?.replacing == true }
            assertEquals(listOf(light), left.map { it.style })
            assertEquals(listOf(light), store.replacements)
            assertEquals(OfflineMapStatus.Ready, offline.maps().single().status)
            assertFalse(offline.downloading)
            advanceTimeBy(CHECK_INTERVAL)
            runCurrent()
            assertEquals(left.map { it.handle }, store.all().filter { it.metadata?.replacing == true }.map { it.handle })
            assertEquals(emptyList(), store.deleted)
        }

    @Test
    fun deletingAnAreaDuringAReplacementDeletesEveryPackOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val (store, offline) = readyArea()
            store.linksAtOnce = false
            updateBothStyles(store)
            offline.delete("area-1")
            runCurrent()
            assertEquals(emptyList(), store.all())
            assertEquals(listOf(dark, light, light), store.deleted.sorted())
            assertNull(offline.problem.value)
            assertFalse(offline.downloading)
            assertEquals(emptyList(), offline.maps())
        }

    @Test
    fun pausingAnAreaDuringAReplacementDeletesTheNewPack() =
        runTest(UnconfinedTestDispatcher()) {
            val (store, offline) = readyArea()
            store.linksAtOnce = false
            val before = updateBothStyles(store)
            offline.pause("area-1")
            runCurrent()
            assertEquals(before, store.all().map { it.handle })
            assertEquals(OfflineMapStatus.Ready, offline.maps().single().status)
            assertFalse(offline.downloading)
        }

    @Test
    fun aReplacementAnEndedProcessLeftIsSettledAtTheNextCheck() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore()
            val metadata = PackMetadata("area-1", "Map", 1, 8.448, 47.32, 8.625, 47.435, newDataAt = 5)
            val complete = PackProgress(PackState.Complete, 100, 100, 3_000_000, 80, 2_500_000)
            // The light pack's replacement was made, the process ended before the old one went; the
            // dark pack's old one went, the process ended before its replacement was unmarked.
            val oldLight = StoredPack(Pack(light), metadata, complete, light)
            val newLight = StoredPack(Pack(light), metadata.copy(replacing = true), complete, light)
            val newDark = StoredPack(Pack(dark), metadata.copy(replacing = true), complete, dark)
            store.packs.value = PackStoreState.Ready(listOf(oldLight, newLight, newDark))
            val offline = offlineMaps(store)
            assertEquals(OfflineMapStatus.Ready, offline.maps().single().status)

            advanceTimeBy(FIRST_CHECK)
            runCurrent()
            assertEquals(listOf(light), store.deleted)
            assertEquals(listOf(oldLight.handle, newDark.handle), store.all().map { it.handle })
            assertTrue(store.all().none { it.metadata?.replacing == true })
            assertEquals(OfflineMapStatus.Ready, offline.maps().single().status)
        }

    /** A ready area with a light pack's replacement an ended process left beside the old one. */
    private fun TestScope.readyAreaWithALeftover(): Triple<FakeStore, OfflineMaps, StoredPack> {
        val (store, offline) = readyArea()
        val old = store.all().first { it.style == light }
        val leftover = StoredPack(Pack(light), old.metadata!!.copy(replacing = true), old.progress, light)
        store.packs.value = PackStoreState.Ready(store.all() + leftover)
        return Triple(store, offline, leftover)
    }

    @Test
    fun anAreaIsCheckedOnceItsLeftoverIsSettledNotWhileItIs() =
        runTest(UnconfinedTestDispatcher()) {
            val (store, offline, leftover) = readyAreaWithALeftover()
            val gate = CompletableDeferred<Unit>()
            store.deleteGate = gate
            advanceTimeBy(FIRST_CHECK)
            runCurrent()
            // The settle deletes the leftover, as its old pack is still there: no walk until it has.
            assertEquals(listOf(light, dark, light), store.paused)
            assertEquals(emptyList(), store.resumed)
            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf(light), store.deleted)
            assertTrue(store.all().none { it.handle === leftover.handle })
            assertEquals(listOf(light), store.resumed)
            assertTrue(offline.downloading)
        }

    @Test
    fun aLeftoverTheStoreRefusesToSettleDoesNotKeepItsAreaFromUpdates() =
        runTest(UnconfinedTestDispatcher()) {
            val (store, offline, leftover) = readyAreaWithALeftover()
            store.failDeleteOf = leftover.handle
            advanceTimeBy(FIRST_CHECK)
            runCurrent()
            // The settle failed: the area is walked all the same.
            assertEquals(listOf(light), store.resumed)
            advanceTimeBy(QUIET_CHECK)
            runCurrent()
            assertEquals(listOf(light, dark), store.resumed)
            advanceTimeBy(QUIET_CHECK)
            runCurrent()
            assertFalse(offline.downloading)
            assertEquals(OfflineMapStatus.Ready, offline.maps().single().status)

            // And at every later check of this process.
            advanceTimeBy(CHECK_INTERVAL - QUIET_CHECK * 2)
            runCurrent()
            assertEquals(listOf(light, dark, light), store.resumed)
            assertTrue(store.all().any { it.handle === leftover.handle })
            assertEquals(emptyList(), store.deleted)
        }

    @Test
    fun anAreaDeletedWhileItsLeftoverIsSettledIsNotWalked() =
        runTest(UnconfinedTestDispatcher()) {
            val (store, offline, _) = readyAreaWithALeftover()
            val gate = CompletableDeferred<Unit>()
            store.deleteGate = gate
            advanceTimeBy(FIRST_CHECK)
            runCurrent()
            offline.delete("area-1")
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            assertEquals(emptyList(), store.resumed)
            assertEquals(emptyList(), store.all())
            assertFalse(offline.downloading)
            assertNull(offline.problem.value)
        }

    @Test
    fun aSettledLeftoverIsWalkedOnlyOnceTheStoreListsItSettled() =
        runTest(UnconfinedTestDispatcher()) {
            // The light pack's old one went, the process ended before its replacement was unmarked.
            val (store, offline) = readyArea()
            val leftover = store.all().first { it.style == light }
            store.update(leftover.handle) { it.copy(metadata = it.metadata!!.copy(replacing = true)) }
            store.holdListing = true
            advanceTimeBy(FIRST_CHECK)
            runCurrent()
            // Walked now, its mark would write back the replacing the store does not list as gone yet.
            assertEquals(emptyList(), store.resumed)
            store.listHeld()
            runCurrent()
            assertEquals(listOf(light), store.resumed)
            assertTrue(store.all().none { it.metadata?.replacing == true })
            assertEquals(listOf(light, dark), store.updating())
        }

    @Test
    fun aPackMadeToReplaceAnotherIsPartOfItsAreaOnlyOnceTheOtherIsGone() {
        val metadata = PackMetadata("area-1", "Map", 1, 8.448, 47.32, 8.625, 47.435)
        val old = StoredPack(Pack(light), metadata, PackProgress(PackState.Complete, 100, 100), light)
        val new = StoredPack(Pack(light), metadata.copy(replacing = true), PackProgress(PackState.Downloading, 10, 100), light)
        val dark = StoredPack(Pack(dark), metadata, PackProgress(PackState.Complete, 100, 100), dark)
        assertEquals(listOf(old, dark), visible(listOf(old, new, dark)))
        assertEquals(listOf(new, dark), visible(listOf(new, dark)))
        assertEquals(OfflineMapStatus.Ready, areas(listOf(old, new, dark)).single().status)
    }

    @Test
    fun aWalkThatFetchesTilesWithinSecondsStaysReadyWithNewMapData() =
        runTest(UnconfinedTestDispatcher()) {
            val (store, offline) = readyArea()
            advanceTimeBy(FIRST_CHECK)
            runCurrent()
            // A small area of a new release: its tiles come before the walk could show as updating.
            store.progress(light, PackProgress(PackState.Downloading, 21, 100, 500_000, 20, 400_000))
            offline.fetchedNewData()
            store.progress(light, PackProgress(PackState.Complete, 100, 100, 3_000_000, 80, 2_500_000))
            advanceTimeBy(QUIET_CHECK)
            runCurrent()
            assertEquals(OfflineMapStatus.Ready, offline.maps().single().status)
            val first = offline.maps().single().newDataAt
            assertTrue(first > 0)
            // The dark style's walk shares the new tiles and fetches nothing: the time stays.
            advanceTimeBy(QUIET_CHECK)
            runCurrent()
            assertEquals(first, offline.maps().single().newDataAt)
            assertFalse(offline.downloading)
            // The next check finds nothing new.
            advanceTimeBy(CHECK_INTERVAL)
            runCurrent()
            advanceTimeBy(QUIET_CHECK * 2)
            runCurrent()
            assertEquals(listOf(light, dark, light, dark), store.resumed)
            assertEquals(first, offline.maps().single().newDataAt)
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
                        StoredPack(Pack(light), metadata, complete, light),
                        StoredPack(Pack(dark), metadata.copy(updating = false), complete, dark),
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
            val offline = offlineMaps(store, unmetered = { unmetered })
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
            assertEquals(
                true,
                store.packs.value
                    .let { it as PackStoreState.Ready }
                    .packs
                    .single()
                    .metadata
                    ?.newData,
            )
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

    /** An area whose check the app was closed during, before it could tell whether there is new data. */
    private fun TestScope.markedArea(): Pair<FakeStore, OfflineMaps> {
        val store = FakeStore()
        val metadata = PackMetadata("area-1", "Map", 1, 8.448, 47.32, 8.625, 47.435, updating = true)
        val complete = PackProgress(PackState.Complete, 100, 100, 3_000_000, 80, 2_500_000)
        store.packs.value =
            PackStoreState.Ready(
                listOf(StoredPack(Pack(light), metadata, complete, light), StoredPack(Pack(dark), metadata, complete, dark)),
            )
        val offline = offlineMaps(store, unmetered = { false })
        assertEquals(OfflineMapStatus.Paused(100, update = true), offline.maps().single().status)
        return store to offline
    }

    @Test
    fun onMobileDataAMarkLeftWhenTheAppWasClosedIsCheckedAgain() =
        runTest(UnconfinedTestDispatcher()) {
            val (store, offline) = markedArea()
            advanceTimeBy(FIRST_CHECK)
            runCurrent()
            assertEquals(listOf(light), store.resumed)
            advanceTimeBy(QUIET_CHECK)
            runCurrent()
            assertEquals(listOf(light, dark), store.resumed)
            advanceTimeBy(QUIET_CHECK)
            runCurrent()
            // Nothing changed: Ready again, without waiting for Wi-Fi.
            assertEquals(OfflineMapStatus.Ready, offline.maps().single().status)
            assertEquals(emptyList(), store.updating())
            assertFalse(offline.downloading)
            // The closed app's walk may have stored new tiles before it could tell: the area counts as new.
            val newDataAt = offline.maps().single().newDataAt
            assertTrue(newDataAt > 0)
            // A later check that fetches nothing leaves it.
            advanceTimeBy(CHECK_INTERVAL)
            runCurrent()
            advanceTimeBy(QUIET_CHECK * 2)
            runCurrent()
            assertEquals(listOf(light, dark, light, dark), store.resumed)
            assertEquals(newDataAt, offline.maps().single().newDataAt)
        }

    @Test
    fun onMobileDataAMarkLeftWhenTheAppWasClosedFindsNewDataOnceThenWaitsForWiFi() =
        runTest(UnconfinedTestDispatcher()) {
            val (store, offline) = markedArea()
            advanceTimeBy(FIRST_CHECK)
            runCurrent()
            store.progress(light, PackProgress(PackState.Downloading, 5, 100))
            advanceTimeBy(QUIET_CHECK)
            runCurrent()
            assertEquals(listOf(light), store.paused)
            store.progress(light, PackProgress(PackState.Complete, 100, 100))
            val stored = (store.packs.value as PackStoreState.Ready).packs.first { (it.handle as Pack).styleUrl == light }
            assertEquals(true, stored.metadata?.newData)
            assertEquals(OfflineMapStatus.Paused(100, update = true), offline.maps().single().status)
            assertFalse(offline.downloading)
            // The new data is known: later checks on mobile data leave it.
            advanceTimeBy(CHECK_INTERVAL * 3)
            runCurrent()
            assertEquals(listOf(light), store.resumed)
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
    fun aDeleteThatFailsStillEndsTheThrottleOnceThePackIsPaused() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore()
            val offline = offlineMaps(store)
            offline.download(zurich, listOf(light, dark))
            store.progress(light, PackProgress(PackState.Downloading, 10, 100, 100_000, 10, 100_000))
            store.failDelete = true
            offline.delete("area-1")
            assertEquals("Couldn't delete the offline map: disk full", offline.problem.value)
            // Paused, not deleted: its requests may still be on their way until MapLibre says so.
            assertTrue(offline.downloading)
            store.progress(light, PackProgress(PackState.Paused, 10, 100, 100_000, 10, 100_000))
            assertFalse(offline.downloading)
            assertFalse(offline.fetchingChanges.first())
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
    fun anAreaIsNamedAfterTheTownTheMapLabelsInIt() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeStore()
            val offline = offlineMaps(store)
            offline.download(zurich, listOf(light), place = "Zürich")
            assertEquals("Zürich", offline.maps().single().name)
        }

    @Test
    fun areasWithoutALabelledTownAreNamedAfterTheirCentre() {
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
