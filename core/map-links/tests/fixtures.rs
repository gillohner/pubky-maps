//! Real-world links: from the research notes (links tested live on 2026-11-25), OsmAnd's
//! `GeoPointParserUtilTest`, Organic Maps' url-processor and ge0 tests, Pinakes' geo resolver
//! tests and Pinakes `also_on` answers.

use map_links::{OsmKind, OsmRef, Point, Provider, canonical_place_uri, parse};

#[derive(Default)]
struct Expect {
    point: Option<(f64, f64)>,
    name: Option<&'static str>,
    query: Option<&'static str>,
    osm: Option<(OsmKind, u64)>,
    zoom: Option<f64>,
    provider_id: Option<&'static str>,
    needs_redirect: bool,
}

fn at(lat: f64, lon: f64) -> Expect {
    Expect {
        point: Some((lat, lon)),
        ..Expect::default()
    }
}

impl Expect {
    fn name(mut self, name: &'static str) -> Self {
        self.name = Some(name);
        self
    }
    fn query(mut self, query: &'static str) -> Self {
        self.query = Some(query);
        self
    }
    fn zoom(mut self, zoom: f64) -> Self {
        self.zoom = Some(zoom);
        self
    }
    fn id(mut self, id: &'static str) -> Self {
        self.provider_id = Some(id);
        self
    }
    fn osm(mut self, kind: OsmKind, id: u64) -> Self {
        self.osm = Some((kind, id));
        self
    }
}

fn none() -> Expect {
    Expect::default()
}

fn redirect() -> Expect {
    Expect {
        needs_redirect: true,
        ..Expect::default()
    }
}

fn check(provider: Provider, cases: &[(&str, Expect)]) {
    for (url, expect) in cases {
        let link = parse(url).unwrap_or_else(|| panic!("not read: {url}"));
        assert_eq!(link.provider, provider, "{url}");
        match (expect.point, link.point) {
            (Some((lat, lon)), Some(p)) => assert!(
                (p.lat - lat).abs() < 1e-6 && (p.lon - lon).abs() < 1e-6,
                "{url}: {p:?}"
            ),
            (None, None) => {}
            (want, got) => panic!("{url}: point {got:?}, expected {want:?}"),
        }
        assert_eq!(link.name.as_deref(), expect.name, "{url}");
        assert_eq!(link.query.as_deref(), expect.query, "{url}");
        assert_eq!(
            link.osm,
            expect.osm.map(|(kind, id)| OsmRef { kind, id }),
            "{url}"
        );
        assert_eq!(link.zoom, expect.zoom, "{url}");
        assert_eq!(link.provider_id.as_deref(), expect.provider_id, "{url}");
        assert_eq!(link.needs_redirect, expect.needs_redirect, "{url}");
        assert_eq!(link.original, url.trim());
    }
}

