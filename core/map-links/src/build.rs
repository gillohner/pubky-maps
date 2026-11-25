//! Outgoing links: a place, or directions to it, in another map app.

use percent_encoding::{AsciiSet, NON_ALPHANUMERIC, utf8_percent_encode};
use url::form_urlencoded::byte_serialize;

use crate::coord::pair;
use crate::{OsmRef, Point, omaps};

/// The zoom used when the place has none.
const DEFAULT_ZOOM: f64 = 16.0;

/// A link format of another app.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum App {
    /// Google Maps URLs (`https://www.google.com/maps/…?api=1`): the Google Maps app when
    /// installed, else the browser.
    GoogleMaps,
    /// Android's `google.navigation:` intent: starts turn-by-turn navigation in Google Maps.
    GoogleNavigation,
    /// Apple Maps unified URLs (`https://maps.apple.com/place|directions`).
    AppleMaps,
    /// `https://waze.com/ul`.
    Waze,
    /// `https://osmand.net/map?pin=` (OsmAnd's web map, which the app also opens).
    OsmAnd,
    /// `om://map?v=1&ll=&n=` (the installed app).
    OrganicMaps,
    /// `https://omaps.app/<ge0>/<name>` (the app when installed, else its web page).
    OrganicMapsWeb,
    /// `cm://map?v=1&ll=&n=` (the installed app).
    CoMaps,
    /// `https://comaps.at/<ge0>/<name>`.
    CoMapsWeb,
    /// `https://share.here.com/l|r/…`.
    HereWeGo,
    /// `https://www.openstreetmap.org/…`.
    OpenStreetMap,
    /// `geo:<lat>,<lon>?z=&q=<lat>,<lon>(<name>)` for any map app.
    Geo,
}

/// How to travel for directions.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum TravelMode {
    Driving,
    Walking,
    Cycling,
    Transit,
}

/// A place to show.
#[derive(Debug, Clone, PartialEq)]
pub struct Place {
    pub point: Point,
    pub name: Option<String>,
    pub osm: Option<OsmRef>,
    pub zoom: Option<f64>,
}

/// What the link should open.
#[derive(Debug, Clone, PartialEq)]
pub enum Target {
    /// The place on the map.
    Place(Place),
    /// Directions from where the user is to the place.
    Directions {
        destination: Place,
        mode: TravelMode,
    },
}

/// Encodes everything but unreserved characters (RFC 3986), for path segments.
const SEGMENT: &AsciiSet = &NON_ALPHANUMERIC
    .remove(b'-')
    .remove(b'.')
    .remove(b'_')
    .remove(b'~');

fn q(value: &str) -> String {
    byte_serialize(value.as_bytes()).collect()
}

fn segment(value: &str) -> String {
    utf8_percent_encode(value, SEGMENT).to_string()
}

fn name_of(place: &Place) -> Option<&str> {
    place
        .name
        .as_deref()
        .map(str::trim)
        .filter(|n| !n.is_empty())
}

fn zoom_of(place: &Place) -> u8 {
    // Clamped, so the cast cannot truncate.
    place
        .zoom
        .filter(|z| z.is_finite())
        .unwrap_or(DEFAULT_ZOOM)
        .clamp(1.0, 20.0)
        .round() as u8
}

/// The link that opens `target` in `app`, or `None` when the app has no link for it (for
/// example directions in Organic Maps, or transit in Waze).
pub fn build(app: App, target: &Target) -> Option<String> {
    match target {
        Target::Place(place) => build_place(app, place),
        Target::Directions { destination, mode } => build_directions(app, destination, *mode),
    }
}

