//! Place URIs: the spelling every tag, review, photo, list item and bookmark points at.
//!
//! - A place picked on the map is its canonical OSM object URL,
//!   `https://www.openstreetmap.org/{node|way|relation}/{id}`.
//! - A pin is `geo:<lat>,<lon>` with at most 5 fraction digits (about 1 m).
//! - Any other pasted link (Google Maps, Apple Maps, a website) is kept as the user had it.
//!
//! Everything passes the social specs' universal reference gate last, so the result is
//! always a valid `uri`/`parent`/`embed`/bookmark target.

use pubky_social_specs::{canonicalize_universal, frozen_trim};

use crate::error::CoreError;

/// The most fraction digits a `geo:` place keeps.
pub const GEO_MAX_FRACTION_DIGITS: u8 = 5;

const OSM_CANONICAL_PREFIX: &str = "https://www.openstreetmap.org/";
const OSM_HOSTS: [&str; 4] = [
    "openstreetmap.org",
    "www.openstreetmap.org",
    "osm.org",
    "www.osm.org",
];

/// An OSM element type.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum OsmElement {
    Node,
    Way,
    Relation,
}

impl OsmElement {
    fn as_str(self) -> &'static str {
        match self {
            Self::Node => "node",
            Self::Way => "way",
            Self::Relation => "relation",
        }
    }

    fn parse(s: &str) -> Option<Self> {
        match s {
            "node" => Some(Self::Node),
            "way" => Some(Self::Way),
            "relation" => Some(Self::Relation),
            _ => None,
        }
    }
}

/// The canonical OSM URL of an element.
#[uniffi::export]
pub fn osm_place_uri(element: OsmElement, id: u64) -> Result<String, CoreError> {
    if id == 0 {
        return Err(CoreError::validation("OSM ids start at 1"));
    }
    Ok(format!("{OSM_CANONICAL_PREFIX}{}/{id}", element.as_str()))
}

/// A `geo:` place for a point, rounded to `fraction_digits` (0 to 5). The app passes fewer
/// digits for coarse public pins.
#[uniffi::export]
pub fn geo_place_uri(lat: f64, lon: f64, fraction_digits: u8) -> Result<String, CoreError> {
    if fraction_digits > GEO_MAX_FRACTION_DIGITS {
        return Err(CoreError::validation(format!(
            "a geo place keeps at most {GEO_MAX_FRACTION_DIGITS} fraction digits"
        )));
    }
    check_range(lat, lon)?;
    Ok(format!(
        "geo:{},{}",
        format_coordinate(lat, fraction_digits),
        format_coordinate(lon, fraction_digits)
    ))
}

/// Normalises whatever the user picked or pasted into the place URI that gets written.
#[uniffi::export]
pub fn normalize_place_uri(input: String) -> Result<String, CoreError> {
    let trimmed = frozen_trim(&input);
    if trimmed.is_empty() {
        return Err(CoreError::validation("place URI is empty"));
    }
    let normalized = if let Some(osm) = parse_osm_url(trimmed) {
        osm
    } else if has_scheme(trimmed, "geo:") {
        // `None`: a search link, not a point; it is kept as pasted.
        normalize_geo(trimmed)?.unwrap_or_else(|| trimmed.to_string())
    } else {
        trimmed.to_string()
    };
    canonicalize_universal(&normalized)
        .map_err(|()| CoreError::validation(format!("not a usable place URI: {normalized}")))
}

fn has_scheme(s: &str, scheme: &str) -> bool {
    s.get(..scheme.len())
        .is_some_and(|head| head.eq_ignore_ascii_case(scheme))
}

/// `http(s)://[www.]openstreetmap.org/{type}/{id}` (or osm.org), with an optional trailing
/// slash, query and fragment, as the canonical URL. Other OSM pages (history, map views,
/// short links) are not element URLs and return `None`.
fn parse_osm_url(s: &str) -> Option<String> {
    let rest = if has_scheme(s, "https://") {
        &s["https://".len()..]
    } else if has_scheme(s, "http://") {
        &s["http://".len()..]
    } else {
        return None;
    };
    let end = rest.find(['?', '#']).unwrap_or(rest.len());
    let rest = &rest[..end];
    let (host, path) = rest.split_once('/')?;
    if !OSM_HOSTS.iter().any(|h| h.eq_ignore_ascii_case(host)) {
        return None;
    }
    let path = path.strip_suffix('/').unwrap_or(path);
    let (kind, id) = path.split_once('/')?;
    let element = OsmElement::parse(&kind.to_ascii_lowercase())?;
    if id.is_empty() || !id.bytes().all(|b| b.is_ascii_digit()) || id.starts_with('0') {
        return None;
    }
    let id: u64 = id.parse().ok()?;
    osm_place_uri(element, id).ok()
}