#[test]
fn google() {
    check(
        Provider::Google,
        &[
            // Where a maps.app.goo.gl link redirects (research notes): the place, not the view.
            (
                "https://www.google.com/maps/place/Kamzik/@48.1821032,17.0941412,17z/data=!4m6!3m5!1s0x476c8c15dee00531:0x9fe526fd2f5bdb5b!8m2!3d48.1826322!4d17.0949707!16zL20vMDVqZGI0",
                at(48.1826322, 17.0949707)
                    .name("Kamzik")
                    .zoom(17.0)
                    .id("11521658090560412507"),
            ),
            (
                "https://www.google.at/maps/place/Bargou,+Tunesien/@36.0922506,9.5676327,15z/data=!3m1!4b1!4m2!3m1!1s0x12fc5d0b4dc5e66f:0xbd3618c6193d14cd",
                at(36.0922506, 9.5676327)
                    .name("Bargou, Tunesien")
                    .zoom(15.0)
                    .id("13634112161016779981"),
            ),
            (
                "https://www.google.co.in/maps/place/10%C2%B007'16.8%22N+76%C2%B020'54.2%22E/@10.1213253,76.3478427,247m/data=!3m2!1e3!4b1!4m6!3m5!1s0x0:0x0!7e2!8m2!3d10.1213237!4d76.348392?shorturl=1",
                at(10.1213237, 76.348392),
            ),
            (
                "https://www.google.co.in/maps/place/data=!3m2!1e3!4b1!4m6!3m5!1s0x0:0x0!7e2!8m2!3d10.1213237!4d76.348392?shorturl=1",
                at(10.1213237, 76.348392),
            ),
            (
                "https://www.google.com/maps/place/F79P%2BJ43+Madi+Cottage+And+Party+Palace,+Baruwa,+Madi+Road,+Madi+44200/@27.4695437,84.2860334,17z/data=!4m6!3m5!1s0x3994f56ccd448a8b:0xd6641aa08823442a!8m2!16s%2Fg%2F11fz9wkqn2",
                at(27.4695437, 84.2860334)
                    .name("F79P+J43 Madi Cottage And Party Palace, Baruwa, Madi Road, Madi 44200")
                    .zoom(17.0)
                    .id("15448501898568746026"),
            ),
            (
                "https://www.google.com/maps/place/Madi/data=!4m2!3m1!1s0x3994f56ccd448a8b:0xd6641aa08823442a?utm_source=mstt_1&entry=gps&lucs=47062702",
                none().name("Madi").id("15448501898568746026"),
            ),
            (
                "https://www.google.com/maps/place/Wild+Herb+Market/@33.32787,-105.66291,14z/data=!4m5!1m2!2m1!1sfood!3m1!1s0x86e1ce2079e1f94b:0x1d7460465dcaf3ed",
                at(33.32787, -105.66291)
                    .name("Wild Herb Market")
                    .zoom(14.0)
                    .id("2122427179735905261"),
            ),
            (
                "https://www.google.com/maps/preview#!q=paris&data=!4m15!2m14!1m13!1s0x47e66e1f06e2b70f%3A0x40b82c3688c9460!3m8!1m3!1d24383582!2d-95.677068!3d37.0625!3m2!1i1222!2i718!4f13.1!4m2!3d48.856614!4d2.3522219",
                at(48.856614, 2.3522219).id("291470376679806048"),
            ),
            (
                "https://www.google.com/maps/dir/?api=1&origin=-39.7649077,175.8822549&destination=-41.156615,174.975586",
                at(-41.156615, 174.975586),
            ),
            (
                "https://www.google.com/maps?daddr=Bahnhofplatz+3,+7000+Chur@46.853582,9.529903",
                at(46.853582, 9.529903).query("Bahnhofplatz 3, 7000 Chur"),
            ),
            (
                "https://www.google.com/maps?daddr=Bahnhofplatz+3,+7000+Chur",
                none().query("Bahnhofplatz 3, 7000 Chur"),
            ),
            (
                "http://maps.google.com/?q=query&ftid=0x3f8dfd04d309f925:0x2867166b05b0bfe6&hl=en&gl=us&shorturl=1",
                none().name("query").id("2911320333031096294"),
            ),
            (
                "https://maps.google.com/maps?hl=en-US&gl=de&um=1&ie=UTF-8&fb=1&sa=X&ftid=0x479e7415349b0571:0xb7e03dcf1f6347f6",
                none().id("13249658063518124022"),
            ),
            (
                "http://www.google.com/maps/search/food/@34.99393,-106.61568,14z",
                at(34.99393, -106.61568).query("food").zoom(14.0),
            ),
            (
                "http://www.google.com/maps/search/food/",
                none().query("food"),
            ),
            // Where goo.gl/maps/Cji0V redirects (checked 2026-11-25).
            (
                "http://maps.google.com/?q=28.118860,98.008069&hl=en&gl=us",
                at(28.11886, 98.008069),
            ),
            (
                "https://maps.google.com/maps?q=loc:47.3769,8.5417",
                at(47.3769, 8.5417),
            ),
            (
                "http://www.google.com.tr/maps?q=47.3769,8.5417(Zurich)",
                at(47.3769, 8.5417).name("Zurich"),
            ),
            (
                "https://www.google.com/maps/@47.3769,8.5417,15z",
                at(47.3769, 8.5417).zoom(15.0),
            ),
            (
                "https://www.google.com/maps/search/?api=1&query=47.37,8.54",
                at(47.37, 8.54),
            ),
            (
                "https://www.google.com/maps/search/?api=1&query=pizza+zurich",
                none().query("pizza zurich"),
            ),
            (
                "https://www.google.com/maps/dir/?api=1&destination=47.37,8.54&travelmode=driving&dir_action=navigate",
                at(47.37, 8.54),
            ),
            (
                "https://www.google.com/maps/dir/Current+Location/Z%C3%BCrich+HB",
                none().query("Zürich HB"),
            ),
            // Pinakes also_on, from Wikidata.
            (
                "https://www.google.com/maps?cid=297554536935865200",
                none().id("297554536935865200"),
            ),
            ("google.navigation:q=47.37,8.54&mode=d", at(47.37, 8.54)),
            ("https://maps.app.goo.gl/AbCdEf123", redirect()),
            ("http://goo.gl/maps/Cji0V", redirect()),
        ],
    );
    for url in [
        "http://maps.google.com/maps/@",
        "http://www.google.com/maps/place/",
        "http://www.google.com/maps?q=",
        "https://www.google.com/maps/@,15z",
        "https://www.google.com/search?q=47.37,8.54",
        "https://google.example.com/maps/@47.3,8.5,15z",
    ] {
        assert_eq!(parse(url), None, "{url}");
    }
}

