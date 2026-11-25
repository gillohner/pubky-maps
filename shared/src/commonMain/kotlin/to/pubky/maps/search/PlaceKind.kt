package to.pubky.maps.search

/**
 * The kind of a place as search shows it: the icon on its row and on its pin. Coarser than its
 * category ("Museum", "Tram stop"), so a handful of icons cover everything.
 */
enum class PlaceKind {
    Food,
    Transit,
    Culture,
    Education,
    Shop,
    Nature,

    /** A town, district, region or country. */
    Area,

    /** A street or a street address. */
    Street,

    /** Typed coordinates. */
    Point,
    Other,
}

private val FOOD_AMENITIES = setOf("restaurant", "cafe", "bar", "pub", "fast_food", "ice_cream", "biergarten", "food_court")
private val FOOD_SHOPS = setOf("bakery", "pastry", "confectionery", "deli", "chocolate", "coffee")
private val TRANSIT_TAGS =
    mapOf(
        "highway" to setOf("bus_stop", "platform"),
        "railway" to setOf("station", "halt", "stop", "tram_stop", "platform", "subway_entrance"),
        "amenity" to setOf("bus_station", "ferry_terminal"),
        "aeroway" to setOf("aerodrome", "terminal"),
    )
private val CULTURE_AMENITIES = setOf("theatre", "arts_centre", "cinema", "place_of_worship", "concert_hall")
private val CULTURE_TOURISM = setOf("museum", "gallery", "attraction", "artwork", "zoo", "aquarium", "theme_park")
private val EDUCATION_AMENITIES = setOf("university", "college", "school", "library", "kindergarten")
private val NATURE_LEISURE = setOf("park", "nature_reserve", "garden", "playground")
private val AREA_TYPES = setOf("city", "district", "locality", "county", "state", "country")

/** The kind of an OSM place from its main tag and Photon's type. */
internal fun placeKindOf(
    key: String?,
    value: String?,
    photonType: String?,
): PlaceKind =
    when {
        key == "public_transport" || TRANSIT_TAGS[key]?.contains(value) == true -> PlaceKind.Transit
        key == "amenity" && value in FOOD_AMENITIES -> PlaceKind.Food
        key == "shop" && value in FOOD_SHOPS -> PlaceKind.Food
        key == "amenity" && value in CULTURE_AMENITIES -> PlaceKind.Culture
        key == "tourism" && value in CULTURE_TOURISM -> PlaceKind.Culture
        key == "historic" -> PlaceKind.Culture
        key == "amenity" && value in EDUCATION_AMENITIES -> PlaceKind.Education
        key == "shop" -> PlaceKind.Shop
        key == "natural" || (key == "leisure" && value in NATURE_LEISURE) || (key == "tourism" && value == "viewpoint") -> PlaceKind.Nature
        key == "place" || key == "boundary" || photonType in AREA_TYPES -> PlaceKind.Area
        photonType == "street" || (key == "building" && photonType == "house") -> PlaceKind.Street
        else -> PlaceKind.Other
    }

/** The kind of a Pinakes card from its schema.org type and its ancestors. */
internal fun placeKindOf(typePath: List<String>): PlaceKind =
    when {
        typePath.any { it in setOf("FoodEstablishment", "Bakery") } -> {
            PlaceKind.Food
        }

        typePath.any { it in setOf("BusStop", "BusStation", "TrainStation", "SubwayStation", "Airport") } -> {
            PlaceKind.Transit
        }

        typePath.any { it in setOf("Museum", "PlaceOfWorship", "TouristAttraction", "PerformingArtsTheater", "MovieTheater", "Zoo") } -> {
            PlaceKind.Culture
        }

        typePath.any { it in setOf("EducationalOrganization", "Library") } -> {
            PlaceKind.Education
        }

        typePath.any { it == "Store" } -> {
            PlaceKind.Shop
        }

        typePath.any { it in setOf("Park", "Mountain", "BodyOfWater", "Landform") } -> {
            PlaceKind.Nature
        }

        typePath.any { it in setOf("AdministrativeArea", "City") } -> {
            PlaceKind.Area
        }

        else -> {
            PlaceKind.Other
        }
    }