/// RFC 5870 `geo:lat,lon[,alt][;params][?query]` down to `geo:lat,lon`. The Android form
/// `geo:0,0?q=lat,lon(label)` takes its point from the query. `geo:0,0?q=<address or name>`
/// is a search, not a point: `None`, so the caller keeps the link as pasted instead of
/// writing it to 0,0.
fn normalize_geo(s: &str) -> Result<Option<String>, CoreError> {
    let body = &s["geo:".len()..];
    let (body, query) = match body.split_once('?') {
        Some((b, q)) => (b, Some(q)),
        None => (body, None),
    };
    let mut parts = body.split(';');
    let coords = parts.next().unwrap_or_default();
    for param in parts {
        if let Some(crs) = param.strip_prefix("crs=")
            && !crs.eq_ignore_ascii_case("wgs84")
        {
            return Err(CoreError::validation(format!(
                "geo URI uses an unsupported crs: {crs}"
            )));
        }
    }
    let (mut lat, mut lon) = parse_point(coords)
        .ok_or_else(|| CoreError::validation(format!("not a geo URI point: {s}")))?;
    if lat == 0.0
        && lon == 0.0
        && let Some(query) = query
    {
        match query_point(query) {
            Some(point) => (lat, lon) = point,
            None => return Ok(None),
        }
    }
    geo_place_uri(lat, lon, GEO_MAX_FRACTION_DIGITS).map(Some)
}

/// `lat,lon` or `lat,lon,alt`.
fn parse_point(s: &str) -> Option<(f64, f64)> {
    let mut it = s.split(',');
    let lat = parse_number(it.next()?)?;
    let lon = parse_number(it.next()?)?;
    if let Some(alt) = it.next() {
        parse_number(alt)?;
    }
    if it.next().is_some() {
        return None;
    }
    Some((lat, lon))
}

/// Plain decimal numbers only: no exponent, no `inf`/`nan`, no `+` sign.
fn parse_number(s: &str) -> Option<f64> {
    let digits = s.strip_prefix('-').unwrap_or(s);
    let valid = !digits.is_empty()
        && digits.bytes().all(|b| b.is_ascii_digit() || b == b'.')
        && digits.bytes().filter(|b| *b == b'.').count() <= 1
        && !digits.starts_with('.')
        && !digits.ends_with('.');
    if !valid {
        return None;
    }
    s.parse().ok()
}

/// The point in a `q=lat,lon(label)` query.
fn query_point(query: &str) -> Option<(f64, f64)> {
    let q = query.split('&').find_map(|pair| pair.strip_prefix("q="))?;
    let q = q.split('(').next().unwrap_or(q);
    let q = q.replace("%2C", ",").replace("%2c", ",");
    parse_point(q.trim())
}

fn check_range(lat: f64, lon: f64) -> Result<(), CoreError> {
    if !lat.is_finite() || !(-90.0..=90.0).contains(&lat) {
        return Err(CoreError::validation(format!(
            "latitude out of range: {lat}"
        )));
    }
    if !lon.is_finite() || !(-180.0..=180.0).contains(&lon) {
        return Err(CoreError::validation(format!(
            "longitude out of range: {lon}"
        )));
    }
    Ok(())
}

/// Rounded to `digits` fraction digits, trailing zeros dropped, never `-0`.
fn format_coordinate(value: f64, digits: u8) -> String {
    let mut s = format!("{value:.prec$}", prec = usize::from(digits));
    if s.contains('.') {
        while s.ends_with('0') {
            s.pop();
        }
        if s.ends_with('.') {
            s.pop();
        }
    }
    if s == "-0" {
        s = "0".into();
    }
    s
}

#[cfg(test)]
mod tests {
    use super::*;

    fn norm(s: &str) -> String {
        normalize_place_uri(s.to_string()).unwrap()
    }

    #[test]
    fn osm_urls_become_canonical() {
        let canonical = "https://www.openstreetmap.org/node/270803095";
        for input in [
            canonical,
            "https://openstreetmap.org/node/270803095",
            "http://www.openstreetmap.org/node/270803095",
            "https://osm.org/node/270803095",
            "HTTPS://WWW.OpenStreetMap.org/node/270803095",
            "https://www.openstreetmap.org/node/270803095/",
            "https://www.openstreetmap.org/node/270803095#map=19/47.3/8.5",
            "https://www.openstreetmap.org/node/270803095?layers=N",
            "  https://www.openstreetmap.org/node/270803095  ",
        ] {
            assert_eq!(norm(input), canonical, "{input}");
        }
        assert_eq!(
            norm("https://www.openstreetmap.org/way/4"),
            "https://www.openstreetmap.org/way/4"
        );
        assert_eq!(
            norm("https://osm.org/relation/51701"),
            "https://www.openstreetmap.org/relation/51701"
        );
    }

    #[test]
    fn other_osm_pages_are_kept_as_is() {
        for input in [
            "https://www.openstreetmap.org/node/270803095/history",
            "https://www.openstreetmap.org/#map=19/47.37/8.54",
            "https://www.openstreetmap.org/node/0123",
            "https://www.openstreetmap.org/changeset/1",
            "https://osm.org/go/0EEQjE--",
        ] {
            assert_eq!(norm(input), input, "{input}");
        }
    }

