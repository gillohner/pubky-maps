//! Organic Maps and CoMaps: `om://`/`cm://` API links, ge0 short links
//! (`omaps.app/<ge0>/<name>`, `comaps.at/…`, `ge0.me/…`) and clear-coordinate links
//! (`omaps.app/<lat>,<lon>/<name>?z=`).
//!
//! ge0 (Organic Maps `libs/ge0`): one zoom character (zoom = value / 4 + 4) then nine
//! characters of interleaved latitude/longitude bits, 3 + 3 per character, in a URL-safe
//! base64 alphabet. Names swap `_` and space, then are percent-encoded.

use percent_encoding::{AsciiSet, CONTROLS, percent_decode_str, utf8_percent_encode};
use url::Url;

use crate::coord::{lat_lon, number};
use crate::{MapLink, Point, Provider};

const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
const COORD_BITS: u32 = 30;
const POINT_CHARS: usize = 9;

fn provider_for(scheme_or_host: &str) -> Provider {
    match scheme_or_host {
        "cm" | "comaps" | "comaps.at" => Provider::CoMaps,
        _ => Provider::OrganicMaps,
    }
}

/// `om://…`, `cm://…`, `comaps://…`, `ge0://…`.
pub(crate) fn parse_scheme(original: &str, scheme: &str) -> Option<MapLink> {
    let rest = original[scheme.len() + 1..].strip_prefix("//")?;
    let provider = provider_for(scheme);
    let (head, query) = match rest.split_once('?') {
        Some((h, q)) => (h, q),
        None => (rest, ""),
    };
    let mut link = MapLink::new(provider, original);
    let params = || url::form_urlencoded::parse(query.as_bytes());
    match head.trim_end_matches('/') {
        "map" => {
            // One pin per `ll`, its `n` after it; the first pin is the place.
            let mut seen = false;
            for (key, value) in params() {
                match key.as_ref() {
                    "ll" if !seen => {
                        link.point = lat_lon(&value);
                        seen = link.point.is_some();
                    }
                    "ll" => break,
                    "n" if seen => link.set_name(&value),
                    "z" => link.set_zoom(number(&value)),
                    _ => {}
                }
            }
        }
        "route" => {
            for (key, value) in params() {
                match key.as_ref() {
                    "dll" => link.point = lat_lon(&value),
                    "daddr" => link.set_name(&value),
                    _ => {}
                }
            }
        }
        "search" => {
            for (key, value) in params() {
                match key.as_ref() {
                    "query" => link.set_query(&value),
                    "cll" => link.point = lat_lon(&value),
                    _ => {}
                }
            }
        }
        _ => return ge0_path(link, rest),
    }
    Some(link)
}

/// `https://omaps.app/…`, `https://comaps.at/…`, `http(s)://ge0.me/…`.
pub(crate) fn parse_web(url: &Url, host: &str, original: &str) -> Option<MapLink> {
    let link = MapLink::new(provider_for(host), original);
    // The raw path from the text: URL parsing would drop `.` and `..` segments, which are
    // names here.
    let path = original.splitn(4, '/').nth(3)?;
    let mut link = ge0_path(link, path)?;
    if link.zoom.is_none()
        && let Some((_, z)) = url.query_pairs().find(|(k, _)| k == "z")
    {
        link.set_zoom(number(&z));
    }
    Some(link)
}

/// `<ge0>[/<name>]` or `<lat>,<lon>[/<name>]` (query ignored).
fn ge0_path(mut link: MapLink, path: &str) -> Option<MapLink> {
    let path = path.split(['?', '#']).next().unwrap_or(path);
    let (code, name) = match path.split_once('/') {
        Some((code, name)) => (code, Some(name)),
        None => (path, None),
    };
    if let Some((point, zoom)) = decode(code) {
        link.point = Some(point);
        link.set_zoom(Some(zoom));
    } else {
        link.point = Some(lat_lon(&percent_decode_str(code).decode_utf8_lossy())?);
    }
    if let Some(name) = name {
        link.set_name(&decode_name(name));
    }
    Some(link)
}

fn decode_name(s: &str) -> String {
    let decoded = percent_decode_str(&s.replace('+', " "))
        .decode_utf8_lossy()
        .into_owned();
    swap_underscores(&decoded)
}

fn swap_underscores(s: &str) -> String {
    s.chars()
        .map(|c| match c {
            '_' => ' ',
            ' ' => '_',
            c => c,
        })
        .collect()
}

