//! `geo:` URIs: RFC 5870 `geo:lat,lon[,alt][;param…]`, plus Android's query: `?z=` (zoom) and
//! `?q=` (`lat,lon(Label)` for a pin, anything else is a search, near the path point unless it
//! is `0,0`).

use crate::coord::{lat_lon, number};
use crate::text::decode_segment;
use crate::{MapLink, Point, Provider};

pub(crate) fn parse(original: &str) -> Option<MapLink> {
    let body = &original["geo:".len()..];
    let (body, query) = match body.split_once('?') {
        Some((b, q)) => (b, Some(q)),
        None => (body, None),
    };
    // A fragment is not part of a geo URI.
    let body = body.split('#').next().unwrap_or(body);
    let mut params = body.split(';');
    let coords = params.next().unwrap_or_default();
    for param in params {
        let (key, value) = param.split_once('=').unwrap_or((param, ""));
        if key.eq_ignore_ascii_case("crs") && !value.eq_ignore_ascii_case("wgs84") {
            return None;
        }
    }
    let mut parts = coords.split(',');
    let lat = number(parts.next()?)?;
    let lon = number(parts.next()?)?;
    if let Some(alt) = parts.next() {
        number(alt)?;
    }
    if parts.next().is_some() {
        return None;
    }
    let centre = Point::new(lat, lon)?;

    let mut link = MapLink::new(Provider::Geo, original);
    let mut q = None;
    for pair in query.unwrap_or_default().split('&') {
        let (key, value) = pair.split_once('=').unwrap_or((pair, ""));
        match key {
            "z" => link.set_zoom(number(value)),
            "q" if q.is_none() => q = Some(decode_segment(value)),
            _ => {}
        }
    }
    // Android writes 0,0 for "no point": the point comes from `q`, or there is none.
    let has_centre = !(lat == 0.0 && lon == 0.0) || q.is_none();
    match q.as_deref().map(str::trim).filter(|q| !q.is_empty()) {
        Some(q) => match labelled_point(q) {
            Some((point, label)) => {
                link.point = Some(point);
                if let Some(label) = label {
                    link.set_name(label);
                }
            }
            None => {
                link.set_query(q);
                if has_centre {
                    link.point = Some(centre);
                }
            }
        },
        None => {
            if has_centre {
                link.point = Some(centre);
            }
        }
    }
    Some(link)
}

/// `lat,lon` or `lat,lon(Label)` (a space before the bracket is common).
pub(crate) fn labelled_point(q: &str) -> Option<(Point, Option<&str>)> {
    let (coords, label) = match q.split_once('(') {
        Some((coords, rest)) => (coords, Some(rest.strip_suffix(')').unwrap_or(rest))),
        None => (q, None),
    };
    Some((lat_lon(coords)?, label))
}

#[cfg(test)]
mod tests {
    use crate::{Point, parse};

    #[test]
    fn plain_points() {
        let link = parse("geo:47.3769,8.5417").unwrap();
        assert_eq!(link.point, Point::new(47.3769, 8.5417));
        assert_eq!(link.name, None);
        let link = parse("GEO:-90,180,-10.5;crs=wgs84;u=5").unwrap();
        assert_eq!(link.point, Point::new(-90.0, 180.0));
        assert_eq!(parse("geo:0,0").unwrap().point, Point::new(0.0, 0.0));
        let link = parse("geo:47.37,8.54?z=15").unwrap();
        assert_eq!(link.zoom, Some(15.0));
        assert_eq!(parse("geo:47.37,8.54?z=99").unwrap().zoom, None);
    }

    #[test]
    fn android_queries() {
        let link = parse("geo:0,0?q=47.37687,8.54169(Hauptbahnhof)").unwrap();
        assert_eq!(link.point, Point::new(47.37687, 8.54169));
        assert_eq!(link.name.as_deref(), Some("Hauptbahnhof"));

        // OsmAnd's test case: a space before the label, label in the q of a real point.
        let link = parse("geo:50.451300,30.569900?z=15&q=50.451300,30.569900 (Kiev)").unwrap();
        assert_eq!(link.point, Point::new(50.4513, 30.5699));
        assert_eq!(link.name.as_deref(), Some("Kiev"));
        assert_eq!(link.zoom, Some(15.0));

        let link = parse("geo:0,0?q=47.37687%2C8.54169%28Caf%C3%A9%29").unwrap();
        assert_eq!(link.point, Point::new(47.37687, 8.54169));
        assert_eq!(link.name.as_deref(), Some("Café"));

        // A search by name or address: no point, never 0,0.
        let link = parse("geo:0,0?q=Hauptbahnhof+Z%C3%BCrich").unwrap();
        assert_eq!(link.point, None);
        assert_eq!(link.query.as_deref(), Some("Hauptbahnhof Zürich"));
        let link = parse("geo:0,0?q=86HJV99P%2B29").unwrap();
        assert_eq!(link.query.as_deref(), Some("86HJV99P+29"));

        // A search near a point keeps the point.
        let link = parse("geo:47.37,8.54?q=restaurants").unwrap();
        assert_eq!(link.point, Point::new(47.37, 8.54));
        assert_eq!(link.query.as_deref(), Some("restaurants"));

        // An empty query at 0,0 says nothing.
        assert_eq!(parse("geo:0,0?q="), None);
        assert_eq!(parse("geo:0,0?z=11&q="), None);
    }

    #[test]
    fn rejected() {
        for input in [
            "geo:",
            "geo:?q=",
            "geo:47.3",
            "geo:91,8",
            "geo:47,181",
            "geo:abc,def",
            "geo:1e2,3",
            "geo:inf,1",
            "geo:NaN,0",
            "geo:47.,8",
            "geo:47.3,8.5,1,2",
            "geo:47.3,8.5;crs=other",
        ] {
            assert_eq!(parse(input), None, "{input}");
        }
    }
}
