package to.pubky.maps.search

import to.pubky.maps.links.GeoPoint
import to.pubky.maps.links.OsmId
import to.pubky.maps.place.PlaceRef
import to.pubky.maps.place.PlaceSource
import to.pubky.maps.place.formatCoordinate
import kotlin.math.min

/**
 * How much a kind of place stands out, as map apps weigh it: a mountain or a stadium is what most
 * people mean by its name; a bus stop is named after the place it serves.
 */
enum class Prominence(
    val factor: Double,
) {
    /** Transit stops, platforms, signposts, information boards: named after something else. */
    Minor(0.5),
    Ordinary(1.0),

    /** Towns and districts, peaks, museums, stadiums, zoos, universities, monuments. */
    Landmark(1.15),
}

/** One row of the results: the place, and what the ranking knew about it. */
data class SearchResult(
    val place: PlaceRef,
    /** Position in Photon's answer (0 first), null when Photon did not list it. */
    val photonRank: Int? = null,
    /** Position in Pinakes' answer (0 first), null when Pinakes did not list it. */
    val pinakesRank: Int? = null,
    /** Public Pubky collections holding the place (Pinakes). */
    val collections: Int = 0,
    /** How prominent this kind of place is, see [Prominence]. */
    val prominence: Prominence = Prominence.Ordinary,
    /** What kind of place it is, for its icon. */
    val kind: PlaceKind = PlaceKind.Other,
    val score: Double = 0.0,
) {
    /** True when people referenced the place on Pubky (Pinakes knows it). */
    val onPubky: Boolean get() = pinakesRank != null
}

/**
 * Merges Photon's and Pinakes' answers and orders them.
 *
 * Merge: a Pinakes card and a Photon result that are the same OpenStreetMap element become one row
 * that keeps the Pinakes card (its link, name and point) and Photon's address and category.
 *
 * Ranking, every part from 0 to 1:
 * - text: how the title matches the query: equal 1.0, starts with it as whole words 0.9, holds
 *   every query word as a whole word 0.8, starts with it inside a word 0.75, every query word starts
 *   a word of the title 0.7, the title holds it 0.5, title and address together hold every word 0.4,
 *   anything else a source returned 0.2 (accents and case ignored);
 * - near: 1 / (1 + distance / scale), scale = the map's centre-to-corner distance (1 km to 1000 km),
 *   so a place one screen away counts half;
 * - order: 1 / (1 + position) in the source's own answer, the better of the two sources;
 * - social: 0 for places nobody referenced on Pubky, else 0.5 plus up to 0.5 for public collections
 *   holding it (full at 5).
 *
 * score = (0.55 text + 0.25 near + 0.20 order) x prominence + 0.15 social, prominence from
 * [Prominence] (0.5, 1 or 1.15). Text leads because a search names what it wants; a nearby, socially
 * known or prominent place wins among similar matches; a far exact match ("lugano" seen from Zürich)
 * still beats a near weak one.
 */
object SearchRanking {
    const val TEXT_WEIGHT = 0.55
    const val NEAR_WEIGHT = 0.25
    const val ORDER_WEIGHT = 0.20
    const val SOCIAL_WEIGHT = 0.15
    const val COLLECTIONS_FOR_FULL_SOCIAL = 5

    /** At most this many rows are shown. */
    const val MAX_RESULTS = 15

    fun rank(
        query: String,
        area: SearchArea,
        photon: List<PhotonPlace>,
        pinakes: List<PinakesPlace>,
    ): List<SearchResult> {
        val q = normalizeForMatch(query)
        return merge(photon, pinakes)
            .map { it.copy(score = score(q, area, it)) }
            .sortedWith(compareByDescending<SearchResult> { it.score }.thenBy { it.place.title })
            .withoutRepeatedStops()
            .take(MAX_RESULTS)
    }

