package to.pubky.maps.write

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val json = Json { ignoreUnknownKeys = true }

/** A list post's name, description (null when it has none) and places. */
internal data class ListPost(
    val name: String,
    val description: String?,
    val items: List<String>,
)

/** A list post's content, from the post JSON the core built (`{"kind":"collection","content":"{…}"}`); null when it is not a list. */
internal fun listOfPost(postJson: String): ListPost? {
    val post = runCatching { json.parseToJsonElement(postJson).jsonObject }.getOrNull() ?: return null
    if (post["kind"]?.jsonPrimitive?.contentOrNull != "collection") return null
    val content =
        post["content"]?.jsonPrimitive?.contentOrNull?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: return null
    val name = content["name"]?.jsonPrimitive?.contentOrNull ?: return null
    val items =
        runCatching { content["items"]?.jsonArray.orEmpty() }
            .getOrDefault(emptyList())
            .mapNotNull { (it as? JsonObject)?.get("uri")?.jsonPrimitive?.contentOrNull }
    val description = content["description"]?.let { (it as? JsonPrimitive)?.contentOrNull }
    return ListPost(name, listDescription(description), items)
}

/** A list's description as kept and shown: trimmed, null when there is none. */
internal fun listDescription(description: String?): String? = description?.trim()?.ifEmpty { null }

/** Whether a list post URI is under `/priv`, and its post id; null when it is not a post URI. */
internal fun listAddress(uri: String): Pair<Boolean, String>? {
    val private = "/priv/social/v1/posts/" in uri
    val marker = if (private) "/priv/social/v1/posts/" else "/pub/social/v1/posts/"
    if (!uri.startsWith("pubky://") || marker !in uri) return null
    val id = uri.substringAfter(marker).takeIf { it.isNotEmpty() && '/' !in it } ?: return null
    return private to id
}
