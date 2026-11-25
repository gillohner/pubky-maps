//! Reads and builds map links.
//!
//! [`parse`] reads a place out of a link someone shared from a map app: Google Maps, Apple Maps,
//! Waze, OpenStreetMap, OsmAnd, Organic Maps, CoMaps, HERE WeGo, or a `geo:` URI. Everything comes
//! from the link text alone; nothing is fetched. Short links (`maps.app.goo.gl`, `goo.gl/maps`,
//! `maps.apple/p/`) hold no place in their text: they come back with
//! [`MapLink::needs_redirect`] set, and [`resolve_redirect`] follows them by reading only the
//! `Location` header of each hop.
//!
//! [`build`] makes the link that opens a place, or directions to it, in one of those apps.
//!
//! [`canonical_place_uri`] gives the URI a place is known by in Pubky data: the OpenStreetMap
//! object URL when the link names one, else `geo:<lat>,<lon>` with at most 5 fraction digits.
//!
//! The crate has no network or platform dependencies, so an app and a server can share it.

mod build;
mod coord;
mod geo;
mod google;
mod omaps;
mod osm;
mod other;
mod redirect;
mod text;

pub use build::{App, Place, Target, TravelMode, build};
pub use coord::{format_coordinate, geo_uri};
pub use redirect::{
    HeaderFetcher, Hop, MAX_REDIRECTS, Method, ResolveError, is_short_link, resolve_redirect,
};
pub use text::find_in_text;

/// The longest link [`parse`] reads, in bytes. Real map links are far shorter.
pub const MAX_LINK_LEN: usize = 8 * 1024;
/// The longest name or query kept, in characters.
pub const MAX_NAME_CHARS: usize = 200;
/// The zoom levels a link may carry; anything else is dropped.
pub const ZOOM_RANGE: std::ops::RangeInclusive<f64> = 0.0..=22.0;

/// A WGS84 point.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Point {
    pub lat: f64,
    pub lon: f64,
}

impl Point {
    /// The point, if both values are finite and in range.
    pub fn new(lat: f64, lon: f64) -> Option<Self> {
        (lat.is_finite()
            && lon.is_finite()
            && (-90.0..=90.0).contains(&lat)
            && (-180.0..=180.0).contains(&lon))
        .then_some(Self { lat, lon })
    }
}

/// An OpenStreetMap element type.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum OsmKind {
    Node,
    Way,
    Relation,
}

impl OsmKind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Node => "node",
            Self::Way => "way",
            Self::Relation => "relation",
        }
    }

    pub fn parse(s: &str) -> Option<Self> {
        match s {
            "node" => Some(Self::Node),
            "way" => Some(Self::Way),
            "relation" => Some(Self::Relation),
            _ => None,
        }
    }
}

/// An OpenStreetMap element.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct OsmRef {
    pub kind: OsmKind,
    /// Never 0.
    pub id: u64,
}

impl OsmRef {
    /// The canonical URL, `https://www.openstreetmap.org/{node|way|relation}/{id}`.
    pub fn url(&self) -> String {
        format!(
            "https://www.openstreetmap.org/{}/{}",
            self.kind.as_str(),
            self.id
        )
    }
}

/// The app or format a link belongs to.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Provider {
    /// A `geo:` URI (RFC 5870, with Android's `?q=` and `?z=`).
    Geo,
    Google,
    Apple,
    Waze,
    OpenStreetMap,
    OsmAnd,
    /// Organic Maps, including the older `ge0.me` and `ge0://` links.
    OrganicMaps,
    CoMaps,
    Here,
}

/// What a map link says about a place.
#[derive(Debug, Clone, PartialEq)]
pub struct MapLink {
    pub provider: Provider,
    /// The place's name, when the link carries one.
    pub name: Option<String>,
    /// The place's point. For a link that shows a map view (`@lat,lon,zoom`, `#map=`), the
    /// centre of that view.
    pub point: Option<Point>,
    /// The OpenStreetMap element the link names.
    pub osm: Option<OsmRef>,
    /// The zoom level the link asks for.
    pub zoom: Option<f64>,
    /// Search text, for a link that asks to search (an address or a name) rather than naming
    /// a point.
    pub query: Option<String>,
    /// The provider's own id of the place: Google's CID (decimal), Apple's `auid` or
    /// `place-id`, or a HERE place id.
    pub provider_id: Option<String>,
    /// A short link whose text holds no place: follow it with [`resolve_redirect`] and parse
    /// the result.
    pub needs_redirect: bool,
    /// The link as given (trimmed).
    pub original: String,
}

impl MapLink {
    pub(crate) fn new(provider: Provider, original: &str) -> Self {
        Self {
            provider,
            name: None,
            point: None,
            osm: None,
            zoom: None,
            query: None,
            provider_id: None,
            needs_redirect: false,
            original: original.to_string(),
        }
    }

