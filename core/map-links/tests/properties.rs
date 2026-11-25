//! Property tests: every outgoing link reads back as the place it was built from, and no input
//! makes the reader panic or return out-of-range values (a proptest fuzz over `parse`).

use map_links::{
    App, MAX_NAME_CHARS, OsmKind, OsmRef, Place, Point, Provider, Target, TravelMode, ZOOM_RANGE,
    build, canonical_place_uri, find_in_text, parse,
};
use proptest::prelude::*;

const APPS: [App; 12] = [
    App::GoogleMaps,
    App::GoogleNavigation,
    App::AppleMaps,
    App::Waze,
    App::OsmAnd,
    App::OrganicMaps,
    App::OrganicMapsWeb,
    App::CoMaps,
    App::CoMapsWeb,
    App::HereWeGo,
    App::OpenStreetMap,
    App::Geo,
];

const MODES: [TravelMode; 4] = [
    TravelMode::Driving,
    TravelMode::Walking,
    TravelMode::Cycling,
    TravelMode::Transit,
];

fn provider(app: App) -> Provider {
    match app {
        App::GoogleMaps | App::GoogleNavigation => Provider::Google,
        App::AppleMaps => Provider::Apple,
        App::Waze => Provider::Waze,
        App::OsmAnd => Provider::OsmAnd,
        App::OrganicMaps | App::OrganicMapsWeb => Provider::OrganicMaps,
        App::CoMaps | App::CoMapsWeb => Provider::CoMaps,
        App::HereWeGo => Provider::Here,
        App::OpenStreetMap => Provider::OpenStreetMap,
        App::Geo => Provider::Geo,
    }
}

/// Formats that carry the place's name.
fn carries_name(app: App) -> bool {
    matches!(
        app,
        App::AppleMaps
            | App::OrganicMaps
            | App::OrganicMapsWeb
            | App::CoMaps
            | App::CoMapsWeb
            | App::HereWeGo
            | App::Geo
    )
}

/// The largest coordinate error of each format: 6 fraction digits, or a ge0 cell centre.
fn tolerance(app: App) -> f64 {
    match app {
        App::OrganicMapsWeb | App::CoMapsWeb => 4e-6,
        _ => 1e-6,
    }
}

fn close(a: Point, b: Point, tol: f64) -> bool {
    let dlon = (a.lon - b.lon).abs();
    (a.lat - b.lat).abs() <= tol && (dlon <= tol || (360.0 - dlon) <= tol)
}

/// Names as people write them: letters (some non-ASCII), digits, punctuation, spaces.
fn name() -> impl Strategy<Value = String> {
    "[A-Za-z0-9äöüéßЖ中 ,.'&()_+/#?%-]{1,40}"
}

/// How the reader shows a name: whitespace runs collapsed, trimmed.
fn shown(name: &str) -> Option<String> {
    let collapsed = name.split_whitespace().collect::<Vec<_>>().join(" ");
    (!collapsed.is_empty()).then(|| collapsed.chars().take(MAX_NAME_CHARS).collect())
}

fn point() -> impl Strategy<Value = Point> {
    (-90.0f64..=90.0, -180.0f64..180.0).prop_map(|(lat, lon)| Point::new(lat, lon).unwrap())
}

fn place() -> impl Strategy<Value = Place> {
    (
        point(),
        proptest::option::of(name()),
        proptest::option::of(1u8..=20),
    )
        .prop_map(|(point, name, zoom)| Place {
            point,
            name,
            osm: None,
            zoom: zoom.map(f64::from),
        })
}

fn round_trip_config() -> ProptestConfig {
    let mut config = ProptestConfig::default();
    if std::env::var_os("PROPTEST_CASES").is_none() {
        config.cases = 512;
    }
    config
}