    internal fun merge(
        photon: List<PhotonPlace>,
        pinakes: List<PinakesPlace>,
    ): List<SearchResult> {
        // Several Pinakes cards can be one OSM element (its OSM card and its Wikidata card): the OSM
        // card speaks for it, else the better ranked one.
        val pinakesByOsm = LinkedHashMap<OsmId, PinakesPlace>()
        val pinakesOther = ArrayList<PinakesPlace>()
        for (card in pinakes) {
            val osm = card.osm
            if (osm == null) {
                pinakesOther += card
                continue
            }
            val held = pinakesByOsm[osm]
            pinakesByOsm[osm] =
                when {
                    held == null -> {
                        card
                    }

                    held.uri != osm.url && card.uri == osm.url -> {
                        card.copy(
                            rank = held.rank,
                            collections = maxOf(card.collections, held.collections),
                        )
                    }

                    else -> {
                        held.copy(collections = maxOf(card.collections, held.collections))
                    }
                }
        }
        val rows = ArrayList<SearchResult>()
        val photonOsm = photon.mapNotNull { it.osm }.toSet()
        for (place in photon) {
            val card = place.osm?.let { pinakesByOsm[it] }
            rows += if (card != null) merged(place, card) else fromPhoton(place)
        }
        for ((osm, card) in pinakesByOsm) if (osm !in photonOsm) rows += fromPinakes(card)
        for (card in pinakesOther) rows += fromPinakes(card)
        return rows
    }

    private fun fromPhoton(place: PhotonPlace) =
        SearchResult(
            place =
                PlaceRef(
                    uri = place.osm?.url ?: geoUri(place.point),
                    name = place.title,
                    point = place.point,
                    osm = place.osm,
                    source = PlaceSource.Photon,
                    zoom = zoomFor(place.type),
                    address = place.address,
                    category = categoryOf(place.osmKey, place.osmValue, place.type),
                ),
            photonRank = place.rank,
            prominence = prominenceOf(place.osmKey, place.osmValue),
            kind = placeKindOf(place.osmKey, place.osmValue, place.type),
        )

    private fun fromPinakes(card: PinakesPlace) =
        SearchResult(
            place =
                PlaceRef(
                    uri = card.osm?.url ?: card.uri,
                    name = card.name,
                    point = card.point,
                    osm = card.osm,
                    source = PlaceSource.Pinakes,
                    zoom = PLACE_ZOOM,
                    category = card.type?.let(::schemaTypeLabel),
                ),
            pinakesRank = card.rank,
            collections = card.collections,
            prominence = prominenceOf(card.type),
            kind = placeKindOf(card.typePath),
        )

    private fun merged(
        place: PhotonPlace,
        card: PinakesPlace,
    ) = SearchResult(
        place =
            PlaceRef(
                // The OSM link: a place picked on our map is tagged on its OSM link.
                uri = card.osm?.url ?: card.uri,
                name = card.name ?: place.title,
                point = card.point,
                osm = card.osm,
                source = PlaceSource.Pinakes,
                zoom = zoomFor(place.type),
                address = place.address,
                category = categoryOf(place.osmKey, place.osmValue, place.type) ?: card.type?.let(::schemaTypeLabel),
            ),
        photonRank = place.rank,
        pinakesRank = card.rank,
        collections = card.collections,
        prominence = prominenceOf(place.osmKey, place.osmValue),
        kind = placeKindOf(place.osmKey, place.osmValue, place.type).takeIf { it != PlaceKind.Other } ?: placeKindOf(card.typePath),
    )

    internal fun score(
        normalizedQuery: String,
        area: SearchArea,
        result: SearchResult,
    ): Double {
        val place = result.place
        val text = textMatch(normalizedQuery, place.title, place.address)
        val near = place.point?.let { 1.0 / (1.0 + distanceMeters(area.centre, it) / area.proximityScaleMeters) } ?: 0.0
        val order = listOfNotNull(result.photonRank, result.pinakesRank).maxOfOrNull { 1.0 / (1.0 + it) } ?: 0.0
        val social =
            if (result.onPubky) {
                0.5 + 0.5 * min(1.0, result.collections.toDouble() / COLLECTIONS_FOR_FULL_SOCIAL)
            } else {
                0.0
            }
        return (TEXT_WEIGHT * text + NEAR_WEIGHT * near + ORDER_WEIGHT * order) * result.prominence.factor + SOCIAL_WEIGHT * social
    }

