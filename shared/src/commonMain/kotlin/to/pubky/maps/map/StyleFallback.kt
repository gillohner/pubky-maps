package to.pubky.maps.map

import to.pubky.maps.config.ServerEntry

/**
 * Which style of an ordered list the map shows. It starts with the first; when the current one fails
 * to load, the next one takes over. Once every style has failed, [current] is null until [retry].
 */
data class StyleFallback(
    val sources: List<ServerEntry>,
    val index: Int = 0,
) {
    init {
        require(sources.isNotEmpty()) { "A style fallback needs at least one style" }
        require(index in 0..sources.size) { "Index $index is outside 0..${sources.size}" }
    }

    /** The style to show, or null when every style failed. */
    val current: ServerEntry? get() = sources.getOrNull(index)

    val allFailed: Boolean get() = index == sources.size

    /**
     * The state after the style at [styleUrl] failed to load. A failure of a style that is no longer
     * current (a late report from an earlier attempt) changes nothing.
     */
    fun failed(styleUrl: String): StyleFallback = if (current?.url == styleUrl) copy(index = index + 1) else this

    /**
     * The style of the other theme ([other]) at the same place in its list, so an offline area takes
     * both themes from the server the map has fallen back to (the default lists have one provider
     * per place); its last style when [other] is shorter. Null when every style failed.
     */
    fun otherTheme(other: List<ServerEntry>): ServerEntry? {
        if (current == null) return null
        return other.getOrNull(index.coerceAtMost(other.lastIndex))
    }

    /** Starts again from the first style. */
    fun retry(): StyleFallback = copy(index = 0)
}
