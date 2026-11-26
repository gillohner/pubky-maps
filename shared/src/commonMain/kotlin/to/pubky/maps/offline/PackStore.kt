package to.pubky.maps.offline

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where offline packs live: MapLibre's offline database on the phone ([MapLibrePackStore]), a fake in
 * tests. A pack is the map of one area in one style; [OfflineMaps] keeps one per map style of an area.
 */
interface PackStore {
    val state: Flow<PackStoreState>

    /** Registers a paused pack of [bounds] in the style at [styleUrl] and returns its handle. */
    suspend fun create(
        styleUrl: String,
        bounds: AreaBounds,
        maxZoom: Int,
        metadata: PackMetadata,
    ): Any

    fun resume(handle: Any)

    fun pause(handle: Any)

    suspend fun delete(handle: Any)

    suspend fun setMetadata(
        handle: Any,
        metadata: PackMetadata,
    )
}

sealed interface PackStoreState {
    data object Loading : PackStoreState

    data class Ready(
        val packs: List<StoredPack>,
    ) : PackStoreState

    data class Failed(
        val message: String,
    ) : PackStoreState
}

/** One pack as the store reports it; [handle] goes back to the store to act on it. */
data class StoredPack(
    val handle: Any,
    val metadata: PackMetadata?,
    val progress: PackProgress,
)

enum class PackState { Unknown, Downloading, Paused, Complete }

data class PackProgress(
    val state: PackState = PackState.Unknown,
    val completedResources: Long = 0,
    val requiredResources: Long = 0,
    val completedBytes: Long = 0,
    val completedTiles: Long = 0,
    val completedTileBytes: Long = 0,
    /** The last failure of a resource, while the download tries it again. */
    val error: String? = null,
)

/** What the app stores with a pack: which area it belongs to and what to call it. */
@Serializable
data class PackMetadata(
    val area: String,
    val name: String,
    val createdAt: Long,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    /** The app stopped the download at the limit of one download ([OfflineLimits]). */
    val stopped: Boolean = false,
    /**
     * A check for new map data walks the pack and has not finished. MapLibre reports a pack that is
     * not downloading as complete once as many tiles are linked to it as its sources need, and the
     * tiles of the release before stay linked: without this mark, an update that stopped half way
     * would look complete while the area lacks the new release's tiles.
     */
    val updating: Boolean = false,
    /**
     * A check's walk of the pack was still downloading after [QUIET_CHECK]: the map server has new
     * data for it. Without it, an [updating] mark may be one a check left when the app was closed
     * before it could tell, so a walk on mobile data may still clear it when nothing changed.
     */
    val newData: Boolean = false,
) {
    fun encode(): ByteArray = json.encodeToString(serializer(), this).encodeToByteArray()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Null for bytes the app did not write. */
        fun decode(bytes: ByteArray?): PackMetadata? =
            bytes?.let { runCatching { json.decodeFromString(serializer(), it.decodeToString()) }.getOrNull() }
    }
}
