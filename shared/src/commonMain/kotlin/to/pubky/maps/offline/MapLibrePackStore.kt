package to.pubky.maps.offline

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.scan
import org.maplibre.compose.offline.DownloadProgress
import org.maplibre.compose.offline.DownloadStatus
import org.maplibre.compose.offline.OfflinePack
import org.maplibre.compose.offline.OfflinePackDefinition
import org.maplibre.compose.offline.OfflineStorage
import org.maplibre.compose.offline.OfflineStorageState
import org.maplibre.spatialk.geojson.BoundingBox

/**
 * The packs in MapLibre's offline database ([storage]), downloaded for a screen of [pixelRatio]
 * (the sprites of that density). Once a pack is downloaded, the map uses it by itself when the
 * phone has no connection.
 */
class MapLibrePackStore(
    private val storage: () -> OfflineStorage,
    private val pixelRatio: Float,
) : PackStore {
    @OptIn(ExperimentalCoroutinesApi::class)
    override val state: Flow<PackStoreState> =
        flowOf(Unit).flatMapLatest { storage().state }.flatMapLatest { state ->
            when (state) {
                is OfflineStorageState.Ready -> {
                    val packs = state.packs.toList()
                    if (packs.isEmpty()) {
                        flowOf(PackStoreState.Ready(emptyList()))
                    } else {
                        combine(packs.map { it.stored() }) { PackStoreState.Ready(it.toList()) }
                    }
                }

                is OfflineStorageState.Failed -> {
                    flowOf(PackStoreState.Failed(state.cause.message ?: "The offline storage could not be opened"))
                }

                else -> {
                    flowOf(PackStoreState.Loading)
                }
            }
        }

    override suspend fun create(
        styleUrl: String,
        bounds: AreaBounds,
        maxZoom: Int,
        metadata: PackMetadata,
    ): Any =
        storage().create(
            OfflinePackDefinition.TilePyramid(
                styleUrl = styleUrl,
                bounds = BoundingBox(west = bounds.west, south = bounds.south, east = bounds.east, north = bounds.north),
                pixelRatio = pixelRatio,
                minZoom = 0.0,
                maxZoom = maxZoom.toDouble(),
            ),
            metadata.encode(),
        )

    override suspend fun createLike(
        handle: Any,
        metadata: PackMetadata,
    ): Any = storage().create((handle as OfflinePack).definition, metadata.encode())

    override fun resume(handle: Any) = storage().resume(handle as OfflinePack)

    override fun pause(handle: Any) = storage().pause(handle as OfflinePack)

    override suspend fun delete(handle: Any) = storage().delete(handle as OfflinePack)

    override suspend fun setMetadata(
        handle: Any,
        metadata: PackMetadata,
    ) = (handle as OfflinePack).setMetadata(metadata.encode())
}

/**
 * A pack with its metadata and progress; a failure keeps the counts of the last report. The fold's
 * empty start is left out, so a list that changes does not show every pack at 0 % for a moment.
 */
private fun OfflinePack.stored(): Flow<StoredPack> =
    combine(metadata, downloadProgress.scan(PackProgress()) { last, next -> next.applyTo(last) }.drop(1)) { bytes, progress ->
        StoredPack(this, PackMetadata.decode(bytes), progress, definition.styleUrl)
    }

internal fun DownloadProgress.applyTo(last: PackProgress): PackProgress =
    when (this) {
        is DownloadProgress.Healthy -> {
            PackProgress(
                state =
                    when (status) {
                        DownloadStatus.Complete -> PackState.Complete
                        DownloadStatus.Downloading -> PackState.Downloading
                        DownloadStatus.Paused -> PackState.Paused
                        else -> PackState.Unknown
                    },
                completedResources = completedResourceCount,
                requiredResources = requiredResourceCount,
                completedBytes = completedResourceBytes,
                completedTiles = completedTileCount,
                completedTileBytes = completedTileBytes,
            )
        }

        is DownloadProgress.Error -> {
            last.copy(state = PackState.Downloading, error = message)
        }

        is DownloadProgress.TileLimitExceeded -> {
            last.copy(state = PackState.Paused, error = "More than $limit tiles")
        }

        else -> {
            last
        }
    }