#[test]
fn apple() {
    check(
        Provider::Apple,
        &[
            // Where a maps.apple/p/ link redirects (research notes, tested live).
            (
                "https://maps.apple.com/place?address=4100%20W%20Reno%20Ave,%20Oklahoma%20City&coordinate=35.307955,-97.537610",
                at(35.307955, -97.53761).name("4100 W Reno Ave, Oklahoma City"),
            ),
            (
                "https://maps.apple.com/place?coordinate=47.37,8.54&name=Z%C3%BCrich%20HB",
                at(47.37, 8.54).name("Zürich HB"),
            ),
            (
                "https://maps.apple.com/directions?destination=47.37,8.54&mode=driving",
                at(47.37, 8.54),
            ),
            (
                "https://maps.apple.com/search?query=pizza&center=47.37,8.54&span=0.05,0.05",
                at(47.37, 8.54).query("pizza"),
            ),
            (
                "https://maps.apple.com/frame?center=47.37,8.54",
                at(47.37, 8.54),
            ),
            (
                "https://maps.apple.com/look-around?coordinate=47.37,8.54",
                at(47.37, 8.54),
            ),
            (
                "https://maps.apple.com/?ll=47.37,8.54&q=Label",
                at(47.37, 8.54).name("Label"),
            ),
            (
                "http://maps.apple.com/?q=Bargou,+Tunisien",
                none().query("Bargou, Tunisien"),
            ),
            (
                "http://maps.apple.com/?daddr=Bargou,+Tunisien",
                none().query("Bargou, Tunisien"),
            ),
            (
                "http://maps.apple.com/?lsp=7618&q=40.738065,-73.988898&sll=40.738065,-73.988898",
                at(40.738065, -73.988898),
            ),
            (
                "https://maps.apple.com/place?auid=3539789365695356771",
                none().id("3539789365695356771"),
            ),
            ("https://maps.apple/p/AbC.dEf-123", redirect()),
        ],
    );
}

#[test]
fn waze() {
    check(
        Provider::Waze,
        &[
            (
                "https://waze.com/ul?ll=47.37,8.54&z=10",
                at(47.37, 8.54).zoom(10.0),
            ),
            (
                "https://waze.com/ul?ll=47.37,8.54&navigate=yes&utm_source=x",
                at(47.37, 8.54),
            ),
            (
                "https://waze.com/ul?q=Bahnhofstrasse",
                none().query("Bahnhofstrasse"),
            ),
        ],
    );
}

