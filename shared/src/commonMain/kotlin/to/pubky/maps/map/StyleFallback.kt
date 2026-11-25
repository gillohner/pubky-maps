package to.pubky.maps.map

import to.pubky.maps.config.MapStyleSource

/**
 * Which style of an ordered list the map shows. It starts with the first; when the current one fails
 * to load, the next one takes over. Once every style has failed, [current] is null until [retry].
 */
data class StyleFallback(
    val sources: List<MapStyleSource>,
    val index: Int = 0,
) {
    init {
        require(sources.isNotEmpty()) { "A style fallback needs at least one style" }
        require(index in 0..sources.size) { "Index $index is outside 0..${sources.size}" }
    }

    /** The style to show, or null when every style failed. */
    val current: MapStyleSource? get() = sources.getOrNull(index)

    val allFailed: Boolean get() = index == sources.size

    /**
     * The state after the style at [styleUrl] failed to load. A failure of a style that is no longer
     * current (a late report from an earlier attempt) changes nothing.
     */
    fun failed(styleUrl: String): StyleFallback = if (current?.styleUrl == styleUrl) copy(index = index + 1) else this

    /** Starts again from the first style. */
    fun retry(): StyleFallback = copy(index = 0)
}
