//! OpenStreetMap and OsmAnd links.
//!
//! OSM: element URLs `/{node|way|relation}/<id>`, marker links `/?mlat=&mlon=`, map views
//! `#map=<zoom>/<lat>/<lon>`, short links `/go/<code>` (openstreetmap-website `ShortLink`:
//! Morton-interleaved x/y, 3 bits each per character, `@` as the old `~`, up to two trailing
//! `-` for partial zoom levels), `/search?query=` and `/directions?route=<from>;<to>`.
//! OsmAnd: `/map?pin=lat,lon#zoom/lat/lon` and the older `/go?lat=&lon=&z=`.

use url::Url;

use crate::coord::{lat_lon, number};
use crate::{MapLink, OsmKind, OsmRef, Point, Provider};

pub(crate) fn parse_osm(url: &Url, original: &str) -> Option<MapLink> {
    let mut link = MapLink::new(Provider::OpenStreetMap, original);
    let segments: Vec<&str> = url
        .path_segments()
        .map(|s| s.filter(|s| !s.is_empty()).collect())
        .unwrap_or_default();
    let query = |name: &str| {
        url.query_pairs()
            .find(|(k, _)| k == name)
            .map(|(_, v)| v.into_owned())
    };
    match segments.as_slice() {
        ["go", code] => {
            let (point, zoom) = shortlink(code)?;
            link.point = Some(point);
            link.set_zoom(Some(zoom));
            // A short link can carry the element it shows.
            link.osm = ["node", "way", "relation"]
                .into_iter()
                .find_map(|kind| osm_ref(kind, &query(kind)?));
        }
        [kind, id] => link.osm = Some(osm_ref(kind, id)?),
        ["search"] => link.set_query(&query("query").unwrap_or_default()),
        // `route=<from>;<to>`: the destination is the place.
        ["directions"] => {
            let route = query("route")?;
            link.point = lat_lon(route.rsplit(';').next()?);
        }
        [] => {
            if let (Some(lat), Some(lon)) = (query("mlat"), query("mlon")) {
                link.point = Point::new(number(&lat)?, number(&lon)?);
                link.set_zoom(query("zoom").as_deref().and_then(number));
            }
        }
        _ => return None,
    }
    if let Some((zoom, centre)) = url.fragment().and_then(map_fragment) {
        link.set_zoom(Some(zoom));
        if link.point.is_none() {
            link.point = Some(centre);
            link.point_is_view = true;
        }
    }
    Some(link)
}

pub(crate) fn parse_osmand(url: &Url, original: &str) -> Option<MapLink> {
    let mut link = MapLink::new(Provider::OsmAnd, original);
    let query = |name: &str| {
        url.query_pairs()
            .find(|(k, _)| k == name)
            .map(|(_, v)| v.into_owned())
    };
    match url.path().trim_end_matches('/') {
        "/map" => {
            link.point = query("pin")
                .or_else(|| query("finish"))
                .and_then(|p| lat_lon(&p));
            if let Some((zoom, centre)) = url.fragment().and_then(|f| zoom_lat_lon(f, '/')) {
                link.set_zoom(Some(zoom));
                if link.point.is_none() {
                    link.point = Some(centre);
                    link.point_is_view = true;
                }
            }
        }
        "/go" | "/go.html" => {
            link.point = Point::new(number(&query("lat")?)?, number(&query("lon")?)?);
            link.set_zoom(query("z").as_deref().and_then(number));
            if let Some(name) = query("name") {
                link.set_name(&name);
            }
        }
        _ => return None,
    }
    Some(link)
}

fn osm_ref(kind: &str, id: &str) -> Option<OsmRef> {
    let kind = OsmKind::parse(kind)?;
    if id.is_empty() || id.starts_with('0') || !id.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    Some(OsmRef {
        kind,
        id: id.parse().ok()?,
    })
}

/// `map=<zoom>/<lat>/<lon>` among `&`-separated fragment parameters.
fn map_fragment(fragment: &str) -> Option<(f64, Point)> {
    fragment
        .split('&')
        .find_map(|p| p.strip_prefix("map="))
        .and_then(|v| zoom_lat_lon(v, '/'))
}

fn zoom_lat_lon(s: &str, sep: char) -> Option<(f64, Point)> {
    let mut parts = s.split(sep);
    let zoom = number(parts.next()?)?;
    let point = Point::new(number(parts.next()?)?, number(parts.next()?)?)?;
    Some((zoom, point))
}

