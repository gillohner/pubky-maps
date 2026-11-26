package to.pubky.maps.map

/**
 * The source layers of the labels of towns, villages and parts of towns in the base map schemas the
 * app's styles use: OpenMapTiles' `place` (OpenFreeMap) and Shortbread's `place_labels` (VersaTiles).
 */
val PlaceLabelSourceLayers = setOf("place", "place_labels")

/**
 * A place label the map draws: the tile feature's [properties] and its [distance] from the centre of
 * the frame it was found in (any unit, the same for every label of one frame).
 */
data class PlaceLabel(
    val properties: Map<String, String>,
    val distance: Double,
)

/**
 * The name of an offline area: the most important place the map labels in it ("Bern", "Lugano"), as
 * Google Maps names a downloaded area after its place. A city comes before a town, a town
 * before a village, a village before the parts of a town. Countries, regions and islands are left
 * out: one name for every area in them, or for none of the towns on them. Among places of one kind
 * the larger one wins (Shortbread's `population`, OpenMapTiles' `rank`), then the one nearest the
 * centre. Null when the map labels no such place in the frame.
 */
fun areaPlaceName(labels: List<PlaceLabel>): String? =
    labels
        .mapNotNull { label ->
            val p = label.properties
            val importance = PLACE_KINDS[p["class"] ?: p["kind"]] ?: return@mapNotNull null
            val name = (p["name"] ?: p["name_int"])?.trim()?.ifEmpty { null } ?: return@mapNotNull null
            Candidate(name, importance, p["population"]?.toDoubleOrNull() ?: 0.0, p["rank"]?.toDoubleOrNull(), label.distance)
        }.minWithOrNull(
            compareBy<Candidate> { it.importance }
                .thenByDescending { it.population }
                .thenBy { it.rank ?: Double.MAX_VALUE }
                .thenBy { it.distance },
        )?.name

private data class Candidate(
    val name: String,
    val importance: Int,
    val population: Double,
    val rank: Double?,
    val distance: Double,
)

/**
 * The kinds of place an area is named after, by importance (0 the highest): OpenMapTiles' `class`
 * and Shortbread's `kind`. Shortbread says `capital` and `state_capital` for the capitals among its
 * cities; they count as cities, so a larger city beside a small capital still names the area.
 */
private val PLACE_KINDS =
    mapOf(
        "capital" to 0,
        "state_capital" to 0,
        "city" to 0,
        "town" to 1,
        "village" to 2,
        "suburb" to 3,
        "quarter" to 4,
        "hamlet" to 5,
        "neighbourhood" to 6,
    )
