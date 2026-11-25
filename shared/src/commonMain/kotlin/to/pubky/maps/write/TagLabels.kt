package to.pubky.maps.write

import to.pubky.maps.place.PlacePageState

/** The spec's label length, in characters (code points). */
const val TAG_LABEL_MAX = 20

/** At most this many suggested labels are offered. */
private const val MAX_SUGGESTIONS = 10

/** Latin letters with diacritics and ligatures, folded to ASCII so "café" and "cafe" are one tag. */
private val FOLDS: Map<Char, String> =
    buildMap {
        fun put(
            ascii: String,
            letters: String,
        ) = letters.forEach { put(it, ascii) }
        put("a", "àáâãäåāăąǎ")
        put("c", "çćĉċč")
        put("d", "ďđ")
        put("e", "èéêëēĕėęě")
        put("g", "ĝğġģ")
        put("h", "ĥħ")
        put("i", "ìíîïĩīĭįıǐ")
        put("j", "ĵ")
        put("k", "ķ")
        put("l", "ĺļľŀł")
        put("n", "ñńņňŉ")
        put("o", "òóôõöøōŏőǒ")
        put("r", "ŕŗř")
        put("s", "śŝşšș")
        put("t", "ţťŧț")
        put("u", "ùúûüũūŭůűųǔ")
        put("w", "ŵ")
        put("y", "ýÿŷ")
        put("z", "źżž")
        put("ss", "ß")
        put("ae", "æ")
        put("oe", "œ")
        put("th", "þ")
    }

/** [text] lower-cased with Latin diacritics folded to ASCII; other scripts stay as they are. */
fun asciiFold(text: String): String =
    buildString {
        text.lowercase().forEach { c -> append(FOLDS[c] ?: c) }
    }

/** Why a typed label cannot be a tag, or the label it becomes. */
sealed interface LabelResult {
    data class Valid(
        val label: String,
    ) : LabelResult

    data class Invalid(
        val reason: String,
    ) : LabelResult
}

/**
 * The tag label for what the user typed, by the spec's rules: folded to ASCII and lower case, spaces
 * become `-`, `:` and `,` are dropped (the spec forbids them and whitespace), 1 to 20 characters.
 */
fun tagLabel(text: String): LabelResult {
    val folded =
        asciiFold(text.trim())
            .replace(Regex("[:,]"), "")
            .replace(Regex("[\\s_]+"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
    val length = folded.codePointCount()
    return when {
        folded.isEmpty() -> LabelResult.Invalid("Type a word for the tag.")
        length > TAG_LABEL_MAX -> LabelResult.Invalid("A tag has at most $TAG_LABEL_MAX characters ($length now).")
        else -> LabelResult.Valid(folded)
    }
}

private fun String.codePointCount(): Int {
    var count = 0
    var i = 0
    while (i < length) {
        i += if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return count
}

/**
 * Labels to offer with one tap: the place's own top tags on Pubky (that the user did not use yet),
 * then the words of its OpenStreetMap category and cuisine and of the category the map showed, each
 * as a valid label, without repeats.
 */
fun tagSuggestions(
    page: PlacePageState?,
    mine: Set<String>,
): List<String> {
    val fromPubky =
        page
            ?.social
            ?.tags
            .orEmpty()
            .sortedByDescending { it.count }
            .map { it.label }
    val card = page?.card
    val categoryWords =
        listOfNotNull(
            card?.category?.substringAfter('/'),
            page?.place?.category,
        ) + page?.osm?.cuisine.orEmpty()
    return (fromPubky + categoryWords)
        .mapNotNull { (tagLabel(it) as? LabelResult.Valid)?.label }
        .filter { it !in mine && it !in NOT_LABELS }
        .distinct()
        .take(MAX_SUGGESTIONS)
}

/** OSM values that say nothing as a tag. */
private val NOT_LABELS = setOf("yes", "no", "point", "other", "unknown")