    /// True when the link tells anything about a place.
    fn says_something(&self) -> bool {
        self.point.is_some()
            || self.osm.is_some()
            || self.query.is_some()
            || self.name.is_some()
            || self.provider_id.is_some()
            || self.needs_redirect
    }

    pub(crate) fn set_zoom(&mut self, zoom: Option<f64>) {
        if let Some(zoom) = zoom.filter(|z| ZOOM_RANGE.contains(z)) {
            self.zoom = Some(zoom);
        }
    }

    pub(crate) fn set_name(&mut self, name: &str) {
        if self.name.is_none() {
            self.name = text::clean(name);
        }
    }

    pub(crate) fn set_query(&mut self, query: &str) {
        if self.query.is_none() {
            self.query = text::clean(query);
        }
    }
}

/// Reads a map link. `None` when the input is not a map link this crate knows, or a map link
/// that says nothing about a place (an app's home page). Never panics and never fetches.
pub fn parse(input: &str) -> Option<MapLink> {
    let trimmed = input.trim();
    if trimmed.is_empty() || trimmed.len() > MAX_LINK_LEN {
        return None;
    }
    let (scheme, _) = trimmed.split_once(':')?;
    let scheme = scheme.to_ascii_lowercase();
    let link = match scheme.as_str() {
        "geo" => geo::parse(trimmed),
        "google.navigation" => google::parse_navigation(trimmed),
        "om" | "ge0" | "cm" | "comaps" => omaps::parse_scheme(trimmed, &scheme),
        "waze" => other::parse_waze_scheme(trimmed),
        "http" | "https" => parse_web(trimmed),
        _ => None,
    }?;
    link.says_something().then_some(link)
}

fn parse_web(original: &str) -> Option<MapLink> {
    let url = url::Url::parse(original).ok()?;
    if !url.username().is_empty() || url.password().is_some() {
        return None;
    }
    let host = url.host_str()?.trim_end_matches('.').to_ascii_lowercase();
    match host.as_str() {
        "maps.app.goo.gl" | "goo.gl" => google::parse_short(&url, &host, original),
        "maps.apple" | "maps.apple.com" => other::parse_apple(&url, &host, original),
        "waze.com" | "www.waze.com" | "ul.waze.com" => other::parse_waze(&url, original),
        "openstreetmap.org" | "www.openstreetmap.org" | "osm.org" | "www.osm.org" => {
            osm::parse_osm(&url, original)
        }
        "osmand.net" | "www.osmand.net" | "download.osmand.net" => {
            osm::parse_osmand(&url, original)
        }
        "omaps.app" | "ge0.me" | "comaps.at" => omaps::parse_web(&url, &host, original),
        "share.here.com" | "here.com" | "www.here.com" | "wego.here.com" => {
            other::parse_here(&url, &host, original)
        }
        _ if google::is_google_host(&host) => google::parse_web(&url, &host, original),
        _ => None,
    }
}

/// The URI a place is known by in Pubky data: the canonical OpenStreetMap URL when the link
/// names an element, else `geo:<lat>,<lon>` with at most 5 fraction digits. `None` for a link
/// without either (a search, or a short link not yet followed).
pub fn canonical_place_uri(link: &MapLink) -> Option<String> {
    if let Some(osm) = link.osm {
        return Some(osm.url());
    }
    link.point.map(|point| geo_uri(point, 5))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn not_map_links() {
        for input in [
            "",
            "   ",
            "hello",
            "https://example.com/maps/place/x",
            "mailto:someone@example.com",
            "https://www.google.com/search?q=pizza",
            "https://www.google.com/maps",
            "https://maps.apple.com/",
            "https://www.openstreetmap.org/user/someone",
            "https://user:pw@www.openstreetmap.org/node/1",
            "geo:",
            "ftp://osm.org/node/1",
        ] {
            assert_eq!(parse(input), None, "{input:?}");
        }
        assert_eq!(
            parse(&format!("geo:1,2?q={}", "a".repeat(MAX_LINK_LEN))),
            None
        );
    }

    #[test]
    fn canonical_uris() {
        let osm = parse("https://osm.org/way/4#map=17/47.1/8.1").unwrap();
        assert_eq!(
            canonical_place_uri(&osm).as_deref(),
            Some("https://www.openstreetmap.org/way/4")
        );
        let geo = parse("geo:47.3768712,8.5416949").unwrap();
        assert_eq!(
            canonical_place_uri(&geo).as_deref(),
            Some("geo:47.37687,8.54169")
        );
        let search = parse("geo:0,0?q=Bahnhofstrasse+1").unwrap();
        assert_eq!(canonical_place_uri(&search), None);
    }
}
