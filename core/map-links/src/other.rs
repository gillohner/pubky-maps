//! Apple Maps, Waze and HERE WeGo links.

use url::Url;

use crate::coord::{lat_lon, number};
use crate::geo::labelled_point;
use crate::text::decode_segment;
use crate::{MapLink, Point, Provider};

/// Apple Maps: unified URLs (`/place`, `/directions`, `/search`, `/frame`, `/look-around`),
/// the legacy `/?ll=&q=&daddr=&address=&auid=` form, and `maps.apple/p/<id>` short links.
pub(crate) fn parse_apple(url: &Url, host: &str, original: &str) -> Option<MapLink> {
    let mut link = MapLink::new(Provider::Apple, original);
    let path = url.path().trim_end_matches('/');
    if let Some(id) = path.strip_prefix("/p/") {
        if id.is_empty() || id.contains('/') {
            return None;
        }
        link.needs_redirect = true;
        return Some(link);
    }
    if host == "maps.apple" {
        return None;
    }
    let mut address = None;
    let mut view = None;
    let mut text_point = None;
    for (key, value) in url.query_pairs() {
        let value = value.trim();
        match key.as_ref() {
            "coordinate" | "ll" | "destination" | "daddr" | "q" | "query" => {
                match labelled_point(value) {
                    Some((point, _)) => text_point = text_point.or(Some(point)),
                    None => match key.as_ref() {
                        // Legacy `q` next to `ll` is the pin's label; on its own a search.
                        "q" if path.is_empty() => {
                            if url.query_pairs().any(|(k, _)| k == "ll") {
                                link.set_name(value);
                            } else {
                                link.set_query(value);
                            }
                        }
                        "destination" | "daddr" | "query" | "q" => link.set_query(value),
                        _ => {}
                    },
                }
            }
            "center" | "sll" => view = view.or_else(|| lat_lon(value)),
            "name" => link.set_name(value),
            "address" => address = Some(value.to_string()),
            "place-id" | "auid" if !value.is_empty() && value.len() <= 128 => {
                link.provider_id = Some(value.to_string());
            }
            "z" => link.set_zoom(number(value)),
            _ => {}
        }
    }
    match path {
        "" | "/place" | "/directions" | "/search" | "/frame" | "/look-around" => {}
        _ => return None,
    }
    // The address is the best title a place link without a name has.
    if let Some(address) = address {
        if link.name.is_none() && (text_point.is_some() || link.provider_id.is_some()) {
            link.set_name(&address);
        } else {
            link.set_query(&address);
        }
    }
    link.point = text_point.or(view);
    Some(link)
}

/// Waze: `waze.com/ul?ll=&z=&navigate=`, `ul?q=`, `ul/h<geohash>` share links,
/// `live-map?h=<geohash>` and `live-map/directions?to=ll.<lat>,<lon>`.
pub(crate) fn parse_waze(url: &Url, original: &str) -> Option<MapLink> {
    let mut link = MapLink::new(Provider::Waze, original);
    let segments: Vec<&str> = url
        .path_segments()
        .map(|s| s.filter(|s| !s.is_empty()).collect())
        .unwrap_or_default();
    // An optional language prefix: /de/live-map/…
    let segments = match segments.first() {
        Some(s) if *s != "ul" && (s.len() == 2 || (s.len() == 5 && s.as_bytes()[2] == b'-')) => {
            &segments[1..]
        }
        _ => &segments[..],
    };
    match segments {
        ["ul"] | ["live-map", ..] | [] => {}
        ["ul", hash] => link.point = geohash(hash.strip_prefix('h')?),
        _ => return None,
    }
    waze_params(&mut link, url.query_pairs());
    Some(link)
}

/// `waze://?ll=…`
pub(crate) fn parse_waze_scheme(original: &str) -> Option<MapLink> {
    let query = original.split_once('?').map_or("", |(_, q)| q);
    let mut link = MapLink::new(Provider::Waze, original);
    waze_params(&mut link, url::form_urlencoded::parse(query.as_bytes()));
    Some(link)
}

fn waze_params<'a>(
    link: &mut MapLink,
    pairs: impl Iterator<Item = (std::borrow::Cow<'a, str>, std::borrow::Cow<'a, str>)>,
) {
    for (key, value) in pairs {
        match key.as_ref() {
            "ll" | "latlng" => link.point = link.point.or_else(|| lat_lon(&value)),
            "to" => {
                if let Some(ll) = value.strip_prefix("ll.") {
                    link.point = link.point.or_else(|| lat_lon(ll));
                }
            }
            "h" => link.point = link.point.or_else(|| geohash(&value)),
            "q" => link.set_query(&value),
            "z" | "zoom" => link.set_zoom(number(&value)),
            _ => {}
        }
    }
}

