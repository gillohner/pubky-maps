//! Google Maps links.
//!
//! The place's own point is the `!3d<lat>!4d<lon>` pair in the `data=` part; `@lat,lon,17z` is
//! only the map view, used when nothing better is there. The name is the `/maps/place/<name>/`
//! segment. The CID (Google's place id) comes from `?cid=`, `ftid=` or the feature id
//! `0x…:0x<cid>` in `data=`.

use url::Url;

use crate::coord::{lat_lon, number};
use crate::geo::labelled_point;
use crate::text::decode_segment;
use crate::{MapLink, Point, Provider};

/// `google.<tld>`, `www.google.<tld>` or `maps.google.<tld>`, for country domains such as
/// `google.de`, `google.co.uk` and `google.com.tr`.
pub(crate) fn is_google_host(host: &str) -> bool {
    let host = host
        .strip_prefix("www.")
        .or_else(|| host.strip_prefix("maps."))
        .unwrap_or(host);
    let Some(tld) = host.strip_prefix("google.") else {
        return false;
    };
    let labels: Vec<&str> = tld.split('.').collect();
    (1..=2).contains(&labels.len())
        && labels
            .iter()
            .all(|l| (2..=3).contains(&l.len()) && l.bytes().all(|b| b.is_ascii_lowercase()))
}

/// `maps.app.goo.gl/<id>` and `goo.gl/maps/<id>`: the place is behind the redirect.
pub(crate) fn parse_short(url: &Url, host: &str, original: &str) -> Option<MapLink> {
    let path = url.path().trim_matches('/');
    let id = if host == "goo.gl" {
        path.strip_prefix("maps/")?
    } else {
        path
    };
    if id.is_empty() || id.contains('/') {
        return None;
    }
    let mut link = MapLink::new(Provider::Google, original);
    link.needs_redirect = true;
    Some(link)
}

/// `google.navigation:q=lat,lon&mode=d` (Android's navigation intent).
pub(crate) fn parse_navigation(original: &str) -> Option<MapLink> {
    let query = &original["google.navigation:".len()..];
    let mut link = MapLink::new(Provider::Google, original);
    for pair in query.split('&') {
        if let Some(q) = pair.strip_prefix("q=") {
            let q = decode_segment(q);
            match lat_lon(&q) {
                Some(point) => link.point = Some(point),
                None => link.set_query(&q),
            }
        }
    }
    Some(link)
}

pub(crate) fn parse_web(url: &Url, host: &str, original: &str) -> Option<MapLink> {
    let segments: Vec<&str> = url
        .path_segments()
        .map(Iterator::collect)
        .unwrap_or_default();
    // On google.<tld> only /maps is the map; maps.google.<tld> is the map everywhere.
    let maps_host = host.starts_with("maps.");
    let rest: &[&str] = match segments.first() {
        Some(&"maps") => &segments[1..],
        _ if maps_host => &segments[..],
        _ => return None,
    };
    let mut link = MapLink::new(Provider::Google, original);

    // The place's own point, wherever the data part is (path, query or the old #! fragment).
    let raw = original.replace("%21", "!");
    let mut place = data_point(&raw);
    let mut view = None;
    let mut path_point = None;

    match rest.first().copied() {
        Some("place") => {
            if let Some(segment) = rest.get(1).filter(|s| !is_view_or_data(s)) {
                let name = decode_segment(segment);
                match labelled_point(name.trim()) {
                    Some((point, _)) => path_point = Some(point),
                    // Degrees-minutes-seconds names ("10°07'16.8\"N …") are coordinates, not names.
                    None if name.contains('°') => {}
                    None => link.set_name(&name),
                }
            }
        }
        Some("search") => {
            if let Some(segment) = rest.get(1).filter(|s| !is_view_or_data(s)) {
                let text = decode_segment(segment);
                match lat_lon(&text) {
                    Some(point) => path_point = Some(point),
                    None => link.set_query(&text),
                }
            }
        }
        Some("dir") => {
            // The last stop is the destination.
            if let Some(segment) = rest[1..]
                .iter()
                .rev()
                .find(|s| !s.is_empty() && !is_view_or_data(s))
            {
                let text = decode_segment(segment);
                match lat_lon(&text) {
                    Some(point) => path_point = Some(point),
                    None => link.set_query(&text),
                }
            }
        }
        _ => {}
    }
    for segment in rest {
        if let Some(v) = segment.strip_prefix('@') {
            let (point, zoom) = viewport(v);
            view = view.or(point);
            link.set_zoom(zoom);
        }
    }

    let mut query_point = None;
    for (key, value) in url.query_pairs() {
        match key.as_ref() {
            "q" | "query" | "daddr" | "destination" => {
                let value = value.trim();
                let value = value.strip_prefix("loc:").unwrap_or(value);
                // "Bahnhofplatz 3, 7000 Chur@46.853582,9.529903": a label and its point.
                let (label, at) = match value.rsplit_once('@') {
                    Some((label, at)) if lat_lon(at).is_some() => (label, Some(at)),
                    _ => (value, None),
                };
                if let Some(point) = at.and_then(lat_lon) {
                    query_point = query_point.or(Some(point));
                    link.set_query(label);
                } else if let Some((point, name)) = labelled_point(label) {
                    query_point = query_point.or(Some(point));
                    if let Some(name) = name {
                        link.set_name(name);
                    }
                } else {
                    link.set_query(label);
                }
            }
            "ll" | "center" => view = view.or_else(|| lat_lon(&value)),
            "z" | "zoom" => link.set_zoom(number(&value)),
            "cid" => {
                if value.bytes().all(|b| b.is_ascii_digit()) && !value.is_empty() {
                    link.provider_id = value.parse::<u64>().ok().map(|c| c.to_string());
                }
            }
            "ftid" => link.provider_id = link.provider_id.take().or_else(|| feature_cid(&value)),
            _ => {}
        }
    }
    if link.provider_id.is_none() {
        link.provider_id = data_feature_cid(&raw);
    }
    place = place.or(query_point).or(path_point);
    link.point_is_view = place.is_none() && view.is_some();
    link.point = place.or(view);
    link.query_names_the_place();
    Some(link)
}

