package to.pubky.maps.config

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
     * Changes the settings and stores them, in the repository's own scope, so leaving a screen or
     * turning the phone does not cancel a change on its way to disk. [transform] gets the stored
     * settings at that moment. When the file cannot be written (a full disk), nothing changes, so the
     * screens keep showing what is stored.
     */
    fun update(transform: (AppSettings) -> AppSettings): Job
}

/**
 * The addresses of [service]'s list once the settings were read from the phone (the flow holds null
 * until then): the user's list, or the defaults when the settings file was lost
 * ([AppSettings.afterLostFile]). For requests the user did not start, such as a note sent from the
 * outbox as the app starts: the built-in defaults read before the file could send to a server the
 * user removed.
 */
suspend fun Flow<AppSettings?>.loadedServers(service: Service): List<String> = filterNotNull().first().servers[service].map { it.url }

/**
 * Settings in one JSON file at [path], written atomically by AndroidX DataStore. A file that cannot
 * be read is replaced by [AppSettings.afterLostFile]; while the file cannot be opened at all, the
 * same settings are used and reading is tried again. Only one instance may exist per file in a
 * process.
 */
class DataStoreSettingsRepository(
    fileSystem: FileSystem,
    path: Path,
    private val scope: CoroutineScope,
    codec: SettingsCodec = SettingsCodec(),
) : SettingsRepository {
    private val store: DataStore<AppSettings> =
        DataStoreFactory.create(
            storage = OkioStorage(fileSystem, CodecSerializer(codec), producePath = { path }),
            corruptionHandler = ReplaceFileCorruptionHandler { AppSettings.afterLostFile() },
            scope = scope,
        )

    override val settings: StateFlow<AppSettings?> =
        store.data
            .retryWhen { cause, attempt ->
                if (cause !is IOException) return@retryWhen false
                if (attempt == 0L) emit(AppSettings.afterLostFile())
                delay(READ_RETRY_DELAY_MS * (attempt + 1).coerceAtMost(MAX_READ_RETRY_FACTOR))
                true
            }.stateIn(scope, SharingStarted.Eagerly, null)

    override fun update(transform: (AppSettings) -> AppSettings): Job =
        scope.launch {
            try {
                store.updateData(transform)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not stored (disk full, or a change that no longer applies); the settings flow still
                // holds the stored state, so the screens show it.
            }
        }

    private companion object {
        const val READ_RETRY_DELAY_MS = 1_000L
        const val MAX_READ_RETRY_FACTOR = 30L
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
