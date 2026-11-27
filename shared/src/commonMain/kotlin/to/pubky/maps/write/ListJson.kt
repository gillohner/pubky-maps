package to.pubky.maps.write

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