    #[test]
    fn osm_builder() {
        assert_eq!(
            osm_place_uri(OsmElement::Node, 1).unwrap(),
            "https://www.openstreetmap.org/node/1"
        );
        assert_eq!(
            osm_place_uri(OsmElement::Relation, 51701).unwrap(),
            "https://www.openstreetmap.org/relation/51701"
        );
        assert!(osm_place_uri(OsmElement::Way, 0).is_err());
    }

    #[test]
    fn geo_uris_keep_at_most_five_fraction_digits() {
        assert_eq!(norm("geo:47.37687,8.54169"), "geo:47.37687,8.54169");
        assert_eq!(norm("geo:47.376871234,8.541694999"), "geo:47.37687,8.54169");
        assert_eq!(norm("geo:47.376876,8.5"), "geo:47.37688,8.5");
        assert_eq!(norm("GEO:47.3,8.50000"), "geo:47.3,8.5");
        assert_eq!(norm("geo:47.37687,8.54169,430"), "geo:47.37687,8.54169");
        assert_eq!(norm("geo:47.37687,8.54169;u=35"), "geo:47.37687,8.54169");
        assert_eq!(
            norm("geo:47.37687,8.54169;crs=wgs84"),
            "geo:47.37687,8.54169"
        );
        assert_eq!(norm("geo:-0.000001,-0.000001"), "geo:0,0");
        assert_eq!(norm("geo:-33.86785,151.20732"), "geo:-33.86785,151.20732");
        assert_eq!(norm("geo:90,180"), "geo:90,180");
    }

    #[test]
    fn android_geo_query_form() {
        assert_eq!(
            norm("geo:0,0?q=47.37687,8.54169(Hauptbahnhof)"),
            "geo:47.37687,8.54169"
        );
        assert_eq!(norm("geo:0,0?q=47.37687%2C8.54169"), "geo:47.37687,8.54169");
        assert_eq!(norm("geo:47.1,8.1?q=cafe"), "geo:47.1,8.1");
    }

    #[test]
    fn android_geo_search_links_are_kept_as_pasted() {
        // A search by name or address is not a point: never written to 0,0.
        for link in [
            "geo:0,0?q=Hauptbahnhof+Z%C3%BCrich",
            "geo:0,0?q=1600%20Amphitheatre%20Parkway",
            "geo:0,0?q=(Label)",
            "geo:0,0?q=",
        ] {
            assert_eq!(norm(link), link, "{link}");
        }
        // Whitespace makes it no URI at all, so it is refused rather than moved to 0,0.
        assert!(matches!(
            normalize_place_uri("geo:0,0?q=Hauptbahnhof Zürich".into()),
            Err(CoreError::Validation { .. })
        ));
        // A plain point at 0,0 is still that point.
        assert_eq!(norm("geo:0,0"), "geo:0,0");
    }

    #[test]
    fn bad_geo_uris_are_rejected() {
        for input in [
            "geo:",
            "geo:47.3",
            "geo:91,8",
            "geo:47,181",
            "geo:abc,def",
            "geo:1e2,3",
            "geo:inf,1",
            "geo:47.,8",
            "geo:47.3,8.5,1,2",
            "geo:47.3,8.5;crs=other",
        ] {
            assert!(
                matches!(
                    normalize_place_uri(input.to_string()),
                    Err(CoreError::Validation { .. })
                ),
                "{input}"
            );
        }
    }

    #[test]
    fn geo_builder_rounds_and_checks() {
        assert_eq!(
            geo_place_uri(47.376_871, 8.541_694, 5).unwrap(),
            "geo:47.37687,8.54169"
        );
        assert_eq!(
            geo_place_uri(47.376_871, 8.541_694, 4).unwrap(),
            "geo:47.3769,8.5417"
        );
        assert_eq!(geo_place_uri(47.0, 8.0, 0).unwrap(), "geo:47,8");
        assert!(geo_place_uri(47.0, 8.0, 6).is_err());
        assert!(geo_place_uri(f64::NAN, 8.0, 5).is_err());
        assert!(geo_place_uri(-90.1, 8.0, 5).is_err());
    }

    #[test]
    fn other_links_are_kept() {
        let google = "https://maps.app.goo.gl/AbCdEf123";
        assert_eq!(norm(google), google);
        let apple = "https://maps.apple.com/?ll=47.37,8.54&q=Cafe";
        assert_eq!(norm(apple), apple);
        assert_eq!(norm(" https://example.com/x "), "https://example.com/x");
    }

    #[test]
    fn junk_is_rejected() {
        for input in [
            "",
            "   ",
            "no scheme here",
            "https://exa mple.com",
            "https://",
        ] {
            assert!(
                matches!(
                    normalize_place_uri(input.to_string()),
                    Err(CoreError::Validation { .. })
                ),
                "{input:?}"
            );
        }
    }
}
