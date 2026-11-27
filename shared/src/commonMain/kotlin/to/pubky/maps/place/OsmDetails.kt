package to.pubky.maps.place

/** How a place is for wheelchair users, from OSM `wheelchair`. */
enum class Wheelchair(
    val text: String,
) {
    Yes("Wheelchair accessible"),
    Limited("Partly wheelchair accessible"),
    No("Not wheelchair accessible"),
}

/**
 * A place's OpenStreetMap details, from the OSM tags on its Pinakes card or those an offline area
 * keeps for it. Only what the page shows;
 * every field is null (or empty) when OSM has none.
 */
data class OsmDetails(
    val openingHours: String?,
    val phone: String?,
    /** An https or http link. */
    val website: String?,
    val email: String?,
    val wheelchair: Wheelchair?,
    /** "Italian", "Coffee shop" (OSM `cuisine`, one per `;`). */
    val cuisine: List<String>,
    val operator: String?,
    /** The floor ("Floor 2"), from OSM `level`. */
    val level: String?,
    /** Payments in bitcoin, as OSM `payment:lightning`, `payment:onchain` and `currency:XBT` say. */
    val bitcoin: List<String>,
) {
    val isEmpty: Boolean
        get() =
            openingHours == null && phone == null && website == null && email == null && wheelchair == null &&
                cuisine.isEmpty() && operator == null && level == null && bitcoin.isEmpty()

    companion object {
        /** Every OSM tag [from] reads: the offline areas keep these of their places. */
        val KEYS: List<String> =
            listOf(
                "opening_hours",
                "phone",
                "contact:phone",
                "website",
                "contact:website",
                "email",
                "contact:email",
                "wheelchair",
                "cuisine",
                "operator",
                "level",
                "payment:lightning",
                "payment:lightning_contactless",
                "payment:onchain",
                "currency:XBT",
            )

        /** The details in [facts] (OSM tag names as keys); null when there are none. */
        fun from(facts: Map<String, String>): OsmDetails? {
            fun first(vararg keys: String): String? =
                keys.firstNotNullOfOrNull { key ->
                    facts[key]
                        ?.split(';')
                        ?.map { it.trim() }
                        ?.firstOrNull { it.isNotEmpty() }
                }
            val details =
                OsmDetails(
                    openingHours = facts["opening_hours"]?.trim()?.ifEmpty { null },
                    phone = first("phone", "contact:phone"),
                    website = first("website", "contact:website")?.let(::webLink),
                    email = first("email", "contact:email")?.takeIf { '@' in it },
                    wheelchair =
                        when (facts["wheelchair"]?.trim()) {
                            "yes", "designated" -> Wheelchair.Yes
                            "limited" -> Wheelchair.Limited
                            "no" -> Wheelchair.No
                            else -> null
                        },
                    cuisine =
                        facts["cuisine"]
                            ?.split(';')
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            ?.map(::humanize)
                            .orEmpty(),
                    operator = first("operator"),
                    level = first("level")?.let { "Floor $it" },
                    bitcoin =
                        buildList {
                            if (facts["payment:lightning"] == "yes") add("Lightning")
                            if (facts["payment:lightning_contactless"] == "yes") add("Lightning contactless")
                            if (facts["payment:onchain"] == "yes") add("Bitcoin on-chain")
                            if (isEmpty() && facts["currency:XBT"] == "yes") add("Bitcoin")
                        },
                )
            return details.takeUnless { it.isEmpty }
        }

        /** A website as OSM has it ("www.example.ch" or "https://..."), as a link the app may open. */
        internal fun webLink(value: String): String? {
            val trimmed = value.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
            return when {
                trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true) -> trimmed
                "://" in trimmed -> null
                else -> "https://$trimmed"
            }
        }

        private fun humanize(tag: String): String = tag.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
    }
}