    /** How well [title] (and [address]) match the normalized query, 0.2 to 1. */
    internal fun textMatch(
        normalizedQuery: String,
        title: String,
        address: String?,
    ): Double {
        if (normalizedQuery.isEmpty()) return 0.0
        val t = normalizeForMatch(title)
        val queryWords = normalizedQuery.split(' ')
        val titleWords = t.split(' ')
        return when {
            t == normalizedQuery -> 1.0

            t.startsWith("$normalizedQuery ") -> 0.9

            queryWords.all { it in titleWords } -> 0.8

            t.startsWith(normalizedQuery) -> 0.75

            queryWords.all { w -> titleWords.any { it.startsWith(w) } } -> 0.7

            normalizedQuery in t -> 0.5

            address != null &&
                normalizeForMatch("$title $address").split(' ').let { words ->
                    queryWords.all { w -> words.any { it.startsWith(w) } }
                }
            -> 0.4

            else -> 0.2
        }
    }
}

/** The category of a platform: a platform repeats any stop of its name. */
private const val PLATFORM = "Platform"

/** Stops of one name and kind closer than this are one stop (its platforms and poles). */
private const val SAME_STOP_METERS = 150.0

/**
 * Drops a transit stop, platform or signpost that repeats a better ranked one of the same name and
 * category (a platform repeats any stop of its name) within [SAME_STOP_METERS]: OSM maps one stop as
 * several platforms and poles, map apps show the stop once. Places people use on Pubky always stay.
 */
internal fun List<SearchResult>.withoutRepeatedStops(): List<SearchResult> {
    val kept = ArrayList<SearchResult>(size)
    for (row in this) {
        val point = row.place.point
        val repeat =
            row.prominence == Prominence.Minor &&
                !row.onPubky &&
                point != null &&
                kept.any { k ->
                    k.prominence == Prominence.Minor &&
                        (k.place.category == row.place.category || PLATFORM in setOf(k.place.category, row.place.category)) &&
                        normalizeForMatch(k.place.title) == normalizeForMatch(row.place.title) &&
                        k.place.point?.let { distanceMeters(it, point) <= SAME_STOP_METERS } == true
                }
        if (!repeat) kept += row
    }
    return kept
}

/** The zoom a place is shown at, from Photon's kind: a country whole, a street, a building close. */
internal fun zoomFor(photonType: String?): Double =
    when (photonType) {
        "country" -> 5.0
        "state" -> 8.0
        "county" -> 10.0
        "city" -> 12.0
        "district", "locality" -> 14.0
        "street" -> 16.0
        else -> PLACE_ZOOM
    }

internal const val PLACE_ZOOM = 17.0

private fun geoUri(point: GeoPoint): String = "geo:${formatCoordinate(point.lat)},${formatCoordinate(point.lon)}"

/** OSM tags of things named after the place they serve: a "Zoo" bus stop is not the zoo. */
private val MINOR_TAGS: Map<String, Set<String>?> =
    mapOf(
        "highway" to setOf("bus_stop", "platform", "stop", "street_lamp", "milestone"),
        "railway" to setOf("platform", "tram_stop", "stop", "subway_entrance", "platform_edge"),
        "public_transport" to setOf("platform", "stop_position", "stop_area", "pole"),
        "information" to null,
        "amenity" to setOf("bicycle_rental", "parking_entrance", "taxi", "car_sharing", "vending_machine"),
        "tourism" to setOf("information"),
        "entrance" to null,
    )

/** OSM tags of landmarks: what a name alone usually means. */
private val LANDMARK_TAGS: Map<String, Set<String>?> =
    mapOf(
        "place" to setOf("city", "town", "village", "suburb", "quarter", "borough", "island"),
        "natural" to setOf("peak", "volcano", "glacier", "bay", "beach"),
        "tourism" to setOf("attraction", "museum", "zoo", "theme_park", "aquarium", "gallery"),
        "historic" to setOf("castle", "monument", "memorial", "ruins", "archaeological_site"),
        "leisure" to setOf("stadium", "park", "nature_reserve"),
        "amenity" to setOf("university", "theatre"),
        "aeroway" to setOf("aerodrome"),
        "waterway" to setOf("river", "waterfall"),
    )

