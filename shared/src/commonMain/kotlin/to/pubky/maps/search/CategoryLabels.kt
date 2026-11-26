package to.pubky.maps.search

// What a place is called for an OpenStreetMap tag whose value does not read as a name by itself.
// `categoryOf` turns other values into words ("bus_stop" -> "Bus stop") and adds "shop" to other
// shop values ("gift" -> "Gift shop"). The names are the nouns Google Maps, CoMaps and OsmAnd show
// for these places ("Bakery", not "Bakery shop"), in British English as the rest of the app.

/** Labels by OSM `key/value`, for tags that the generic rules would word badly. */
internal val TAG_LABELS: Map<String, String> =
    mapOf(
        // A shop value that is already what the shop is called.
        "shop/bakery" to "Bakery",
        "shop/supermarket" to "Supermarket",
        "shop/convenience" to "Convenience store",
        "shop/department_store" to "Department store",
        "shop/mall" to "Shopping centre",
        "shop/kiosk" to "Kiosk",
        "shop/butcher" to "Butcher",
        "shop/greengrocer" to "Greengrocer",
        "shop/deli" to "Deli",
        "shop/confectionery" to "Sweet shop",
        "shop/seafood" to "Fishmonger",
        "shop/beverages" to "Drinks shop",
        "shop/hairdresser" to "Hairdresser",
        "shop/beauty" to "Beauty salon",
        "shop/massage" to "Massage",
        "shop/florist" to "Florist",
        "shop/optician" to "Optician",
        "shop/chemist" to "Drugstore",
        "shop/perfumery" to "Perfumery",
        "shop/newsagent" to "Newsagent",
        "shop/laundry" to "Laundry",
        "shop/dry_cleaning" to "Dry cleaner",
        "shop/tailor" to "Tailor",
        "shop/locksmith" to "Locksmith",
        "shop/travel_agency" to "Travel agency",
        "shop/car" to "Car dealer",
        "shop/doityourself" to "DIY store",
        "shop/garden_centre" to "Garden centre",
        "shop/variety_store" to "Variety store",
        "shop/boutique" to "Boutique",
        "shop/jewelry" to "Jeweller",
        "shop/funeral_directors" to "Funeral director",
        "shop/pawnbroker" to "Pawnbroker",
        "shop/bookmaker" to "Bookmaker",
        "shop/wholesale" to "Wholesaler",
        "shop/copyshop" to "Copy shop",
        "shop/second_hand" to "Second-hand shop",
        "shop/storage_rental" to "Self storage",
        // A shop value in the plural, where the shop takes the singular.
        "shop/books" to "Bookshop",
        "shop/shoes" to "Shoe shop",
        "shop/toys" to "Toy shop",
        "shop/watches" to "Watch shop",
        "shop/tyres" to "Tyre shop",
        "shop/video_games" to "Video game shop",
        "shop/hearing_aids" to "Hearing aid shop",
        // Other tags whose value is an abbreviation, a plural or not the usual name.
        "amenity/atm" to "ATM",
        "amenity/bbq" to "Barbecue",
        "amenity/townhall" to "Town hall",
        "amenity/doctors" to "Doctor",
        "amenity/veterinary" to "Vet",
        "amenity/biergarten" to "Beer garden",
        "amenity/bureau_de_change" to "Currency exchange",
        "amenity/fuel" to "Petrol station",
        "amenity/ice_cream" to "Ice cream shop",
        "aeroway/aerodrome" to "Airport",
        "leisure/pitch" to "Sports pitch",
    )