fn build_place(app: App, place: &Place) -> Option<String> {
    let ll = pair(place.point);
    let name = name_of(place);
    let zoom = zoom_of(place);
    Some(match app {
        App::GoogleMaps => format!("https://www.google.com/maps/search/?api=1&query={ll}"),
        App::GoogleNavigation => return None,
        App::AppleMaps => {
            let mut url = format!("https://maps.apple.com/place?coordinate={ll}");
            if let Some(name) = name {
                url.push_str(&format!("&name={}", segment(name)));
            }
            url
        }
        App::Waze => format!("https://waze.com/ul?ll={ll}"),
        App::OsmAnd => {
            let (lat, lon) = ll.split_once(',')?;
            format!("https://osmand.net/map?pin={ll}#{zoom}/{lat}/{lon}")
        }
        App::OrganicMaps | App::CoMaps => {
            let scheme = if app == App::OrganicMaps { "om" } else { "cm" };
            let mut url = format!("{scheme}://map?v=1&ll={ll}");
            if let Some(name) = name {
                url.push_str(&format!("&n={}", segment(name)));
            }
            url.push_str(&format!("&z={zoom}"));
            url
        }
        App::OrganicMapsWeb | App::CoMapsWeb => {
            let host = if app == App::OrganicMapsWeb {
                "omaps.app"
            } else {
                "comaps.at"
            };
            let mut url = format!(
                "https://{host}/{}",
                omaps::encode(place.point, f64::from(zoom))
            );
            if let Some(name) = name {
                url.push('/');
                url.push_str(&omaps::encode_name(name));
            }
            url
        }
        App::HereWeGo => {
            let mut url = format!("https://share.here.com/l/{ll}");
            if let Some(name) = name {
                url.push(',');
                url.push_str(&segment(name));
            }
            url.push_str(&format!("?z={zoom}"));
            url
        }
        App::OpenStreetMap => match place.osm {
            Some(osm) => osm.url(),
            None => {
                let (lat, lon) = ll.split_once(',')?;
                format!(
                    "https://www.openstreetmap.org/?mlat={lat}&mlon={lon}#map={zoom}/{lat}/{lon}"
                )
            }
        },
        App::Geo => {
            let mut url = format!("geo:{ll}?z={zoom}&q={ll}");
            if let Some(name) = name {
                url.push_str(&format!("({})", segment(name)));
            }
            url
        }
    })
}

