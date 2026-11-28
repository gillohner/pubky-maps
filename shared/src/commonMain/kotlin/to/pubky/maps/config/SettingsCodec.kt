package to.pubky.maps.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The settings file's format version. Raise it, and add a migration, whenever a stored field changes meaning. */
const val SETTINGS_SCHEMA_VERSION = 1

/** Turns a settings file written by an older version into the shape of the next version. */
fun interface SettingsMigration {
    fun migrate(document: JsonObject): JsonObject
}

/** Thrown for a settings file that cannot be read at all; the store then starts again from the defaults. */
class UnreadableSettingsException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Reads and writes [AppSettings] as a JSON document with a schema version.
 *
 * Reading is lenient so that no app version loses the user's settings: a file from an older version
 * goes through [migrations] one version at a time ([migrations] maps a version to the step that
 * lifts it to the next one); a file from a newer version is read as far as this version understands
 * it; a server list, storage kind or app this version does not know is left out, a bad address or
 * credit link is dropped, and a storage choice the kind does not offer becomes the kind's most private
 * choice (never its default, which may be less private than what the user chose).
 */
class SettingsCodec(
    private val schemaVersion: Int = SETTINGS_SCHEMA_VERSION,
    private val migrations: Map<Int, SettingsMigration> = emptyMap(),
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            prettyPrint = true
        }

    fun encode(settings: AppSettings): String = json.encodeToString(StoredSettings.serializer(), settings.toStored(schemaVersion))

    fun decode(text: String): AppSettings {
        try {
            var document = json.parseToJsonElement(text).jsonObject
            // A file without a version is read as the first version.
            var version = document[VERSION_KEY]?.jsonPrimitive?.intOrNull ?: 1
            while (version < schemaVersion) {
                val step = migrations[version] ?: throw UnreadableSettingsException("No migration from settings version $version")
                document = JsonObject(step.migrate(document) + (VERSION_KEY to JsonPrimitive(version + 1)))
                version++
            }
            return json.decodeFromJsonElement(StoredSettings.serializer(), document).toSettings()
        } catch (e: UnreadableSettingsException) {
            throw e
        } catch (e: SerializationException) {
            throw UnreadableSettingsException("The settings file is not valid", e)
        } catch (e: Exception) {
            // A migration step that fails on an unexpected file must not stop the app from starting.
            throw UnreadableSettingsException("The settings file could not be read", e)
        }
    }

    private companion object {
        const val VERSION_KEY = "schemaVersion"
    }
}

@Serializable
private data class StoredLink(
    val label: String,
    val url: String,
)

@Serializable
private data class StoredServer(
    val name: String = "",
    val url: String,
    val attribution: List<StoredLink> = emptyList(),
)

/** The file format. Maps are keyed by the stable keys of [Service], [DataKind] and [OpenInApp]. */
@Serializable
private data class StoredSettings(
    val schemaVersion: Int = 1,
    val servers: Map<String, List<StoredServer>> = emptyMap(),
    val storage: Map<String, String> = emptyMap(),
    val openIn: Map<String, Boolean> = emptyMap(),
    /** Written only when off, so the default (on) can change for users who never touched it. */
    val followLocalRules: Boolean? = null,
    /** Written only when off, as [followLocalRules]. */
    val voiceGuidance: Boolean? = null,
)

private fun AppSettings.toStored(schemaVersion: Int) =
    StoredSettings(
        schemaVersion = schemaVersion,
        servers =
            serverOverrides
                .mapKeys { it.key.key }
                .mapValues { (_, list) ->
                    list.map { entry ->
                        StoredServer(entry.name, entry.url, entry.attribution.map { StoredLink(it.label, it.url) })
                    }
                },
        storage = storageChoices.entries.associate { it.key.key to it.value.key },
        openIn = openInChoices.mapKeys { it.key.key },
        followLocalRules = if (followLocalRules) null else false,
        voiceGuidance = if (voiceGuidance) null else false,
    )

private fun StoredSettings.toSettings(): AppSettings {
    val serverOverrides =
        servers.entries
            .mapNotNull { (key, stored) ->
                val service = Service.fromKey(key) ?: return@mapNotNull null
                val list =
                    stored
                        .mapNotNull { server ->
                            val url = ServerUrl.parse(server.url) as? ServerUrl.Valid ?: return@mapNotNull null
                            ServerEntry(
                                name = server.name.ifBlank { url.host },
                                url = url.url,
                                attribution =
                                    server.attribution
                                        .filter { ServerUrl.parse(it.url) is ServerUrl.Valid }
                                        .map { AttributionLink(it.label, it.url) },
                            )
                        }.distinctBy { it.url }
                if (list.isEmpty()) null else service to list
            }.toMap()
    val storageChoices =
        storage.entries
            .mapNotNull { (key, value) ->
                val kind = DataKind.fromKey(key) ?: return@mapNotNull null
                val choice = StorageChoice.fromKey(value)?.takeIf { it in kind.choices } ?: kind.mostPrivate
                kind to choice
            }.toMap()
    val openInChoices = openIn.entries.mapNotNull { (key, on) -> OpenInApp.fromKey(key)?.let { it to on } }.toMap()
    return AppSettings(serverOverrides, storageChoices, openInChoices, followLocalRules ?: true, voiceGuidance ?: true)
}