fn is_view_or_data(segment: &str) -> bool {
    segment.starts_with('@') || segment.starts_with("data=") || segment.starts_with("am=")
}

/// `lat,lon,17z` (or `…,247m` for a height in metres: no zoom then).
fn viewport(s: &str) -> (Option<Point>, Option<f64>) {
    let mut parts = s.split(',');
    let (Some(lat), Some(lon)) = (parts.next(), parts.next()) else {
        return (None, None);
    };
    let point = number(lat)
        .zip(number(lon))
        .and_then(|(lat, lon)| Point::new(lat, lon));
    let zoom = parts
        .next()
        .and_then(|z| z.strip_suffix('z'))
        .and_then(number);
    (point, zoom)
}

/// The last `!3d<lat>!4d<lon>` pair: the place in a `data=` part.
fn data_point(s: &str) -> Option<Point> {
    let mut found = None;
    let mut rest = s;
    while let Some(i) = rest.find("!3d") {
        rest = &rest[i + 3..];
        let lat_end = rest.find('!').unwrap_or(rest.len());
        let lat = &rest[..lat_end];
        if let Some(lon) = rest[lat_end..].strip_prefix("!4d") {
            let lon_end = lon.find(['!', '?', '&', '#', '/']).unwrap_or(lon.len());
            if let Some(point) = number(lat)
                .zip(number(&lon[..lon_end]))
                .and_then(|(lat, lon)| Point::new(lat, lon))
            {
                found = Some(point);
            }
        }
    }
    found
}

/// The CID in a feature id `0x<hex>:0x<cid hex>`.
fn feature_cid(s: &str) -> Option<String> {
    let s = s.replace("%3A", ":").replace("%3a", ":");
    let (_, cid) = s.split_once(':')?;
    let hex = cid.strip_prefix("0x")?;
    let end = hex
        .find(|c: char| !c.is_ascii_hexdigit())
        .unwrap_or(hex.len());
    let value = u64::from_str_radix(hex.get(..end)?, 16).ok()?;
    (value != 0).then(|| value.to_string())
}

/// The CID of the feature id `!1s0x…:0x…` in a `data=` part.
fn data_feature_cid(s: &str) -> Option<String> {
    let mut found = None;
    let mut rest = s;
    while let Some(i) = rest.find("!1s0x") {
        rest = &rest[i + 3..];
        let end = rest.find('!').unwrap_or(rest.len());
        if let Some(cid) = feature_cid(&rest[..end]) {
            found = Some(cid);
        }
    }
    found
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::parse;

    #[test]
    fn hosts() {
        for host in [
            "google.com",
            "www.google.com",
            "maps.google.com",
            "www.google.de",
            "www.google.co.uk",
            "www.google.com.tr",
            "maps.google.co.in",
        ] {
            assert!(is_google_host(host), "{host}");
        }
        for host in [
            "google",
            "notgoogle.com",
            "google.evil.example.com",
            "www.google.c",
            "google.com.attacker",
        ] {
            assert!(!is_google_host(host), "{host}");
        }
    }

    #[test]
    fn feature_ids() {
        assert_eq!(
            feature_cid("0x479e7415349b0571:0xb7e03dcf1f6347f6").as_deref(),
            Some("13249658063518124022")
        );
        assert_eq!(feature_cid("0x0:0x0"), None);
        assert_eq!(feature_cid("junk"), None);
    }

    #[test]
    fn short_links() {
        let link = parse("https://maps.app.goo.gl/AbCdEf123").unwrap();
        assert!(link.needs_redirect);
        assert_eq!(link.point, None);
        assert!(parse("https://goo.gl/maps/Cji0V").unwrap().needs_redirect);
        assert_eq!(parse("https://goo.gl/abc"), None);
        assert_eq!(parse("https://maps.app.goo.gl/"), None);
    }
}