/// The centre of a geohash cell (Waze share links use them).
pub(crate) fn geohash(s: &str) -> Option<Point> {
    const ALPHABET: &[u8] = b"0123456789bcdefghjkmnpqrstuvwxyz";
    if s.is_empty() || s.len() > 12 {
        return None;
    }
    let (mut lat, mut lon) = ((-90.0f64, 90.0f64), (-180.0f64, 180.0f64));
    let mut even = true;
    for c in s.bytes() {
        let value = ALPHABET.iter().position(|a| *a == c.to_ascii_lowercase())?;
        for bit in (0..5).rev() {
            let on = value >> bit & 1 == 1;
            let range = if even { &mut lon } else { &mut lat };
            let mid = (range.0 + range.1) / 2.0;
            if on {
                range.0 = mid;
            } else {
                range.1 = mid;
            }
            even = !even;
        }
    }
    Point::new((lat.0 + lat.1) / 2.0, (lon.0 + lon.1) / 2.0)
}

/// HERE WeGo: `share.here.com/l/<lat>,<lon>,<title>?z=`, `share.here.com/r/<from>/<to>`,
/// `share.here.com/p/<id>` (a place id only) and `here.com/?map=<lat>,<lon>,<zoom>&msg=`.
pub(crate) fn parse_here(url: &Url, host: &str, original: &str) -> Option<MapLink> {
    let mut link = MapLink::new(Provider::Here, original);
    if host == "share.here.com" {
        let segments: Vec<&str> = url.path_segments()?.filter(|s| !s.is_empty()).collect();
        match segments.as_slice() {
            ["l", location] => here_location(&mut link, location)?,
            ["r", .., to] => here_location(&mut link, to)?,
            ["p", id] => link.provider_id = Some(decode_segment(id)).filter(|id| id.len() <= 128),
            _ => return None,
        }
        if let Some((_, z)) = url.query_pairs().find(|(k, _)| k == "z") {
            link.set_zoom(number(&z));
        }
        return Some(link);
    }
    let map = url.query_pairs().find(|(k, _)| k == "map")?.1;
    let mut parts = map.split(',');
    link.point = Point::new(number(parts.next()?)?, number(parts.next()?)?);
    link.set_zoom(parts.next().and_then(number));
    if let Some((_, msg)) = url.query_pairs().find(|(k, _)| k == "msg") {
        link.set_name(&msg);
    }
    Some(link)
}

