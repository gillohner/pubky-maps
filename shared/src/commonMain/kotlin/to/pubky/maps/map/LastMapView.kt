package to.pubky.maps.map

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.spatialk.geojson.Position

/** A view of the map: its centre, zoom, and turn ([bearing]) and tilt ([pitch]) in degrees. */
data class MapView(
    val lat: Double,
    val lon: Double,
    val zoom: Double,
    val bearing: Double = 0.0,
    val pitch: Double = 0.0,
) {
    /** True for a view the map can show: every value finite and in MapLibre's range. */
    val isValid: Boolean
        get() =
            lat in -90.0..90.0 &&
                lon in -180.0..180.0 &&
                zoom in 0.0..MAX_ZOOM &&
                bearing in 0.0..360.0 &&
                pitch in 0.0..MAX_PITCH

    fun toCameraPosition(): CameraPosition =
        CameraPosition(center = Position(longitude = lon, latitude = lat), zoom = zoom, bearing = bearing, pitch = pitch)

    companion object {
        /** MapLibre's highest zoom. */
        const val MAX_ZOOM = 25.5

        /** MapLibre's steepest tilt. */
        const val MAX_PITCH = 85.0

        /** The view of [camera], its longitude and turn brought into one turn around the world. */
        fun of(camera: CameraPosition): MapView =
            MapView(
                lat = camera.center.latitude,
                lon = camera.center.longitude.wrapped(),
                zoom = camera.zoom,
                bearing = camera.bearing.mod(360.0),
                pitch = camera.pitch,
            )

        private fun Double.wrapped(): Double = if (this in -180.0..180.0) this else (this + 180.0).mod(360.0) - 180.0
    }
}

/** Where a map shown now opens. */
sealed interface StartView {
    /** The file is still being read (a few milliseconds at start-up). */
    data object Reading : StartView

    /** The view the user left the map at, or null on a first start or after a lost file. */
    data class Read(
        val view: MapView?,
    ) : StartView
}

/**
 * Where the user left the map, so the next start opens there (as Google Maps, CoMaps and OsmAnd do)
 * instead of at the whole world. Kept on this phone only.
 */
interface LastMapViewStore {
    /** [StartView.Reading] until the file has been read at start-up, then the last view saved or read. */
    val startView: StateFlow<StartView>

    /** Keeps [view] for the next map; a newer save replaces one not written yet. A view the map cannot show is ignored. */
    fun save(view: MapView)
}

/** A store in memory, for previews and tests. */
class InMemoryLastMapViewStore(
    initial: MapView? = null,
) : LastMapViewStore {
    private val state = MutableStateFlow<StartView>(StartView.Read(initial))
    override val startView: StateFlow<StartView> = state.asStateFlow()

    override fun save(view: MapView) {
        if (view.isValid) state.value = StartView.Read(view)
    }
}

/**
 * The last view in one JSON file at the path [producePath] gives, written atomically by AndroidX
 * DataStore. [producePath] is called on the store's own thread, so finding the app's folder does not
 * read the disk on the main thread. A file that cannot be read, or holds a view the map cannot show,
 * starts at the whole world. Only one instance may exist per file in a process.
 */
class DataStoreLastMapViewStore(
    fileSystem: FileSystem,
    producePath: () -> Path,
    private val scope: CoroutineScope,
) : LastMapViewStore {
    private val store: DataStore<StoredView> =
        DataStoreFactory.create(
            storage = OkioStorage(fileSystem, StoredViewSerializer, producePath = producePath),
            corruptionHandler = ReplaceFileCorruptionHandler { StoredView() },
            scope = scope,
        )

    private val state = MutableStateFlow<StartView>(StartView.Reading)
    override val startView: StateFlow<StartView> = state.asStateFlow()

    /** The newest view not stored yet; one writer stores them in turn, skipping views a newer one replaced. */
    private val pending = MutableStateFlow<MapView?>(null)

    init {
        scope.launch {
            val fromFile =
                try {
                    store.data.first().view
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The file cannot be opened (or DataStore failed otherwise): the map starts at the
                    // whole world rather than waiting for a view that never comes.
                    null
                }
            // A view saved meanwhile is newer than the file's.
            state.compareAndSet(StartView.Reading, StartView.Read(fromFile))
            pending.filterNotNull().collect { view ->
                try {
                    store.updateData { StoredView(view) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Not stored (a full disk): the next start opens at an older view or the world.
                }
            }
        }
    }

    override fun save(view: MapView) {
        // The map gives its view again each time it is shown (a start, back from Settings): no new write.
        if (!view.isValid || state.value == StartView.Read(view)) return
        state.value = StartView.Read(view)
        pending.value = view
    }
}

/** How long the camera stays still before its view is stored: a pan in a few strokes is stored once. */
const val VIEW_REST_MS = 1_000L

/**
 * The views the camera rests at for [VIEW_REST_MS], each once in a row; [cameras] gives the camera's
 * position, or null while it moves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun restingViews(cameras: Flow<CameraPosition?>): Flow<MapView> =
    cameras
        .transformLatest { camera ->
            if (camera == null) return@transformLatest
            delay(VIEW_REST_MS)
            emit(MapView.of(camera))
        }.distinctUntilChanged()

/**
 * Saves each view the camera rests at ([restingViews] of [cameras]) until cancelled. When the map
 * leaves the screen before a rest is over (Back right after a pan finishes the activity, and this
 * with it), the last position [cameras] gave is saved then, unless the camera was moving (null): a
 * view the camera only passes through (a fling, the move to a linked place) is never saved. The last
 * value of the flow is used, not the map's state at that moment, because the map has already let go
 * of its view by then and no longer says whether its camera moves.
 */
suspend fun saveRestingViews(
    cameras: Flow<CameraPosition?>,
    save: (MapView) -> Unit,
) {
    var latest: CameraPosition? = null
    try {
        restingViews(cameras.onEach { latest = it }).collect { save(it) }
    } finally {
        latest?.let { save(MapView.of(it)) }
    }
}

/** The file's content: the last view, none on a first start. */
internal data class StoredView(
    val view: MapView? = null,
)

private val ViewJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

@Serializable
private data class StoredMapView(
    val lat: Double,
    val lon: Double,
    val zoom: Double,
    val bearing: Double = 0.0,
    val pitch: Double = 0.0,
)

@Serializable
private data class StoredViewFile(
    val version: Int = 1,
    val view: StoredMapView? = null,
)

internal fun encodeStoredView(stored: StoredView): String =
    ViewJson.encodeToString(
        StoredViewFile.serializer(),
        StoredViewFile(view = stored.view?.let { StoredMapView(it.lat, it.lon, it.zoom, it.bearing, it.pitch) }),
    )

/** Reads the stored file; a view the map cannot show is dropped. Throws on a file that is not JSON of this shape. */
internal fun decodeStoredView(json: String): StoredView {
    val file = ViewJson.decodeFromString(StoredViewFile.serializer(), json)
    val view = file.view?.let { MapView(it.lat, it.lon, it.zoom, it.bearing, it.pitch) }?.takeIf { it.isValid }
    return StoredView(view)
}

private object StoredViewSerializer : OkioSerializer<StoredView> {
    override val defaultValue: StoredView = StoredView()

    override suspend fun readFrom(source: BufferedSource): StoredView =
        try {
            decodeStoredView(source.readUtf8())
        } catch (e: SerializationException) {
            throw CorruptionException("Unreadable map view", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Unreadable map view", e)
        }

    override suspend fun writeTo(
        t: StoredView,
        sink: BufferedSink,
    ) {
        sink.writeUtf8(encodeStoredView(t))
    }
}