private fun Map<String, Set<String>?>.has(
    key: String,
    value: String?,
): Boolean = containsKey(key) && (this[key] == null || value in this[key].orEmpty())

internal fun prominenceOf(
    key: String?,
    value: String?,
): Prominence =
    when {
        key == null -> Prominence.Ordinary
        MINOR_TAGS.has(key, value) -> Prominence.Minor
        LANDMARK_TAGS.has(key, value) -> Prominence.Landmark
        else -> Prominence.Ordinary
    }

/** The same for a Pinakes card's schema.org type. */
internal fun prominenceOf(schemaType: String?): Prominence =
    when (schemaType) {
        in MINOR_SCHEMA_TYPES -> Prominence.Minor
        in LANDMARK_SCHEMA_TYPES -> Prominence.Landmark
        else -> Prominence.Ordinary
    }

private val MINOR_SCHEMA_TYPES = setOf("BusStop", "BusStation")
private val LANDMARK_SCHEMA_TYPES =
    setOf(
        "City",
        "Museum",
        "Zoo",
        "StadiumOrArena",
        "Mountain",
        "LandmarksOrHistoricalBuildings",
        "TouristAttraction",
        "CollegeOrUniversity",
    )

/** A short label for a place's OSM tag ("Museum", "Bus stop", "Street", "Bakery"; see [TAG_LABELS]). */
internal fun categoryOf(
    key: String?,
    value: String?,
    photonType: String?,
): String? {
    if (key == null || value == null) return null
    return when {
        key == "highway" && photonType == "street" -> "Street"
        key == "place" -> humanize(value)
        "$key/$value" in TAG_LABELS -> TAG_LABELS.getValue("$key/$value")
        key == "building" && value == "yes" -> "Building"
        key == "shop" && value == "yes" -> "Shop"
        key == "shop" -> "${humanize(value)} shop"
        key == "boundary" -> null
        value == "yes" -> humanize(key)
        else -> humanize(value)
    }
}

/** "bus_stop" -> "Bus stop". */
private fun humanize(tag: String): String = tag.replace('_', ' ').trim().replaceFirstChar { it.uppercaseChar() }

/** "CafeOrCoffeeShop" -> "Cafe or coffee shop", "Museum" -> "Museum". */
internal fun schemaTypeLabel(type: String): String? {
    if (type == "Place" || type == "Thing") return null
    val words = type.split(Regex("(?<=[a-z])(?=[A-Z])")).filter { it.isNotEmpty() }
    return words.mapIndexed { i, w -> if (i == 0) w else w.lowercase() }.joinToString(" ")
}

/**
 * Text as the ranking compares it: lower case, accents removed (ü as u, ß as ss), punctuation as
 * spaces, single spaces.
 */
fun normalizeForMatch(text: String): String {
    val out = StringBuilder(text.length)
    for (c in text.lowercase()) {
        val folded = FOLDS[c]
        when {
            folded != null -> out.append(folded)
            c.isLetterOrDigit() -> out.append(c)
            else -> out.append(' ')
        }
    }
    return out
        .toString()
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")
}

private val FOLDS: Map<Char, String> =
    buildMap {
        fun fold(
            chars: String,
            to: String,
        ) = chars.forEach { put(it, to) }
        fold("àáâãäåāăą", "a")
        fold("çćĉċč", "c")
        fold("ďđ", "d")
        fold("èéêëēĕėęě", "e")
        fold("ĝğġģ", "g")
        fold("ìíîïĩīĭįı", "i")
        fold("ñńņňŉ", "n")
        fold("òóôõöøōŏő", "o")
        fold("ŕŗř", "r")
        fold("śŝşšș", "s")
        fold("ţťŧț", "t")
        fold("ùúûüũūŭůűų", "u")
        fold("ýÿŷ", "y")
        fold("źżž", "z")
        put('ß', "ss")
        put('æ', "ae")
        put('œ', "oe")
        put('ł', "l")
    }