/// `<lat>,<lon>[,<title>]`; `mylocation` is no place.
fn here_location(link: &mut MapLink, s: &str) -> Option<()> {
    let s = decode_segment(s);
    let mut parts = s.splitn(3, ',');
    link.point = Point::new(number(parts.next()?)?, number(parts.next()?)?);
    if let Some(title) = parts.next() {
        link.set_name(title);
    }
    Some(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::parse;

    #[test]
    fn apple() {
        let link = parse(
            "https://maps.apple.com/place?address=1%20Main%20St&coordinate=35.307955,-97.537610",
        )
        .unwrap();
        assert_eq!(link.point, Point::new(35.307_955, -97.537_61));
        assert_eq!(link.name.as_deref(), Some("1 Main St"));

        let link =
            parse("https://maps.apple.com/place?coordinate=47.37,8.54&name=Caf%C3%A9").unwrap();
        assert_eq!(link.name.as_deref(), Some("Café"));
        let link = parse("https://maps.apple.com/?ll=47.37,8.54&q=Label").unwrap();
        assert_eq!(link.point, Point::new(47.37, 8.54));
        assert_eq!(link.name.as_deref(), Some("Label"));
        let link = parse("http://maps.apple.com/?q=Bargou,+Tunisien").unwrap();
        assert_eq!(link.query.as_deref(), Some("Bargou, Tunisien"));
        assert_eq!(link.point, None);
        let link = parse("http://maps.apple.com/?daddr=Bargou,+Tunisien").unwrap();
        assert_eq!(link.query.as_deref(), Some("Bargou, Tunisien"));
        let link = parse(
            "http://maps.apple.com/?lsp=7618&q=40.738065,-73.988898&sll=40.738065,-73.988898",
        )
        .unwrap();
        assert_eq!(link.point, Point::new(40.738_065, -73.988_898));
        let link = parse("http://maps.apple.com/?lsp=9902&auid=13787349062281695774&sll=40.694576,-73.982992&q=Garden%20Nail%20%26%20Spa&hnear=325%20Gold%20St").unwrap();
        assert_eq!(link.provider_id.as_deref(), Some("13787349062281695774"));
        assert_eq!(link.point, Point::new(40.694_576, -73.982_992));
        assert_eq!(link.query.as_deref(), Some("Garden Nail & Spa"));
        let link = parse("https://maps.apple.com/place?auid=3539789365695356771").unwrap();
        assert_eq!(link.provider_id.as_deref(), Some("3539789365695356771"));
        let link =
            parse("https://maps.apple.com/directions?destination=47.37,8.54&mode=driving").unwrap();
        assert_eq!(link.point, Point::new(47.37, 8.54));
        let link =
            parse("https://maps.apple.com/search?query=pizza&center=47.37,8.54&span=0.05,0.05")
                .unwrap();
        assert_eq!(link.query.as_deref(), Some("pizza"));
        assert_eq!(link.point, Point::new(47.37, 8.54));
        assert!(
            parse("https://maps.apple/p/AbC.dEf-123")
                .unwrap()
                .needs_redirect
        );
        assert_eq!(parse("https://maps.apple/x"), None);
        assert_eq!(parse("http://maps.apple.com/?ll="), None);
    }

    #[test]
    fn waze() {
        let link = parse("https://waze.com/ul?ll=47.37,8.54&z=10").unwrap();
        assert_eq!(link.point, Point::new(47.37, 8.54));
        assert_eq!(link.zoom, Some(10.0));
        let link = parse("https://www.waze.com/ul?ll=47.37%2C8.54&navigate=yes").unwrap();
        assert_eq!(link.point, Point::new(47.37, 8.54));
        assert_eq!(
            parse("https://waze.com/ul?q=Bahnhof")
                .unwrap()
                .query
                .as_deref(),
            Some("Bahnhof")
        );
        // waze.com/ul/h<geohash> redirects to live-map?h=<geohash> (checked 2026-11-25).
        let link = parse("https://waze.com/ul/hu0qjd2eyk").unwrap();
        let p = link.point.unwrap();
        assert!(
            (p.lat - 47.3769).abs() < 1e-4 && (p.lon - 8.5417).abs() < 1e-4,
            "{p:?}"
        );
        let link = parse("https://www.waze.com/live-map?h=u0qjd2eyk").unwrap();
        assert_eq!(link.point, Some(p));
        let link =
            parse("https://www.waze.com/de/live-map/directions?to=ll.47.3769%2C8.5417").unwrap();
        assert_eq!(link.point, Point::new(47.3769, 8.5417));
        assert_eq!(
            parse("waze://?ll=47.37,8.54&navigate=yes").unwrap().point,
            Point::new(47.37, 8.54)
        );
        assert_eq!(parse("https://waze.com/ul/x!"), None);
        assert_eq!(parse("https://www.waze.com/"), None);
    }

    #[test]
    fn geohashes() {
        let p = geohash("u4pruydqqvj").unwrap();
        assert!(
            (p.lat - 57.649_11).abs() < 1e-5 && (p.lon - 10.407_44).abs() < 1e-5,
            "{p:?}"
        );
        assert_eq!(geohash(""), None);
        assert_eq!(geohash("a"), None);
    }

    #[test]
    fn here() {
        let link = parse(
            "http://share.here.com/l/52.5134272,13.3778416,Hannah-Arendt-Stra%C3%9Fe?z=16.0&t=normal",
        )
        .unwrap();
        assert_eq!(link.point, Point::new(52.513_427_2, 13.377_841_6));
        assert_eq!(link.name.as_deref(), Some("Hannah-Arendt-Straße"));
        assert_eq!(link.zoom, Some(16.0));
        let link = parse("https://share.here.com/r/mylocation/47.37,8.54,Office?m=d").unwrap();
        assert_eq!(link.point, Point::new(47.37, 8.54));
        assert_eq!(link.name.as_deref(), Some("Office"));
        let link = parse("https://share.here.com/p/s-abc123").unwrap();
        assert_eq!(link.provider_id.as_deref(), Some("s-abc123"));
        assert_eq!(link.point, None);
        let link = parse(
            "https://www.here.com/location?map=52.5134272,13.3778416,16,normal&msg=Hannah-Arendt-Stra%C3%9Fe",
        )
        .unwrap();
        assert_eq!(link.point, Point::new(52.513_427_2, 13.377_841_6));
        assert_eq!(link.zoom, Some(16.0));
        assert_eq!(link.name.as_deref(), Some("Hannah-Arendt-Straße"));
        assert_eq!(
            parse("https://www.here.com/?map=48.23145,16.38454,15,normal")
                .unwrap()
                .zoom,
            Some(15.0)
        );
        assert_eq!(parse("http://share.here.com/l/"), None);
        assert_eq!(parse("https://www.here.com/location?map="), None);
    }
}
