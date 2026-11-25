package to.pubky.maps.settings

import to.pubky.maps.config.DataKind
import to.pubky.maps.config.OpenInApp
import to.pubky.maps.config.Service
import to.pubky.maps.config.StorageChoice

// What the settings screens call things. Inline until the first translation moves them to resources.

internal val Service.title: String
    get() =
        when (this) {
            Service.MapStyleLight -> "Map style, light"
            Service.MapStyleDark -> "Map style, dark"
            Service.Photon -> "Search"
            Service.Pinakes -> "Places"
            Service.Nexus -> "Tags and posts"
            Service.PkarrRelay -> "Finding homeservers"
            Service.AuthRelay -> "Sign-in relay"
        }

internal val Service.description: String
    get() =
        when (this) {
            Service.MapStyleLight -> "The look of the map and its tiles in light mode."
            Service.MapStyleDark -> "The look of the map and its tiles in dark mode."
            Service.Photon -> "Photon servers: addresses and places as you type."
            Service.Pinakes -> "Pinakes servers: place details, links and what people use."
            Service.Nexus -> "Nexus servers: tags, reviews and posts people wrote about places."
            Service.PkarrRelay -> "Pkarr relays: find the homeserver that holds someone's Pubky data."
            Service.AuthRelay -> "Carries your sign-in from Pubky Ring to this app."
        }

internal val StorageChoice.title: String
    get() =
        when (this) {
            StorageChoice.PhoneOnly -> "Phone only"
            StorageChoice.HomeserverPrivate -> "Homeserver, private"
            StorageChoice.Public -> "Public"
        }

internal val StorageChoice.explanation: String
    get() =
        when (this) {
            StorageChoice.PhoneOnly -> "Stays on this phone. Pubky Maps never uploads it."
            StorageChoice.HomeserverPrivate -> "On your homeserver, not public, readable by the homeserver operator."
            StorageChoice.Public -> "On your homeserver, readable by anyone, marked Public in the app."
        }

internal val DataKind.title: String
    get() =
        when (this) {
            DataKind.SavedPlaces -> "Saved places"
            DataKind.HomeAndWork -> "Home and work"
            DataKind.Lists -> "Lists"
            DataKind.CheckIns -> "Check-ins"
            DataKind.RecordedTracks -> "Recorded tracks"
            DataKind.LocationHistory -> "Location history"
            DataKind.PhotoLocations -> "Photo locations"
            DataKind.Tags -> "Tags"
            DataKind.ReviewsAndPosts -> "Reviews and posts"
        }

internal val DataKind.description: String
    get() =
        when (this) {
            DataKind.SavedPlaces -> "Places you save for later."
            DataKind.HomeAndWork -> "Your home and work addresses."
            DataKind.Lists -> "New lists of places you make."
            DataKind.CheckIns -> "Places you say you were at."
            DataKind.RecordedTracks -> "Routes you record on the move."
            DataKind.LocationHistory -> "Where your phone has been, over time."
            DataKind.PhotoLocations -> "The spot saved inside a photo. Phone only removes it before a photo is posted."
            DataKind.Tags -> "Tags you put on places."
            DataKind.ReviewsAndPosts -> "What you write about places."
        }

/** Why a kind that is public by nature has no choice. */
internal const val PUBLIC_BY_NATURE = "Always public: anyone can read them and see they are yours."

internal val OpenInApp.title: String
    get() =
        when (this) {
            OpenInApp.GoogleMaps -> "Google Maps"
            OpenInApp.AppleMaps -> "Apple Maps"
            OpenInApp.Waze -> "Waze"
            OpenInApp.OrganicMaps -> "Organic Maps"
            OpenInApp.CoMaps -> "CoMaps"
            OpenInApp.OsmAnd -> "OsmAnd"
            OpenInApp.HereWeGo -> "HERE WeGo"
            OpenInApp.AnyGeoApp -> "Other map apps"
        }

/** A note under the app's name, where its name alone does not say what happens. */
internal val OpenInApp.note: String?
    get() =
        when (this) {
            OpenInApp.AppleMaps -> "Opens Apple's web map. There is no Apple Maps app for Android."
            OpenInApp.AnyGeoApp -> "Any map app on this phone, picked from a list (a geo: link)."
            else -> null
        }

internal const val SYNONYM_BADGE = "Synonym"
internal const val SYNONYM_EXPLANATION =
    "Marked Synonym: run by Synonym, the company behind Pubky, or by the Pubky Maps team. " +
        "The map keeps working without them."
