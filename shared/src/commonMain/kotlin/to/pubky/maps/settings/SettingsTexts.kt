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
            Service.Overpass -> "Offline place details"
            Service.Valhalla -> "Routes"
            Service.OsmApi -> "Map notes"
            Service.Panoramax -> "Street-level photos"
            Service.PkarrRelay -> "Finding homeservers"
            Service.AuthRelay -> "Sign-in relay"
        }

internal val Service.description: String
    get() =
        when (this) {
            Service.MapStyleLight -> {
                "The look of the map and its tiles in light mode."
            }

            Service.MapStyleDark -> {
                "The look of the map and its tiles in dark mode."
            }

            Service.Photon -> {
                "Photon servers: addresses and places as you type."
            }

            Service.Pinakes -> {
                "Pinakes servers: place details, links and what people use."
            }

            Service.Nexus -> {
                "Nexus servers: tags, reviews and posts people wrote about places."
            }

            Service.Overpass -> {
                "Overpass servers: opening hours, phone numbers and websites of the places in your offline maps. " +
                    "They learn which areas you keep."
            }

            Service.Valhalla -> {
                "Valhalla servers: routes by car, bike and on foot. They learn where each route starts and ends."
            }

            Service.OsmApi -> {
                "OpenStreetMap API servers: the map problems you report, as public notes without your name. " +
                    "They learn each note and where it is."
            }

            Service.Panoramax -> {
                "Panoramax servers: street-level photos of the places you open. They learn where each place you open is."
            }

            Service.PkarrRelay -> {
                "Pkarr relays: find the homeserver that holds someone's Pubky data."
            }

            Service.AuthRelay -> {
                "Carries your sign-in from Pubky Ring to this app."
            }
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
            DataKind.SavedPlaces -> "Bookmarks"
            DataKind.HomeAndWork -> "Home and work"
            DataKind.Lists -> "Collections"
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
            DataKind.SavedPlaces -> {
                "Places you bookmark for later."
            }

            DataKind.HomeAndWork -> {
                "Your home and work addresses."
            }

            DataKind.Lists -> {
                "New collections of places you make (you can pick another place for each collection)."
            }

            DataKind.CheckIns -> {
                "Places you say you were at."
            }

            DataKind.RecordedTracks -> {
                "Routes you record on the move."
            }

            DataKind.LocationHistory -> {
                "Where your phone has been, over time."
            }

            DataKind.PhotoLocations -> {
                "The spot stored inside a photo. Phone only removes it before a photo is posted; camera and phone details are always removed."
            }

            DataKind.Tags -> {
                "Tags you put on places."
            }

            DataKind.ReviewsAndPosts -> {
                "What you write about places."
            }
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