fn build_directions(app: App, place: &Place, mode: TravelMode) -> Option<String> {
    let ll = pair(place.point);
    let name = name_of(place);
    Some(match app {
        App::GoogleMaps => {
            let mode = match mode {
                TravelMode::Driving => "driving",
                TravelMode::Walking => "walking",
                TravelMode::Cycling => "bicycling",
                TravelMode::Transit => "transit",
            };
            format!("https://www.google.com/maps/dir/?api=1&destination={ll}&travelmode={mode}")
        }
        App::GoogleNavigation => {
            let mode = match mode {
                TravelMode::Driving => "d",
                TravelMode::Walking => "w",
                TravelMode::Cycling => "b",
                TravelMode::Transit => return None,
            };
            format!("google.navigation:q={ll}&mode={mode}")
        }
        App::AppleMaps => {
            let mode = match mode {
                TravelMode::Driving => "driving",
                TravelMode::Walking => "walking",
                TravelMode::Cycling => "cycling",
                TravelMode::Transit => "transit",
            };
            format!("https://maps.apple.com/directions?destination={ll}&mode={mode}")
        }
        App::Waze => match mode {
            TravelMode::Driving => format!("https://waze.com/ul?ll={ll}&navigate=yes"),
            _ => return None,
        },
        App::HereWeGo => {
            let mode = match mode {
                TravelMode::Driving => "d",
                TravelMode::Walking => "w",
                TravelMode::Cycling => "b",
                TravelMode::Transit => "pt",
            };
            let mut url = format!("https://share.here.com/r/mylocation/{ll}");
            if let Some(name) = name {
                url.push(',');
                url.push_str(&segment(name));
            }
            url.push_str(&format!("?m={mode}"));
            url
        }
        App::OpenStreetMap => {
            let engine = match mode {
                TravelMode::Driving => "fossgis_osrm_car",
                TravelMode::Walking => "fossgis_osrm_foot",
                TravelMode::Cycling => "fossgis_osrm_bike",
                TravelMode::Transit => return None,
            };
            format!(
                "https://www.openstreetmap.org/directions?engine={engine}&route=%3B{}",
                q(&ll)
            )
        }
        // No documented "directions from here" link: Organic Maps and CoMaps need an explicit
        // start, OsmAnd's web links and geo: have none.
        App::OsmAnd
        | App::OrganicMaps
        | App::OrganicMapsWeb
        | App::CoMaps
        | App::CoMapsWeb
        | App::Geo => return None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn place(name: Option<&str>) -> Place {
        Place {
            point: Point::new(47.37, 8.54).unwrap(),
            name: name.map(str::to_string),
            osm: None,
            zoom: None,
        }
    }

    #[test]
    fn places() {
        let p = Target::Place(place(Some("Café & Bar")));
        for (app, url) in [
            (
                App::GoogleMaps,
                "https://www.google.com/maps/search/?api=1&query=47.37,8.54",
            ),
            (
                App::AppleMaps,
                "https://maps.apple.com/place?coordinate=47.37,8.54&name=Caf%C3%A9%20%26%20Bar",
            ),
            (App::Waze, "https://waze.com/ul?ll=47.37,8.54"),
            (
                App::OsmAnd,
                "https://osmand.net/map?pin=47.37,8.54#16/47.37/8.54",
            ),
            (
                App::OrganicMaps,
                "om://map?v=1&ll=47.37,8.54&n=Caf%C3%A9%20%26%20Bar&z=16",
            ),
            (
                App::CoMaps,
                "cm://map?v=1&ll=47.37,8.54&n=Caf%C3%A9%20%26%20Bar&z=16",
            ),
            (
                App::HereWeGo,
                "https://share.here.com/l/47.37,8.54,Caf%C3%A9%20%26%20Bar?z=16",
            ),
            (
                App::OpenStreetMap,
                "https://www.openstreetmap.org/?mlat=47.37&mlon=8.54#map=16/47.37/8.54",
            ),
            (
                App::Geo,
                "geo:47.37,8.54?z=16&q=47.37,8.54(Caf%C3%A9%20%26%20Bar)",
            ),
        ] {
            assert_eq!(build(app, &p).as_deref(), Some(url), "{app:?}");
        }
        assert!(
            build(App::OrganicMapsWeb, &p)
                .unwrap()
                .starts_with("https://omaps.app/")
        );
        assert!(
            build(App::CoMapsWeb, &p)
                .unwrap()
                .ends_with("/Caf%C3%A9_%26_Bar")
        );
        assert_eq!(build(App::GoogleNavigation, &p), None);
        assert_eq!(
            build(App::Geo, &Target::Place(place(None))).as_deref(),
            Some("geo:47.37,8.54?z=16&q=47.37,8.54")
        );
        let osm = Target::Place(Place {
            osm: Some(OsmRef {
                kind: crate::OsmKind::Way,
                id: 7,
            }),
            ..place(None)
        });
        assert_eq!(
            build(App::OpenStreetMap, &osm).as_deref(),
            Some("https://www.openstreetmap.org/way/7")
        );
    }

    #[test]
    fn directions() {
        let d = |mode| Target::Directions {
            destination: place(Some("Office")),
            mode,
        };
        assert_eq!(
            build(App::GoogleMaps, &d(TravelMode::Cycling)).as_deref(),
            Some(
                "https://www.google.com/maps/dir/?api=1&destination=47.37,8.54&travelmode=bicycling"
            )
        );
        assert_eq!(
            build(App::GoogleNavigation, &d(TravelMode::Driving)).as_deref(),
            Some("google.navigation:q=47.37,8.54&mode=d")
        );
        assert_eq!(build(App::GoogleNavigation, &d(TravelMode::Transit)), None);
        assert_eq!(
            build(App::AppleMaps, &d(TravelMode::Transit)).as_deref(),
            Some("https://maps.apple.com/directions?destination=47.37,8.54&mode=transit")
        );
        assert_eq!(
            build(App::Waze, &d(TravelMode::Driving)).as_deref(),
            Some("https://waze.com/ul?ll=47.37,8.54&navigate=yes")
        );
        assert_eq!(build(App::Waze, &d(TravelMode::Walking)), None);
        assert_eq!(
            build(App::HereWeGo, &d(TravelMode::Walking)).as_deref(),
            Some("https://share.here.com/r/mylocation/47.37,8.54,Office?m=w")
        );
        assert_eq!(
            build(App::OpenStreetMap, &d(TravelMode::Walking)).as_deref(),
            Some(
                "https://www.openstreetmap.org/directions?engine=fossgis_osrm_foot&route=%3B47.37%2C8.54"
            )
        );
        for app in [App::OsmAnd, App::OrganicMaps, App::CoMapsWeb, App::Geo] {
            assert_eq!(build(app, &d(TravelMode::Driving)), None, "{app:?}");
        }
    }
}