/// Organic Maps' `UrlEncodeString`: controls and URL punctuation percent-encoded.
const NAME_SET: &AsciiSet = &CONTROLS
    .add(b' ')
    .add(b'<')
    .add(b'>')
    .add(b'#')
    .add(b'%')
    .add(b'"')
    .add(b'!')
    .add(b'*')
    .add(b'\'')
    .add(b'(')
    .add(b')')
    .add(b';')
    .add(b':')
    .add(b'@')
    .add(b'&')
    .add(b'=')
    .add(b'+')
    .add(b'$')
    .add(b',')
    .add(b'/')
    .add(b'?')
    .add(b'[')
    .add(b']')
    .add(b'{')
    .add(b'}')
    .add(b'\\')
    .add(b'|')
    .add(b'^')
    .add(b'`');

pub(crate) fn encode_name(name: &str) -> String {
    utf8_percent_encode(&swap_underscores(name), NAME_SET).to_string()
}

fn value(c: u8) -> Option<u32> {
    ALPHABET.iter().position(|a| *a == c).map(|v| v as u32)
}

/// A 10-character ge0 code: its point (the centre of its cell) and zoom.
pub(crate) fn decode(code: &str) -> Option<(Point, f64)> {
    let bytes = code.as_bytes();
    if bytes.len() != POINT_CHARS + 1 {
        return None;
    }
    let zoom = f64::from(value(bytes[0])?) / 4.0 + 4.0;
    let (mut lat, mut lon) = (0u32, 0u32);
    let mut shift = COORD_BITS - 3;
    for c in &bytes[1..] {
        let a = value(*c)?;
        lat |= (((a >> 5) & 1) << 2 | ((a >> 3) & 1) << 1 | ((a >> 1) & 1)) << shift;
        lon |= (((a >> 4) & 1) << 2 | ((a >> 2) & 1) << 1 | (a & 1)) << shift;
        shift = shift.saturating_sub(3);
    }
    // Nine of ten characters: add half of the last cell to land in its centre.
    let middle = 1u32 << 2;
    let max = f64::from((1u32 << COORD_BITS) - 1);
    let lat = f64::from(lat + middle) / max * 180.0 - 90.0;
    let lon = f64::from(lon + middle) / (max + 1.0) * 360.0 - 180.0;
    Some((
        Point::new(lat.clamp(-90.0, 90.0), lon.clamp(-180.0, 180.0))?,
        zoom,
    ))
}