/// Decodes an openstreetmap.org short link code: its point and zoom.
pub(crate) fn shortlink(code: &str) -> Option<(Point, f64)> {
    const ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_~";
    let digits = code.trim_end_matches('-');
    let dashes = code.len() - digits.len();
    if digits.is_empty() || digits.len() > 10 || dashes > 2 {
        return None;
    }
    let (mut x, mut y, mut z) = (0u64, 0u64, 0u32);
    for c in digits.bytes() {
        let c = if c == b'@' { b'~' } else { c };
        let mut t = ALPHABET.iter().position(|a| *a == c)? as u64;
        for _ in 0..3 {
            x = (x << 1) | u64::from(t & 32 != 0);
            t <<= 1;
            y = (y << 1) | u64::from(t & 32 != 0);
            t <<= 1;
        }
        z += 3;
    }
    x <<= 32 - z;
    y <<= 32 - z;
    let scale = 4_294_967_296.0;
    let point = Point::new(
        y as f64 * 180.0 / scale - 90.0,
        x as f64 * 360.0 / scale - 180.0,
    )?;
    let zoom = f64::from(z) - 8.0 - [0.0, 2.0, 1.0][dashes];
    Some((point, zoom))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::parse;

    #[test]
    fn shortlinks_decode_like_openstreetmap_org() {
        // Where openstreetmap.org/go/<code> redirects for these codes.
        for (code, lat, lon, zoom) in [
            ("0EEQjE--", 51.510_772_705_078_125, 0.054_931_640_625, 9.0),
            (
                "0GAjIv8h",
                50.671_530_961_990_356,
                6.097_154_617_309_57,
                16.0,
            ),
            (
                "~v2juONc--",
                78.103_501_796_722_41,
                178.411_488_533_020_02,
                15.0,
            ),
            ("D@hV--", -47.153_320_312_5, -92.8125, 3.0),
        ] {
            let (point, z) = shortlink(code).unwrap();
            assert_eq!((point.lat, point.lon, z), (lat, lon, zoom), "{code}");
        }
        for (new, old) in [("~v2juONc--", "@v2juONc--"), ("D~hV--", "D@hV--")] {
            assert_eq!(shortlink(new), shortlink(old));
        }
        for bad in ["", "---", "abc---", "a.b", "AAAAAAAAAAA", "ab-c"] {
            assert!(shortlink(bad).is_none(), "{bad}");
        }
    }

    #[test]
    fn osm_links() {
        let link = parse("https://www.openstreetmap.org/node/240109189").unwrap();
        assert_eq!(
            link.osm,
            Some(OsmRef {
                kind: OsmKind::Node,
                id: 240_109_189
            })
        );
        assert_eq!(link.point, None);
        let link = parse("http://osm.org/way/50637691/#map=17/52.51/13.39").unwrap();
        assert_eq!(link.osm.unwrap().kind, OsmKind::Way);
        assert_eq!(link.point, Point::new(52.51, 13.39));
        assert_eq!(link.zoom, Some(17.0));

        let link = parse("https://www.openstreetmap.org/?mlat=52.5186925&mlon=13.3996024").unwrap();
        assert_eq!(link.point, Point::new(52.518_692_5, 13.399_602_4));
        let link = parse("https://www.openstreetmap.org/#map=12/52.5/13.4").unwrap();
        assert_eq!(link.point, Point::new(52.5, 13.4));
        assert_eq!(link.zoom, Some(12.0));
        let link = parse("https://osm.org/go/0GAjIv8h?m").unwrap();
        assert_eq!(link.zoom, Some(16.0));
        let link = parse("https://osm.org/go/0GAjIv8h?node=240109189").unwrap();
        assert_eq!(link.osm.unwrap().id, 240_109_189);
        let link = parse(
            "https://www.openstreetmap.org/directions?engine=fossgis_osrm_car&route=47.1%2C8.1%3B47.37%2C8.54",
        )
        .unwrap();
        assert_eq!(link.point, Point::new(47.37, 8.54));
        let link = parse("https://www.openstreetmap.org/search?query=Z%C3%BCrich").unwrap();
        assert_eq!(link.query.as_deref(), Some("Zürich"));

        for bad in [
            "https://www.openstreetmap.org/node/0123",
            "https://www.openstreetmap.org/node/240109189/history",
            "https://www.openstreetmap.org/changeset/1",
            "https://www.openstreetmap.org/?mlat=91&mlon=0",
            "https://www.openstreetmap.org/?mlat=1",
            "https://www.openstreetmap.org/",
            "https://example.org/go/0EEQjE--",
        ] {
            assert_eq!(parse(bad), None, "{bad}");
        }
    }

    #[test]
    fn osmand_links() {
        let link = parse("https://osmand.net/map?pin=52.5186925,13.3996024").unwrap();
        assert_eq!(link.provider, Provider::OsmAnd);
        assert_eq!(link.point, Point::new(52.518_692_5, 13.399_602_4));
        let link = parse("https://osmand.net/map/?pin=47.37,8.54#15/47.37/8.54").unwrap();
        assert_eq!(link.zoom, Some(15.0));
        let link = parse("https://osmand.net/go?lat=52.5186925&lon=13.3996024&z=17").unwrap();
        assert_eq!(link.point, Point::new(52.518_692_5, 13.399_602_4));
        assert_eq!(link.zoom, Some(17.0));
        let link = parse("http://www.osmand.net/go.html?lat=34.99393&lon=-110.12345").unwrap();
        assert_eq!(link.point, Point::new(34.99393, -110.12345));
        let link =
            parse("https://osmand.net/map/?start=47.1,8.1&finish=47.37,8.54&profile=car").unwrap();
        assert_eq!(link.point, Point::new(47.37, 8.54));
        for bad in [
            "https://osmand.net/",
            "https://osmand.net/map?pin=52.5",
            "http://download.osmand.net/go?lat=34.99393",
        ] {
            assert_eq!(parse(bad), None, "{bad}");
        }
    }
}
