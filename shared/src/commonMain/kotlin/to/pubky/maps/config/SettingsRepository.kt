package to.pubky.maps.config

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.IOException
import okio.Path

/** The user's settings, kept across app starts. */
interface SettingsRepository {
    /** The current settings; null only until the stored settings have been read at start-up. */
    val settings: StateFlow<AppSettings?>

    /**
     * Changes the settings and stores them. When the file cannot be written (a full disk), nothing
     * changes, so the screen keeps showing what is stored.
     */
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

/**
 * Settings in one JSON file at [path], written atomically by AndroidX DataStore. A file that cannot
 * be read is replaced by the defaults; one that cannot be opened at all leaves the defaults in use.
 * Only one instance may exist per file in a process.
 */
class DataStoreSettingsRepository(
    fileSystem: FileSystem,
    path: Path,
    scope: CoroutineScope,
    codec: SettingsCodec = SettingsCodec(),
) : SettingsRepository {
    private val store: DataStore<AppSettings> =
        DataStoreFactory.create(
            storage = OkioStorage(fileSystem, CodecSerializer(codec), producePath = { path }),
            corruptionHandler = ReplaceFileCorruptionHandler { AppSettings() },
            scope = scope,
        )

    override val settings: StateFlow<AppSettings?> =
        store.data
            .catch { e -> if (e is IOException) emit(AppSettings()) else throw e }
            .stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        try {
            store.updateData(transform)
        } catch (e: IOException) {
            // Not stored; the settings flow still holds the stored state, so the screen shows it.
        }
    }
}

private class CodecSerializer(
    private val codec: SettingsCodec,
) : OkioSerializer<AppSettings> {
    override val defaultValue: AppSettings = AppSettings()

    override suspend fun readFrom(source: BufferedSource): AppSettings =
        try {
            codec.decode(source.readUtf8())
        } catch (e: UnreadableSettingsException) {
            throw CorruptionException("Unreadable settings file", e)
        }

    override suspend fun writeTo(
        t: AppSettings,
        sink: BufferedSink,
    ) {
        sink.writeUtf8(codec.encode(t))
    }
}