#[test]
fn openstreetmap_and_osmand() {
    check(
        Provider::OpenStreetMap,
        &[
            (
                "https://www.openstreetmap.org/node/240109189",
                none().osm(OsmKind::Node, 240_109_189),
            ),
            (
                "http://openstreetmap.org/node/240109189?x=1",
                none().osm(OsmKind::Node, 240_109_189),
            ),
            (
                "https://osm.org/node/240109189/",
                none().osm(OsmKind::Node, 240_109_189),
            ),
            (
                "https://www.openstreetmap.org/relation/146656",
                none().osm(OsmKind::Relation, 146_656),
            ),
            (
                "https://www.openstreetmap.org/way/50637691#map=18/52.5/13.4",
                at(52.5, 13.4).osm(OsmKind::Way, 50_637_691).zoom(18.0),
            ),
            (
                "https://www.openstreetmap.org/?mlat=52.5186925&mlon=13.3996024",
                at(52.5186925, 13.3996024),
            ),
            (
                "https://osm.org/?mlat=52.5&mlon=13.4&zoom=17#map=17/52.5/13.4",
                at(52.5, 13.4).zoom(17.0),
            ),
            (
                "https://www.openstreetmap.org/#map=12/52.5/13.4",
                at(52.5, 13.4).zoom(12.0),
            ),
            (
                "https://www.openstreetmap.org/go/0EEQjE--",
                at(51.510772705078125, 0.054931640625).zoom(9.0),
            ),
            (
                "https://osm.org/go/0GAjIv8h?node=240109189",
                at(50.671530961990356, 6.09715461730957)
                    .zoom(16.0)
                    .osm(OsmKind::Node, 240_109_189),
            ),
        ],
    );
    check(
        Provider::OsmAnd,
        &[
            (
                "https://osmand.net/map?pin=52.5186925,13.3996024",
                at(52.5186925, 13.3996024),
            ),
            (
                "https://osmand.net/map/?pin=47.37,8.54#15/47.37/8.54",
                at(47.37, 8.54).zoom(15.0),
            ),
            (
                "https://osmand.net/go?lat=52.5186925&lon=13.3996024&z=17",
                at(52.5186925, 13.3996024).zoom(17.0),
            ),
            (
                "http://download.osmand.net/go?lat=34.99393&lon=-110.12345#this+should+be+ignored",
                at(34.99393, -110.12345),
            ),
            (
                "https://osmand.net/map/?start=47.1,8.1&finish=47.37,8.54&profile=car",
                at(47.37, 8.54),
            ),
        ],
    );
}

#[test]
fn organic_maps_and_comaps() {
    check(
        Provider::OrganicMaps,
        &[
            (
                "https://omaps.app/-14.333333,-170/American_Samoa",
                at(-14.333333, -170.0).name("American Samoa"),
            ),
            (
                "om://map?v=1&ll=54.32123,12.34562&n=Point%20Name&id=AnyStringOrEncodedUrl&backurl=x&appname=y",
                at(54.32123, 12.34562).name("Point Name"),
            ),
        ],
    );
    // ge0 links land in the centre of their cell: check them near, not exactly.
    for (url, provider, lat, lon, name) in [
        (
            "https://omaps.app/B4srhdHVVt/Some+Name",
            Provider::OrganicMaps,
            64.5234,
            12.1234,
            Some("Some_Name"),
        ),
        (
            "http://ge0.me/B4srhdHVVt/Berlin",
            Provider::OrganicMaps,
            64.5234,
            12.1234,
            Some("Berlin"),
        ),
        (
            "ge0://B4srhdHVVt/Berlin",
            Provider::OrganicMaps,
            64.5234,
            12.1234,
            Some("Berlin"),
        ),
        (
            "https://comaps.at/B4srhdHVVt/Berlin",
            Provider::CoMaps,
            64.5234,
            12.1234,
            Some("Berlin"),
        ),
        ("cm://8wAAAAAAAA", Provider::CoMaps, 0.0, 0.0, None),
    ] {
        let link = parse(url).unwrap();
        let p = link.point.unwrap();
        assert_eq!(link.provider, provider, "{url}");
        assert!(
            (p.lat - lat).abs() < 1e-5 && (p.lon - lon).abs() < 1e-5,
            "{url} {p:?}"
        );
        assert_eq!(link.name.as_deref(), name, "{url}");
    }
}

