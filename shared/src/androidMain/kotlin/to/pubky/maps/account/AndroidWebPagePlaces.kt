package to.pubky.maps.account

import to.pubky.maps.core.needsPlaceCard
import to.pubky.maps.place.PlaceData

/** The bookmarked or tagged web pages that are places, by the core's `needsPlaceCard` rule and the Pinakes cards of [data]. */
fun androidWebPagePlaces(data: () -> PlaceData): WebPagePlaces =
    PinakesWebPagePlaces(::needsPlaceCard, data, now = System::currentTimeMillis)
