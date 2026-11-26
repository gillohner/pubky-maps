package to.pubky.maps.search

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.IOException
import okio.Path
import to.pubky.maps.links.OsmId
import to.pubky.maps.links.OsmType
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource

/** A past search: the text searched for, or the place picked from the results. */
data class RecentSearch(
    val query: String,
    val place: PlaceRef? = null,
) {
    /** One entry per place, and per text (case and accents ignored). */
    internal val key: String get() = place?.uri ?: "q:${normalizeForMatch(query)}"
}

/** The user's recent searches, newest first. Kept on this phone only. */
interface RecentSearchStore {
    val entries: StateFlow<List<RecentSearch>>

    /** Puts [entry] first; an older entry for the same text or place goes. */
    fun add(entry: RecentSearch)

    /** Removes [entry] (the entry for its text or place) and keeps the others. */
    fun remove(entry: RecentSearch)

    fun clear()

    companion object {
        /** How many entries are kept. */
        const val MAX_ENTRIES = 30
    }
}

/** [entry] first; a place picked for a text also replaces that text's own entry. */
internal fun List<RecentSearch>.withAdded(entry: RecentSearch): List<RecentSearch> {
    val textKey = RecentSearch(entry.query).key
    val rest = filterNot { it.key == entry.key || (entry.place != null && it.place == null && it.key == textKey) }
    return (listOf(entry) + rest).take(RecentSearchStore.MAX_ENTRIES)
}

/** Without [entry]: the one entry with its key goes, the others keep their order. */
internal fun List<RecentSearch>.withRemoved(entry: RecentSearch): List<RecentSearch> = filterNot { it.key == entry.key }

/** Recent searches in memory, for previews and tests. */
class InMemoryRecentSearchStore(
    initial: List<RecentSearch> = emptyList(),
) : RecentSearchStore {
    private val state = MutableStateFlow(initial)
    override val entries: StateFlow<List<RecentSearch>> = state.asStateFlow()

    override fun add(entry: RecentSearch) = state.update { it.withAdded(entry) }

    override fun remove(entry: RecentSearch) = state.update { it.withRemoved(entry) }

    override fun clear() {
        state.value = emptyList()
    }
}

/**
 * Recent searches in one JSON file at [path], written atomically by AndroidX DataStore. A file that
 * cannot be read starts an empty history. Only one instance may exist per file in a process.
 */
class DataStoreRecentSearchStore(
    fileSystem: FileSystem,
    path: Path,
    private val scope: CoroutineScope,
) : RecentSearchStore {
    private val store: DataStore<List<RecentSearch>> =
        DataStoreFactory.create(
            storage = OkioStorage(fileSystem, RecentSearchSerializer, producePath = { path }),
            corruptionHandler = ReplaceFileCorruptionHandler { emptyList() },
            scope = scope,
        )

    override val entries: StateFlow<List<RecentSearch>> =
        store.data
            .retryWhen { cause, attempt ->
                // The file cannot be opened (not unreadable: that starts an empty history): show none
                // meanwhile and try again, so the history comes back without a restart.
                if (cause !is IOException) return@retryWhen false
                if (attempt == 0L) emit(emptyList())
                delay(READ_RETRY_DELAY_MS * (attempt + 1).coerceAtMost(MAX_READ_RETRY_FACTOR))
                true
            }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Orders the writes as they were asked for (each runs in its own coroutine). */
    private val writes = Mutex()

    override fun add(entry: RecentSearch) = write { it.withAdded(entry) }

    override fun remove(entry: RecentSearch) = write { it.withRemoved(entry) }

    override fun clear() = write { emptyList() }

    private fun write(transform: (List<RecentSearch>) -> List<RecentSearch>) {
        // Started at once, so the lock is queued in call order: "kunsthaus" then its picked place.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                writes.withLock { store.updateData(transform) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not stored (a full disk): the history is a convenience, the search itself worked.
            }
        }
    }

    private companion object {
        const val READ_RETRY_DELAY_MS = 1_000L
        const val MAX_READ_RETRY_FACTOR = 30L
    }
}

private object RecentSearchSerializer : OkioSerializer<List<RecentSearch>> {
    override val defaultValue: List<RecentSearch> = emptyList()

    override suspend fun readFrom(source: BufferedSource): List<RecentSearch> =
        try {
            decodeRecentSearches(source.readUtf8())
        } catch (e: SerializationException) {
            throw CorruptionException("Unreadable recent searches", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Unreadable recent searches", e)
        }

    override suspend fun writeTo(
        t: List<RecentSearch>,
        sink: BufferedSink,
    ) {
        sink.writeUtf8(encodeRecentSearches(t))
    }
}

internal fun encodeRecentSearches(entries: List<RecentSearch>): String =
    SearchJson.encodeToString(StoredRecents.serializer(), StoredRecents(entries = entries.map { it.toStored() }))

/** Reads the stored file; entries this version cannot use are skipped, not the whole history. */
internal fun decodeRecentSearches(json: String): List<RecentSearch> =
    SearchJson
        .decodeFromString(StoredRecents.serializer(), json)
        .entries
        .mapNotNull { it.toRecent() }
        .take(RecentSearchStore.MAX_ENTRIES)

@Serializable
private data class StoredRecents(
    val version: Int = 1,
    val entries: List<StoredRecent> = emptyList(),
)

@Serializable
private data class StoredRecent(
    val query: String,
    val place: StoredPlace? = null,
)

@Serializable
private data class StoredPlace(
    val uri: String,
    val name: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    @SerialName("osm_type") val osmType: String? = null,
    @SerialName("osm_id") val osmId: Long? = null,
    val source: String,
    val zoom: Double? = null,
    val address: String? = null,
    val category: String? = null,
)

private fun RecentSearch.toStored() =
    StoredRecent(
        query = query,
        place =
            place?.let {
                StoredPlace(
                    uri = it.uri,
                    name = it.name,
                    lat = it.point?.lat,
                    lon = it.point?.lon,
                    osmType =
                        it.osm
                            ?.type
                            ?.name
                            ?.lowercase(),
                    osmId = it.osm?.id,
                    source = it.source.name,
                    zoom = it.zoom,
                    address = it.address,
                    category = it.category,
                )
            },
    )

private fun StoredRecent.toRecent(): RecentSearch? {
    val stored = place ?: return RecentSearch(query).takeIf { query.isNotBlank() }
    val source = PlaceSource.entries.firstOrNull { it.name == stored.source } ?: return null
    val point = if (stored.lat != null && stored.lon != null) geoPointOrNull(stored.lat, stored.lon) ?: return null else null
    val osmType = OsmType.entries.firstOrNull { it.name.lowercase() == stored.osmType }
    val osm = if (osmType != null && stored.osmId != null && stored.osmId > 0) OsmId(osmType, stored.osmId) else null
    return RecentSearch(
        query = query,
        place =
            PlaceRef(
                uri = stored.uri,
                name = stored.name,
                point = point,
                osm = osm,
                source = source,
                zoom = stored.zoom,
                address = stored.address,
                category = stored.category,
            ),
    )
}