proptest! {
    #![proptest_config(round_trip_config())]

    #[test]
    fn places_read_back(place in place(), app in proptest::sample::select(APPS.to_vec())) {
        let Some(url) = build(app, &Target::Place(place.clone())) else {
            prop_assert_eq!(app, App::GoogleNavigation);
            return Ok(());
        };
        let link = parse(&url).unwrap_or_else(|| panic!("not read back: {url}"));
        prop_assert_eq!(link.provider, provider(app), "{}", url);
        let got = link.point.unwrap_or_else(|| panic!("no point: {url}"));
        prop_assert!(close(got, place.point, tolerance(app)), "{} -> {:?}", url, got);
        if carries_name(app) {
            let want = place.name.as_deref().and_then(shown);
            prop_assert_eq!(link.name.clone(), want, "{}", url);
        }
        if let Some(zoom) = place.zoom {
            // ge0 codes zoom levels 4 to 19.75 only.
            let zoom = if tolerance(app) > 1e-6 { zoom.clamp(4.0, 19.75) } else { zoom };
            if let Some(got) = link.zoom {
                prop_assert!((got - zoom).abs() <= 0.25, "{} zoom {}", url, got);
            }
        }
        // The canonical spelling of a point is stable.
        let canonical = canonical_place_uri(&link).unwrap();
        let again = parse(&canonical).unwrap();
        prop_assert_eq!(canonical_place_uri(&again), Some(canonical));
    }

    #[test]
    fn directions_read_back(
        place in place(),
        app in proptest::sample::select(APPS.to_vec()),
        mode in proptest::sample::select(MODES.to_vec()),
    ) {
        let target = Target::Directions { destination: place.clone(), mode };
        if let Some(url) = build(app, &target) {
            let link = parse(&url).unwrap_or_else(|| panic!("not read back: {url}"));
            let got = link.point.unwrap_or_else(|| panic!("no point: {url}"));
            prop_assert!(close(got, place.point, tolerance(app)), "{} -> {:?}", url, got);
        }
    }

    #[test]
    fn osm_elements_read_back(
        kind in proptest::sample::select(vec![OsmKind::Node, OsmKind::Way, OsmKind::Relation]),
        id in 1u64..=u64::MAX,
        point in point(),
    ) {
        let osm = OsmRef { kind, id };
        let place = Place { point, name: None, osm: Some(osm), zoom: None };
        let url = build(App::OpenStreetMap, &Target::Place(place)).unwrap();
        let link = parse(&url).unwrap();
        prop_assert_eq!(link.osm, Some(osm));
        prop_assert_eq!(canonical_place_uri(&link), Some(url));
    }
}

/// Link-like inputs: a known prefix and random tails of URL characters.
fn link_like() -> impl Strategy<Value = String> {
    let prefixes = vec![
        "geo:",
        "geo:0,0?q=",
        "GEO:47.3,8.5;",
        "google.navigation:q=",
        "om://",
        "om://map?v=1&ll=",
        "cm://route?",
        "ge0://",
        "waze://?ll=",
        "https://www.google.com/maps/",
        "https://www.google.com/maps/place/",
        "https://maps.google.co.uk/?q=",
        "https://www.google.com/maps?ftid=0x1:0x",
        "https://maps.app.goo.gl/",
        "https://goo.gl/maps/",
        "https://maps.apple.com/?",
        "https://maps.apple.com/place?coordinate=",
        "https://maps.apple/p/",
        "https://waze.com/ul",
        "https://www.waze.com/live-map?h=",
        "https://www.openstreetmap.org/",
        "https://osm.org/go/",
        "https://www.openstreetmap.org/#map=",
        "https://osmand.net/map?pin=",
        "https://osmand.net/go?lat=",
        "https://omaps.app/",
        "https://comaps.at/",
        "http://ge0.me/",
        "https://share.here.com/l/",
        "https://share.here.com/r/",
        "https://www.here.com/?map=",
    ];
    (
        proptest::sample::select(prefixes),
        "[-0-9a-zA-Z.,;:/?#&=@!%+()_~ \u{0}\u{7f}äЖ]{0,80}",
    )
        .prop_map(|(prefix, tail)| format!("{prefix}{tail}"))
}

fn check_invariants(input: &str) -> Result<(), TestCaseError> {
    if let Some(link) = parse(input) {
        if let Some(p) = link.point {
            prop_assert!(
                p.lat.is_finite() && (-90.0..=90.0).contains(&p.lat),
                "{input}"
            );
            prop_assert!(
                p.lon.is_finite() && (-180.0..=180.0).contains(&p.lon),
                "{input}"
            );
        }
        if let Some(z) = link.zoom {
            prop_assert!(ZOOM_RANGE.contains(&z), "{input}");
        }
        for text in [&link.name, &link.query].into_iter().flatten() {
            prop_assert!(
                !text.is_empty() && text.chars().count() <= MAX_NAME_CHARS,
                "{input}"
            );
            prop_assert!(!text.chars().any(char::is_control), "{input}");
            prop_assert_eq!(text.trim(), text.as_str(), "{}", input);
        }
        if let Some(osm) = link.osm {
            prop_assert!(osm.id > 0, "{input}");
        }
        prop_assert_eq!(link.original.as_str(), input.trim());
        if let Some(canonical) = canonical_place_uri(&link) {
            prop_assert!(parse(&canonical).is_some(), "{input} -> {canonical}");
        }
    }
    Ok(())
}

/// 4096 cases by default; `PROPTEST_CASES` sets more for a longer fuzz run.
fn fuzz_config() -> ProptestConfig {
    let mut config = ProptestConfig::default();
    if std::env::var_os("PROPTEST_CASES").is_none() {
        config.cases = 4096;
    }
    config
}

proptest! {
    #![proptest_config(fuzz_config())]

    #[test]
    fn parse_never_panics_on_link_like_input(input in link_like()) {
        check_invariants(&input)?;
        let _ = find_in_text(&format!("Name\n{input} more"));
    }

    #[test]
    fn parse_never_panics_on_any_input(input in "\\PC{0,200}") {
        check_invariants(&input)?;
        let _ = find_in_text(&input);
    }
}
