//! Coordinates in link text: strict decimal numbers, `lat,lon` pairs and their formatting.

use crate::Point;

/// A plain decimal number: an optional sign, digits, and an optional fraction. No exponent,
/// no `inf`/`nan`, no bare `.5` or `5.`.
pub(crate) fn number(s: &str) -> Option<f64> {
    let s = s.trim();
    let digits = s
        .strip_prefix('-')
        .or_else(|| s.strip_prefix('+'))
        .unwrap_or(s);
    let (whole, fraction) = match digits.split_once('.') {
        Some((w, f)) => (w, Some(f)),
        None => (digits, None),
    };
    let valid = !whole.is_empty()
        && whole.bytes().all(|b| b.is_ascii_digit())
        && fraction.is_none_or(|f| !f.is_empty() && f.bytes().all(|b| b.is_ascii_digit()));
    if !valid {
        return None;
    }
    let value: f64 = s.trim_start_matches('+').parse().ok()?;
    value.is_finite().then_some(value)
}

/// `lat,lon` (spaces around either number allowed).
pub(crate) fn lat_lon(s: &str) -> Option<Point> {
    let (lat, lon) = s.split_once(',')?;
    Point::new(number(lat)?, number(lon)?)
}

/// Rounded to `digits` fraction digits, trailing zeros dropped, never `-0`.
pub fn format_coordinate(value: f64, digits: u8) -> String {
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

/// `geo:<lat>,<lon>` with at most `digits` fraction digits.
pub fn geo_uri(point: Point, digits: u8) -> String {
    format!(
        "geo:{},{}",
        format_coordinate(point.lat, digits),
        format_coordinate(point.lon, digits)
    )
}

/// `lat,lon` as outgoing links write it: 6 fraction digits (about 0.1 m), trailing zeros
/// dropped.
pub(crate) fn pair(point: Point) -> String {
    format!(
        "{},{}",
        format_coordinate(point.lat, 6),
        format_coordinate(point.lon, 6)
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn numbers() {
        for (s, v) in [
            ("47.37", 47.37),
            ("-8", -8.0),
            ("+8.5", 8.5),
            (" 1.25 ", 1.25),
            ("007.5", 7.5),
        ] {
            assert_eq!(number(s), Some(v), "{s}");
        }
        for s in [
            "", "-", ".5", "5.", "1e3", "inf", "NaN", "1.2.3", "--1", "0x10", "1,5",
        ] {
            assert_eq!(number(s), None, "{s}");
        }
    }

    #[test]
    fn pairs() {
        assert_eq!(lat_lon("47.37,8.54"), Point::new(47.37, 8.54));
        assert_eq!(lat_lon("47.37, 8.54"), Point::new(47.37, 8.54));
        assert_eq!(lat_lon("91,8"), None);
        assert_eq!(lat_lon("47,181"), None);
        assert_eq!(lat_lon("47"), None);
        assert_eq!(lat_lon("47,8,1"), None);
    }

    #[test]
    fn formatting() {
        assert_eq!(format_coordinate(47.376_871_2, 5), "47.37687");
        assert_eq!(format_coordinate(8.5, 5), "8.5");
        assert_eq!(format_coordinate(-0.000_001, 5), "0");
        assert_eq!(format_coordinate(47.0, 0), "47");
        let p = Point::new(47.376_871_2, -8.5).unwrap();
        assert_eq!(geo_uri(p, 5), "geo:47.37687,-8.5");
        assert_eq!(pair(p), "47.376871,-8.5");
    }
}