/// The ge0 code of a point at a zoom (Organic Maps `GenerateShortShowMapUrl`).
pub(crate) fn encode(point: Point, zoom: f64) -> String {
    let zoom_value = if zoom <= 4.0 {
        0
    } else if zoom >= 19.75 {
        63
    } else {
        ((zoom - 4.0) * 4.0) as usize
    };
    let max = (1u32 << COORD_BITS) - 1;
    let lat = ((point.lat + 90.0) / 180.0 * f64::from(max))
        .round()
        .clamp(0.0, f64::from(max)) as u32;
    let lon_180 = if point.lon >= 180.0 {
        point.lon - 360.0
    } else {
        point.lon
    };
    let x = (lon_180 + 180.0) / 360.0 * (f64::from(max) + 1.0) + 0.5;
    let lon = if x <= 0.0 || x >= f64::from(max) + 1.0 {
        0
    } else {
        x as u32
    };
    let mut out = String::with_capacity(POINT_CHARS + 1);
    out.push(char::from(ALPHABET[zoom_value]));
    let mut shift = COORD_BITS - 3;
    for _ in 0..POINT_CHARS {
        let la = (lat >> shift) & 7;
        let lo = (lon >> shift) & 7;
        let byte = ((la >> 2) & 1) << 5
            | ((lo >> 2) & 1) << 4
            | ((la >> 1) & 1) << 3
            | ((lo >> 1) & 1) << 2
            | (la & 1) << 1
            | (lo & 1);
        out.push(char::from(ALPHABET[byte as usize]));
        shift = shift.saturating_sub(3);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::parse;

    #[test]
    fn ge0_decodes_like_organic_maps() {
        // Vectors from organicmaps libs/ge0/ge0_tests/parser_tests.cpp, with its tolerances.
        let lat_eps = 8.0 / f64::from((1u32 << 30) - 1) * 180.0 / 1.77;
        let lon_eps = 8.0 / f64::from((1u32 << 30) - 1) * 360.0 / 1.77;
        for (code, lat, lon, zoom) in [
            ("Byqqqqqqqq", 45.0, 0.0, 4.25),
            ("B6qqqqqqqq", 90.0, 0.0, 4.25),
            ("BVVVVVVVVV", -90.0, 179.999_999, 4.25),
            ("BP________", -0.000_001, -0.000_001, 4.25),
            ("Bzqqqqqqqq", 45.0, 45.0, 4.25),
            ("BaF6F6F6F6", -20.0, 20.0, 4.25),
            ("B4srhdHVVt", 64.5234, 12.1234, 4.25),
            ("B_________", 90.0, 179.999_999, 4.25),
            ("Bqqqqqqqqq", 90.0, -180.0, 4.25),
            ("BAAAAAAAAA", -90.0, -180.0, 4.25),
            ("8wAAAAAAAA", 0.0, 0.0, 19.0),
            ("AwAAAAAAAA", 0.0, 0.0, 4.0),
        ] {
            let (point, z) = decode(code).unwrap();
            assert!(
                (point.lat - lat).abs() <= lat_eps,
                "{code} lat {}",
                point.lat
            );
            assert!(
                (point.lon - lon).abs() <= lon_eps,
                "{code} lon {}",
                point.lon
            );
            assert_eq!(z, zoom, "{code}");
        }
        for bad in [
            "Byqqqqqqq",
            "B",
            "",
            "!wAAAAAAAA",
            "/wAAAAAAAA",
            "AwAAAAAAAAs",
        ] {
            assert!(decode(bad).is_none(), "{bad}");
        }
    }

    #[test]
    fn ge0_encodes_like_organic_maps() {
        // organicmaps libs/ge0/ge0_tests/url_generator_tests.cpp.
        assert_eq!(encode(Point::new(0.0, 0.0).unwrap(), 19.0), "8wAAAAAAAA");
        assert_eq!(
            encode(Point::new(-90.0, -180.0).unwrap(), 4.0),
            "AAAAAAAAAA"
        );
        assert_eq!(encode(Point::new(90.0, 180.0).unwrap(), 4.25), "Bqqqqqqqqq");
        assert_eq!(
            encode(Point::new(64.5234, 12.1234).unwrap(), 4.25),
            "B4srhdHVVt"
        );
        assert_eq!(encode_name("Some Name_x"), "Some_Name%20x");
        assert_eq!(decode_name("Some_Name%20x"), "Some Name_x");
    }

    #[test]
    fn links() {
        let link = parse("https://omaps.app/B4srhdHVVt/Some+Name").unwrap();
        assert_eq!(link.provider, Provider::OrganicMaps);
        assert_eq!(link.name.as_deref(), Some("Some_Name"));
        assert_eq!(link.zoom, Some(4.25));

        let link = parse(
            "https://omaps.app/AwAAAAAAAA/%d0%9c%d0%b8%d0%bd%d1%81%d0%ba_%d1%83%d0%bb._%d0%9b%d0%b5%d0%bd%d0%b8%d0%bd%d0%b0_9",
        )
        .unwrap();
        assert_eq!(link.name.as_deref(), Some("Минск ул. Ленина 9"));

        let link = parse("https://omaps.app/-14.333333,-170/American_Samoa?z=9").unwrap();
        assert_eq!(link.point, Point::new(-14.333_333, -170.0));
        assert_eq!(link.name.as_deref(), Some("American Samoa"));
        assert_eq!(link.zoom, Some(9.0));

        let link = parse("https://comaps.at/B4srhdHVVt/Berlin").unwrap();
        assert_eq!(link.provider, Provider::CoMaps);
        let link = parse("http://ge0.me/B4srhdHVVt/Berlin").unwrap();
        assert_eq!(link.provider, Provider::OrganicMaps);

        let link =
            parse("om://map?v=1&ll=54.32123,12.34562&n=Point%20Name&id=x&backurl=a").unwrap();
        assert_eq!(link.point, Point::new(54.32123, 12.34562));
        assert_eq!(link.name.as_deref(), Some("Point Name"));
        let link = parse("cm://map?v=1&ll=47.37,8.54&n=Z%C3%BCri").unwrap();
        assert_eq!(link.provider, Provider::CoMaps);
        assert_eq!(link.name.as_deref(), Some("Züri"));
        let link = parse("om://B4srhdHVVt/Berlin").unwrap();
        assert_eq!(link.name.as_deref(), Some("Berlin"));
        let link =
            parse("om://route?sll=47.1,8.1&saddr=Home&dll=47.37,8.54&daddr=Office&type=vehicle")
                .unwrap();
        assert_eq!(link.point, Point::new(47.37, 8.54));
        assert_eq!(link.name.as_deref(), Some("Office"));
        let link = parse("om://search?query=cafe&cll=47.37,8.54").unwrap();
        assert_eq!(link.query.as_deref(), Some("cafe"));

        for bad in [
            "https://omaps.app/",
            "https://omaps.app/Byqqqqqqq",
            "https://omaps.app/91,1/x",
            "om://map?v=1",
            "om:map",
        ] {
            assert_eq!(parse(bad), None, "{bad}");
        }
    }
}