#[test]
fn here() {
    check(
        Provider::Here,
        &[
            (
                "http://share.here.com/l/52.5134272,13.3778416,Hannah-Arendt-Stra%C3%9Fe?z=16.0&t=normal",
                at(52.5134272, 13.3778416)
                    .name("Hannah-Arendt-Straße")
                    .zoom(16.0),
            ),
            (
                "https://www.here.com/location?map=52.5134272,13.3778416,16,normal&msg=Hannah-Arendt-Stra%C3%9Fe",
                at(52.5134272, 13.3778416)
                    .name("Hannah-Arendt-Straße")
                    .zoom(16.0),
            ),
            (
                "https://www.here.com/?map=48.23145,16.38454,15,normal",
                at(48.23145, 16.38454).zoom(15.0),
            ),
            (
                "https://share.here.com/r/mylocation/47.37,8.54,Office?m=d",
                at(47.37, 8.54).name("Office"),
            ),
        ],
    );
}

#[test]
fn geo() {
    check(
        Provider::Geo,
        &[
            ("geo:47.37,8.54?q=Test", at(47.37, 8.54).query("Test")),
            ("geo:52.52,13.40", at(52.52, 13.4)),
            ("geo:46.0051,8.9528,20;crs=wgs84;u=5", at(46.0051, 8.9528)),
            (
                "geo:50.451300,30.569900?z=15&q=50.451300,30.569900 (Kiev)",
                at(50.4513, 30.5699).name("Kiev").zoom(15.0),
            ),
            (
                "geo:0,0?q=47.37687,8.54169(Hauptbahnhof)",
                at(47.37687, 8.54169).name("Hauptbahnhof"),
            ),
            (
                "geo:0,0?z=11&q=Lots+Of+Stuff",
                none().query("Lots Of Stuff").zoom(11.0),
            ),
            ("geo:0,0?q=86HJV99P%2B29", none().query("86HJV99P+29")),
        ],
    );
    for url in ["geo:", "geo:0,0?q=", "geo:?q=", "geo:0,0?m"] {
        if url == "geo:0,0?m" {
            // A point at 0,0 with an unknown parameter is still that point.
            assert_eq!(parse(url).unwrap().point, Point::new(0.0, 0.0));
            continue;
        }
        assert_eq!(parse(url), None, "{url}");
    }
}

#[test]
fn canonical_place_uris() {
    for (url, canonical) in [
        (
            "https://osm.org/node/240109189/",
            Some("https://www.openstreetmap.org/node/240109189"),
        ),
        (
            "https://osm.org/go/0GAjIv8h?node=240109189",
            Some("https://www.openstreetmap.org/node/240109189"),
        ),
        (
            "https://www.google.com/maps/place/Kamzik/@48.1821032,17.0941412,17z/data=!8m2!3d48.1826322!4d17.0949707",
            Some("geo:48.18263,17.09497"),
        ),
        ("geo:47.3,8.50000", Some("geo:47.3,8.5")),
        // Searches and map views name no place.
        ("geo:47.37,8.54?q=restaurants", None),
        (
            "https://www.google.com/maps/search/pizza/@47.37,8.54,15z",
            None,
        ),
        ("https://www.google.com/maps/@47.37,8.54,15z", None),
        (
            "https://maps.apple.com/search?query=pizza&center=47.37,8.54",
            None,
        ),
        ("https://www.openstreetmap.org/#map=12/52.5/13.4", None),
        (
            "https://www.here.com/?map=48.23145,16.38454,15,normal",
            None,
        ),
        (
            "https://www.openstreetmap.org/?mlat=52.5&mlon=13.4#map=12/52.5/13.4",
            Some("geo:52.5,13.4"),
        ),
        ("https://maps.app.goo.gl/AbCdEf123", None),
        ("geo:0,0?q=Bahnhofstrasse", None),
    ] {
        assert_eq!(
            canonical_place_uri(&parse(url).unwrap()).as_deref(),
            canonical,
            "{url}"
        );
    }
}
