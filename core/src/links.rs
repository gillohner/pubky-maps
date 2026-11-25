//! Map links for the app: the `map-links` reader and builders, plus the two steps that need
//! the network: following a short link (only `Location` headers, never a page) and looking the
//! place up on Pinakes (`GET /v1/resources/by-uri`).

use std::future::Future;

use map_links::{HeaderFetcher, Hop, MAX_REDIRECTS, Method, ResolveError};
use reqwest::header::LOCATION;
use serde::Deserialize;
use url::Url;

use crate::core::PubkyMapsCore;
use crate::error::{CoreError, redact};
use crate::place::OsmElement;

/// The largest Pinakes card read; cards are a few kilobytes.
const MAX_CARD_BYTES: usize = 1024 * 1024;

/// The app or format a map link belongs to.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MapProvider {
    Geo,
    Google,
    Apple,
    Waze,
    OpenStreetMap,
    OsmAnd,
    OrganicMaps,
    CoMaps,
    Here,
}

impl From<map_links::Provider> for MapProvider {
    fn from(p: map_links::Provider) -> Self {
        use map_links::Provider as P;
        match p {
            P::Geo => Self::Geo,
            P::Google => Self::Google,
            P::Apple => Self::Apple,
            P::Waze => Self::Waze,
            P::OpenStreetMap => Self::OpenStreetMap,
            P::OsmAnd => Self::OsmAnd,
            P::OrganicMaps => Self::OrganicMaps,
            P::CoMaps => Self::CoMaps,
            P::Here => Self::Here,
        }
    }
}

/// A WGS84 point.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct MapPoint {
    pub lat: f64,
    pub lon: f64,
}

impl MapPoint {
    fn checked(self) -> Result<map_links::Point, CoreError> {
        map_links::Point::new(self.lat, self.lon)
            .ok_or_else(|| CoreError::validation(format!("point out of range: {self:?}")))
    }
}

impl From<map_links::Point> for MapPoint {
    fn from(p: map_links::Point) -> Self {
        Self {
            lat: p.lat,
            lon: p.lon,
        }
    }
}

/// An OpenStreetMap element.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct OsmElementRef {
    pub element: OsmElement,
    pub id: u64,
}

impl From<map_links::OsmRef> for OsmElementRef {
    fn from(r: map_links::OsmRef) -> Self {
        Self {
            element: match r.kind {
                map_links::OsmKind::Node => OsmElement::Node,
                map_links::OsmKind::Way => OsmElement::Way,
                map_links::OsmKind::Relation => OsmElement::Relation,
            },
            id: r.id,
        }
    }
}

impl From<OsmElementRef> for map_links::OsmRef {
    fn from(r: OsmElementRef) -> Self {
        Self {
            kind: match r.element {
                OsmElement::Node => map_links::OsmKind::Node,
                OsmElement::Way => map_links::OsmKind::Way,
                OsmElement::Relation => map_links::OsmKind::Relation,
            },
            id: r.id,
        }
    }
}

/// What a map link says about a place, read from its text alone.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct MapLink {
    pub provider: MapProvider,
    pub name: Option<String>,
    /// The place's point; for a map-view link, the centre of the view.
    pub point: Option<MapPoint>,
    /// The point is only the centre of a map view, not a place.
    pub point_is_view: bool,
    pub osm: Option<OsmElementRef>,
    pub zoom: Option<f64>,
    /// Search text, for a link that searches (an address or name) instead of naming a point.
    pub query: Option<String>,
    /// The provider's own place id (Google CID, Apple `auid`/`place-id`, HERE id).
    pub provider_id: Option<String>,
    /// A short link: the place is behind its redirect (`resolveMapLink`).
    pub needs_redirect: bool,
    /// The link as given (trimmed).
    pub original: String,
}

impl From<map_links::MapLink> for MapLink {
    fn from(l: map_links::MapLink) -> Self {
        Self {
            provider: l.provider.into(),
            name: l.name,
            point: l.point.map(Into::into),
            point_is_view: l.point_is_view,
            osm: l.osm.map(Into::into),
            zoom: l.zoom,
            query: l.query,
            provider_id: l.provider_id,
            needs_redirect: l.needs_redirect,
            original: l.original,
        }
    }
}

/// An app to hand a place to.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MapApp {
    /// `https://www.google.com/maps/…?api=1` (the app when installed, else the browser).
    GoogleMaps,
    /// `google.navigation:` (directions only; turn-by-turn in the Google Maps app).
    GoogleNavigation,
    /// `https://maps.apple.com/place|directions`.
    AppleMaps,
    /// `https://waze.com/ul`.
    Waze,
    /// `https://osmand.net/map?pin=`.
    OsmAnd,
    /// `om://map` (the installed app).
    OrganicMaps,
    /// `https://omaps.app/<ge0>/<name>`.
    OrganicMapsWeb,
    /// `cm://map` (the installed app).
    CoMaps,
    /// `https://comaps.at/<ge0>/<name>`.
    CoMapsWeb,
    /// `https://share.here.com/…`.
    HereWeGo,
    /// `https://www.openstreetmap.org/…`.
    OpenStreetMap,
    /// `geo:` for any map app.
    Geo,
}

impl From<MapApp> for map_links::App {
    fn from(a: MapApp) -> Self {
        use map_links::App as A;
        match a {
            MapApp::GoogleMaps => A::GoogleMaps,
            MapApp::GoogleNavigation => A::GoogleNavigation,
            MapApp::AppleMaps => A::AppleMaps,
            MapApp::Waze => A::Waze,
            MapApp::OsmAnd => A::OsmAnd,
            MapApp::OrganicMaps => A::OrganicMaps,
            MapApp::OrganicMapsWeb => A::OrganicMapsWeb,
            MapApp::CoMaps => A::CoMaps,
            MapApp::CoMapsWeb => A::CoMapsWeb,
            MapApp::HereWeGo => A::HereWeGo,
            MapApp::OpenStreetMap => A::OpenStreetMap,
            MapApp::Geo => A::Geo,
        }
    }
}

/// How to travel for directions.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TravelMode {
    Driving,
    Walking,
    Cycling,
    Transit,
}

impl From<TravelMode> for map_links::TravelMode {
    fn from(m: TravelMode) -> Self {
        match m {
            TravelMode::Driving => Self::Driving,
            TravelMode::Walking => Self::Walking,
            TravelMode::Cycling => Self::Cycling,
            TravelMode::Transit => Self::Transit,
        }
    }
}

/// A place to build a link for.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LinkPlace {
    pub point: MapPoint,
    pub name: Option<String>,
    pub osm: Option<OsmElementRef>,
    pub zoom: Option<f64>,
}

impl LinkPlace {
    fn checked(self) -> Result<map_links::Place, CoreError> {
        if let Some(zoom) = self.zoom
            && !map_links::ZOOM_RANGE.contains(&zoom)
        {
            return Err(CoreError::validation(format!("zoom out of range: {zoom}")));
        }
        Ok(map_links::Place {
            point: self.point.checked()?,
            name: self.name,
            osm: self.osm.map(Into::into),
            zoom: self.zoom,
        })
    }
}

/// Reads a map link (Google, Apple, Waze, OpenStreetMap, OsmAnd, Organic Maps, CoMaps, HERE,
/// `geo:`) from its text. `None` for anything else. Never touches the network.
#[uniffi::export]
pub fn parse_map_link(url: String) -> Option<MapLink> {
    map_links::parse(&url).map(Into::into)
}

/// The first map link in shared text ("Name\nhttps://maps.app.goo.gl/…"); a link without a
/// name takes the first line above it as its name.
#[uniffi::export]
pub fn find_map_link(text: String) -> Option<MapLink> {
    map_links::find_in_text(&text).map(Into::into)
}

/// The link that shows `place` in `app`; `None` when the app has no such link.
#[uniffi::export]
pub fn build_place_link(app: MapApp, place: LinkPlace) -> Result<Option<String>, CoreError> {
    Ok(map_links::build(
        app.into(),
        &map_links::Target::Place(place.checked()?),
    ))
}

/// The link that starts directions to `destination` in `app`; `None` when the app has no
/// such link (Organic Maps, CoMaps, OsmAnd and `geo:` have none; Waze drives only).
#[uniffi::export]
pub fn build_directions_link(
    app: MapApp,
    destination: LinkPlace,
    mode: TravelMode,
) -> Result<Option<String>, CoreError> {
    Ok(map_links::build(
        app.into(),
        &map_links::Target::Directions {
            destination: destination.checked()?,
            mode: mode.into(),
        },
    ))
}

/// The canonical place URI of a link (`map_links::canonical_place_uri`): its OpenStreetMap
/// element URL, else `geo:lat,lon` with at most 5 fraction digits; `None` for a search, a map
/// view or an unfollowed short link.
#[uniffi::export]
pub fn map_link_place_uri(link: MapLink) -> Option<String> {
    map_links::canonical_place_uri(&link.into_crate()?)
}

impl MapLink {
    /// The crate's link; `None` when the point is out of range (never for a link the crate read).
    fn into_crate(self) -> Option<map_links::MapLink> {
        let point = match self.point {
            Some(p) => Some(map_links::Point::new(p.lat, p.lon)?),
            None => None,
        };
        Some(map_links::MapLink {
            provider: match self.provider {
                MapProvider::Geo => map_links::Provider::Geo,
                MapProvider::Google => map_links::Provider::Google,
                MapProvider::Apple => map_links::Provider::Apple,
                MapProvider::Waze => map_links::Provider::Waze,
                MapProvider::OpenStreetMap => map_links::Provider::OpenStreetMap,
                MapProvider::OsmAnd => map_links::Provider::OsmAnd,
                MapProvider::OrganicMaps => map_links::Provider::OrganicMaps,
                MapProvider::CoMaps => map_links::Provider::CoMaps,
                MapProvider::Here => map_links::Provider::Here,
            },
            name: self.name,
            point,
            point_is_view: self.point_is_view,
            osm: self.osm.map(Into::into),
            zoom: self.zoom,
            query: self.query,
            provider_id: self.provider_id,
            needs_redirect: self.needs_redirect,
            original: self.original,
        })
    }
}

/// A place opened from a link, ready for the map.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LinkedPlace {
    /// The link as the user had it. Tags and posts go on this URI; it is never rewritten.
    pub source_uri: String,
    pub provider: MapProvider,
    pub name: Option<String>,
    pub point: Option<MapPoint>,
    /// The point is only the centre of a map view (or of a search), not a place: move the
    /// camera there, do not pin it.
    pub point_is_view: bool,
    pub osm: Option<OsmElementRef>,
    pub zoom: Option<f64>,
    /// Search text, when the link searches instead of naming a point.
    pub query: Option<String>,
    /// The Pinakes card's URI when Pinakes knows the place.
    pub card_uri: Option<String>,
    /// True when name and point come from a Pinakes card, false when from the link text.
    pub from_pinakes: bool,
    /// A short link that could not be followed for a network failure (it may still hold a
    /// place).
    pub short_link_unfollowed: bool,
    /// Pinakes servers were asked and none answered (every one failed or timed out).
    pub pinakes_unreachable: bool,
}

impl LinkedPlace {
    fn from_link(link: &map_links::MapLink) -> Self {
        Self {
            source_uri: link.original.clone(),
            provider: link.provider.into(),
            name: link.name.clone(),
            point: link.point.map(Into::into),
            point_is_view: link.point_is_view,
            osm: link.osm.map(Into::into),
            zoom: link.zoom,
            query: link.query.clone(),
            card_uri: None,
            from_pinakes: false,
            short_link_unfollowed: false,
            pinakes_unreachable: false,
        }
    }
}

/// The place the map link in `text` names, read from the text alone (no network, no core
/// instance), to show at once while `openMapLink` follows a short link and asks Pinakes, or
/// when the core cannot be built. `None` when the text holds no map link.
#[uniffi::export]
pub fn place_from_text(text: String) -> Option<LinkedPlace> {
    map_links::find_in_text(&text).map(|link| LinkedPlace::from_link(&link))
}

/// A Pinakes card, as far as the map needs it.
#[derive(Debug, Deserialize)]
struct Card {
    uri: String,
    name: Option<String>,
    point: Option<CardPoint>,
}

#[derive(Debug, Deserialize)]
struct CardPoint {
    lat: f64,
    lon: f64,
}

/// One hop of a short link through the core's link client.
struct ReqwestFetcher(reqwest::Client);

impl HeaderFetcher for ReqwestFetcher {
    fn fetch(&self, url: &str, method: Method) -> impl Future<Output = Result<Hop, String>> + Send {
        let request = match method {
            Method::Head => self.0.head(url),
            Method::Get => self.0.get(url),
        };
        async move {
            let response = request.send().await.map_err(|e| redact(&e.to_string()))?;
            let location = response
                .headers()
                .get(LOCATION)
                .and_then(|v| v.to_str().ok())
                .map(str::to_string);
            // The body is never read: dropping the response closes it.
            Ok(Hop {
                status: response.status().as_u16(),
                location,
            })
        }
    }
}

async fn resolve(client: reqwest::Client, url: &str) -> Result<String, CoreError> {
    map_links::resolve_redirect(&ReqwestFetcher(client), url, MAX_REDIRECTS)
        .await
        .map_err(|e| match e {
            ResolveError::NotShortLink => {
                CoreError::validation(format!("not a short map link: {url}"))
            }
            ResolveError::Network(reason) => CoreError::Network { reason },
            ResolveError::NoRedirect { status: 404 | 410 } => CoreError::NotFound {
                reason: "the short link does not exist".into(),
            },
            other => CoreError::Server {
                status: 0,
                reason: other.to_string(),
            },
        })
}

/// What a Pinakes server list said about a URI.
enum Lookup {
    Found(Card),
    Unknown,
    Unreachable,
}

/// `GET <server>/v1/resources/by-uri?uri=<uri>` on each server in order until one answers.
/// A 404 is an answer (the link is not known there); a failure or a 5xx moves to the next.
async fn lookup(client: &reqwest::Client, servers: &[Url], uri: &str) -> Lookup {
    for server in servers {
        let Ok(mut url) = server.join("v1/resources/by-uri") else {
            continue;
        };
        url.query_pairs_mut().append_pair("uri", uri);
        let Ok(response) = client.get(url).send().await else {
            continue;
        };
        match response.status().as_u16() {
            200 => {
                if let Some(card) = read_card(response).await {
                    return Lookup::Found(card);
                }
            }
            404 | 410 => return Lookup::Unknown,
            _ => {}
        }
    }
    Lookup::Unreachable
}

async fn read_card(mut response: reqwest::Response) -> Option<Card> {
    let mut body = Vec::new();
    while let Some(chunk) = response.chunk().await.ok()? {
        if body.len() + chunk.len() > MAX_CARD_BYTES {
            return None;
        }
        body.extend_from_slice(&chunk);
    }
    serde_json::from_slice(&body).ok()
}

/// Server base URLs with a trailing slash, so `join` keeps their path.
fn server_urls(servers: &[String]) -> Result<Vec<Url>, CoreError> {
    servers
        .iter()
        .map(|s| {
            let mut s = s.trim().to_string();
            if !s.ends_with('/') {
                s.push('/');
            }
            let url = Url::parse(&s)
                .map_err(|e| CoreError::validation(format!("Pinakes server: {e}: {s}")))?;
            if !matches!(url.scheme(), "http" | "https") {
                return Err(CoreError::validation(format!(
                    "Pinakes server must be http(s): {s}"
                )));
            }
            Ok(url)
        })
        .collect()
}

async fn open(
    client: reqwest::Client,
    text: String,
    pinakes: Vec<Url>,
) -> Result<LinkedPlace, CoreError> {
    let mut link = map_links::find_in_text(&text)
        .ok_or_else(|| CoreError::validation("no map link in the text"))?;
    let mut short_link_unfollowed = false;
    if link.needs_redirect {
        match resolve(client.clone(), &link.original).await {
            Ok(resolved) => {
                if let Some(long) = map_links::parse(&resolved) {
                    let text_name = link.name.take();
                    link = map_links::MapLink {
                        name: long.name.or(text_name),
                        needs_redirect: false,
                        original: link.original,
                        ..long
                    };
                }
            }
            Err(CoreError::Network { .. }) => short_link_unfollowed = true,
            // A dead or odd short link: keep what the text said.
            Err(_) => {}
        }
    }
    let mut place = LinkedPlace {
        short_link_unfollowed,
        ..LinkedPlace::from_link(&link)
    };
    if pinakes.is_empty() {
        return Ok(place);
    }
    // The link as pasted first (Pinakes keeps the links people referenced), then the OSM
    // element it names.
    let mut uris = vec![link.original.clone()];
    if let Some(osm) = link.osm.map(|osm| osm.url())
        && osm != link.original
    {
        uris.push(osm);
    }
    for uri in uris {
        match lookup(&client, &pinakes, &uri).await {
            Lookup::Found(card) => {
                place.from_pinakes = true;
                if let Some(name) = card.name.as_deref().and_then(map_links::clean_text) {
                    place.name = Some(name);
                }
                if let Some(point) = card.point.and_then(|p| map_links::Point::new(p.lat, p.lon)) {
                    // A card is a place: its point is pinned, whatever the link showed.
                    place.point = Some(point.into());
                    place.point_is_view = false;
                    place.query = None;
                }
                if place.osm.is_none() {
                    place.osm = map_links::parse(&card.uri)
                        .and_then(|l| l.osm)
                        .map(Into::into);
                }
                place.card_uri = Some(card.uri);
                return Ok(place);
            }
            Lookup::Unknown => {}
            Lookup::Unreachable => {
                place.pinakes_unreachable = true;
                return Ok(place);
            }
        }
    }
    Ok(place)
}

#[uniffi::export]
impl PubkyMapsCore {
    /// Follows a short map link (`maps.app.goo.gl`, `goo.gl/maps`, `maps.apple/p/`) and
    /// returns where it leads, for `parseMapLink`. Only `Location` headers are read (HEAD, or
    /// GET without reading the body when HEAD is refused), over HTTPS, at most 4 hops, to
    /// short-link hosts only. `NotFound` for a dead link, `Network` when offline.
    pub async fn resolve_map_link(&self, url: String) -> Result<String, CoreError> {
        let client = self.links.clone();
        self.run(async move { resolve(client, &url).await }).await
    }

    /// Opens the map link in `text` (a pasted link, or shared text holding one): follows a
    /// short link, then asks the Pinakes servers in `pinakes_servers` (in order, the next one
    /// when a server fails) for the link as pasted and, for an OpenStreetMap link, for its
    /// element. When Pinakes does not know it or nothing answers, name and point come from
    /// the link text. `Validation` when the text holds no map link.
    pub async fn open_map_link(
        &self,
        text: String,
        pinakes_servers: Vec<String>,
    ) -> Result<LinkedPlace, CoreError> {
        let client = self.links.clone();
        let servers = server_urls(&pinakes_servers)?;
        self.run(async move { open(client, text, servers).await })
            .await
    }
}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;
    use std::sync::{Arc, Mutex};
    use std::time::Duration;

    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    use super::*;
    use crate::config::link_client;

    /// A tiny HTTP server: answers each `METHOD path` from a table (else 404), records what it
    /// was asked. Returns its base URL.
    async fn server(answers: &[(&str, &'static str)]) -> (String, Arc<Mutex<Vec<String>>>) {
        let answers: HashMap<String, &'static str> = answers
            .iter()
            .map(|(k, v)| ((*k).to_string(), *v))
            .collect();
        let asked = Arc::new(Mutex::new(Vec::new()));
        let log = asked.clone();
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        tokio::spawn(async move {
            while let Ok((mut socket, _)) = listener.accept().await {
                let answers = answers.clone();
                let log = log.clone();
                tokio::spawn(async move {
                    let mut buf = vec![0_u8; 8192];
                    let n = socket.read(&mut buf).await.unwrap_or(0);
                    let request = String::from_utf8_lossy(&buf[..n]).to_string();
                    let line = request.lines().next().unwrap_or_default().to_string();
                    let key = line.rsplit_once(' ').map_or("", |(key, _)| key).to_string();
                    log.lock().unwrap().push(key.clone());
                    let answer = answers.get(&key).copied().unwrap_or(
                        "HTTP/1.1 404 Not Found\r\ncontent-length: 26\r\n\r\n{\"error\":\"link not known\"}",
                    );
                    let _ = socket.write_all(answer.as_bytes()).await;
                    let _ = socket.shutdown().await;
                });
            }
        });
        (format!("http://{address}"), asked)
    }

    fn client() -> reqwest::Client {
        link_client(Duration::from_secs(5)).unwrap()
    }

    fn by_uri(uri: &str) -> String {
        let mut url = Url::parse("http://x/v1/resources/by-uri").unwrap();
        url.query_pairs_mut().append_pair("uri", uri);
        format!("GET /v1/resources/by-uri?{}", url.query().unwrap())
    }

    #[test]
    fn free_functions() {
        let link = parse_map_link("geo:0,0?q=47.37,8.54(Test)".into()).unwrap();
        assert_eq!(link.provider, MapProvider::Geo);
        assert_eq!(link.name.as_deref(), Some("Test"));
        assert_eq!(map_link_place_uri(link).as_deref(), Some("geo:47.37,8.54"));
        let link = find_map_link("Zürich HB\nhttps://maps.app.goo.gl/abc".into()).unwrap();
        assert!(link.needs_redirect);
        assert_eq!(link.name.as_deref(), Some("Zürich HB"));
        assert_eq!(map_link_place_uri(link), None);
        let osm = parse_map_link("https://osm.org/way/4".into()).unwrap();
        assert_eq!(
            osm.osm,
            Some(OsmElementRef {
                element: OsmElement::Way,
                id: 4
            })
        );
        assert_eq!(
            map_link_place_uri(osm).as_deref(),
            Some("https://www.openstreetmap.org/way/4")
        );

        let place = LinkPlace {
            point: MapPoint {
                lat: 47.37,
                lon: 8.54,
            },
            name: Some("Test".into()),
            osm: None,
            zoom: None,
        };
        assert_eq!(
            build_place_link(MapApp::Geo, place.clone())
                .unwrap()
                .as_deref(),
            Some("geo:47.37,8.54?z=16&q=47.37,8.54(Test)")
        );
        assert_eq!(
            build_directions_link(MapApp::Waze, place.clone(), TravelMode::Driving)
                .unwrap()
                .as_deref(),
            Some("https://waze.com/ul?ll=47.37,8.54&navigate=yes")
        );
        assert_eq!(
            build_directions_link(MapApp::OrganicMaps, place.clone(), TravelMode::Driving).unwrap(),
            None
        );
        let bad = LinkPlace {
            point: MapPoint {
                lat: 91.0,
                lon: 0.0,
            },
            ..place
        };
        assert!(matches!(
            build_place_link(MapApp::Geo, bad.clone()),
            Err(CoreError::Validation { .. })
        ));
        let nan_zoom = LinkPlace {
            point: MapPoint { lat: 1.0, lon: 2.0 },
            zoom: Some(f64::NAN),
            ..bad
        };
        assert!(matches!(
            build_place_link(MapApp::OrganicMapsWeb, nan_zoom),
            Err(CoreError::Validation { .. })
        ));
        // A search near a point names no place.
        let search = parse_map_link("geo:47.37,8.54?q=restaurants".into()).unwrap();
        assert_eq!(map_link_place_uri(search), None);
    }

    #[tokio::test]
    async fn a_hop_reads_only_the_location_header() {
        let (base, asked) = server(&[(
            "HEAD /short",
            "HTTP/1.1 302 Found\r\nlocation: https://www.google.com/maps/@1,2,3z\r\ncontent-length: 0\r\n\r\n",
        )])
        .await;
        let hop = ReqwestFetcher(client())
            .fetch(&format!("{base}/short"), Method::Head)
            .await
            .unwrap();
        assert_eq!(hop.status, 302);
        assert_eq!(
            hop.location.as_deref(),
            Some("https://www.google.com/maps/@1,2,3z")
        );
        // Not followed: only the one request was made.
        assert_eq!(asked.lock().unwrap().as_slice(), ["HEAD /short"]);
    }

    #[tokio::test]
    async fn resolving_needs_a_short_link() {
        let err = resolve(client(), "https://example.com/x")
            .await
            .unwrap_err();
        assert!(matches!(err, CoreError::Validation { .. }), "{err:?}");
    }

    const CARD: &str = "HTTP/1.1 200 OK\r\ncontent-type: application/json\r\ncontent-length: 102\r\n\r\n{\"uri\":\"https://www.openstreetmap.org/node/240109189\",\"name\":\"Berlin\",\"point\":{\"lat\":52.5,\"lon\":13.4}}";

    #[tokio::test]
    async fn pinakes_card_by_the_pasted_link() {
        let link = "https://osmand.net/map?pin=52.51,13.39";
        let (base, _) = server(&[(&by_uri(link), CARD)]).await;
        let place = open(client(), link.into(), server_urls(&[base]).unwrap())
            .await
            .unwrap();
        assert!(place.from_pinakes);
        assert!(!place.pinakes_unreachable && !place.short_link_unfollowed);
        assert_eq!(place.source_uri, link);
        assert_eq!(place.name.as_deref(), Some("Berlin"));
        assert_eq!(
            place.point,
            Some(MapPoint {
                lat: 52.5,
                lon: 13.4
            })
        );
        assert_eq!(place.osm.map(|o| o.id), Some(240_109_189));
        assert_eq!(
            place.card_uri.as_deref(),
            Some("https://www.openstreetmap.org/node/240109189")
        );
    }

    #[tokio::test]
    async fn pinakes_card_by_the_osm_element() {
        // The link as pasted is unknown (404); its element is known.
        let pasted = "https://osm.org/node/240109189#map=15/52.51/13.39";
        let (base, asked) = server(&[(
            &by_uri("https://www.openstreetmap.org/node/240109189"),
            CARD,
        )])
        .await;
        let place = open(client(), pasted.into(), server_urls(&[base]).unwrap())
            .await
            .unwrap();
        assert!(place.from_pinakes);
        assert_eq!(place.source_uri, pasted, "the link the user had is kept");
        assert_eq!(place.name.as_deref(), Some("Berlin"));
        assert_eq!(asked.lock().unwrap().len(), 2);
    }

    #[tokio::test]
    async fn unknown_to_pinakes_uses_the_link_text() {
        let (base, _) = server(&[]).await;
        let place = open(
            client(),
            "geo:47.37,8.54?q=47.37,8.54(Test)".into(),
            server_urls(&[base]).unwrap(),
        )
        .await
        .unwrap();
        assert!(!place.from_pinakes);
        assert!(!place.pinakes_unreachable && !place.short_link_unfollowed);
        assert_eq!(place.name.as_deref(), Some("Test"));
        assert_eq!(
            place.point,
            Some(MapPoint {
                lat: 47.37,
                lon: 8.54
            })
        );
    }

    #[tokio::test]
    async fn a_failing_server_hands_over_to_the_next() {
        let link = "https://osmand.net/map?pin=52.51,13.39";
        let (broken, _) = server(&[(
            &by_uri(link),
            "HTTP/1.1 503 Service Unavailable\r\ncontent-length: 0\r\n\r\n",
        )])
        .await;
        let (good, _) = server(&[(&by_uri(link), CARD)]).await;
        let servers = server_urls(&["http://127.0.0.1:9".into(), broken, good]).unwrap();
        let place = open(client(), link.into(), servers).await.unwrap();
        assert!(place.from_pinakes);
    }

    #[tokio::test]
    async fn offline_falls_back_to_the_link_text() {
        let servers = server_urls(&["http://127.0.0.1:9".into()]).unwrap();
        let place = open(client(), "geo:47.37,8.54".into(), servers)
            .await
            .unwrap();
        assert!(place.pinakes_unreachable);
        assert!(!place.short_link_unfollowed);
        assert!(!place.from_pinakes);
        assert_eq!(
            place.point,
            Some(MapPoint {
                lat: 47.37,
                lon: 8.54
            })
        );
    }

    /// A host that accepts connections and never answers (dropped packets, a stalled proxy).
    async fn black_hole() -> String {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        tokio::spawn(async move {
            let mut held = Vec::new();
            while let Ok((socket, _)) = listener.accept().await {
                held.push(socket);
            }
        });
        format!("http://{address}")
    }

    #[tokio::test]
    async fn a_silent_pinakes_costs_its_deadline_then_the_next_server_answers() {
        let link = "https://osmand.net/map?pin=52.51,13.39";
        let silent = black_hole().await;
        let (good, _) = server(&[(&by_uri(link), CARD)]).await;
        let quick = link_client(Duration::from_millis(400)).unwrap();
        let started = std::time::Instant::now();
        let place = open(
            quick.clone(),
            link.into(),
            server_urls(&[silent.clone(), good]).unwrap(),
        )
        .await
        .unwrap();
        assert!(place.from_pinakes);
        assert!(
            started.elapsed() < Duration::from_secs(3),
            "{:?}",
            started.elapsed()
        );

        // Only silent servers: the link text, marked as Pinakes unreachable, after the deadline.
        let started = std::time::Instant::now();
        let place = open(
            quick,
            "geo:47.37,8.54".into(),
            server_urls(&[silent]).unwrap(),
        )
        .await
        .unwrap();
        assert!(place.pinakes_unreachable);
        assert_eq!(
            place.point,
            Some(MapPoint {
                lat: 47.37,
                lon: 8.54
            })
        );
        assert!(
            started.elapsed() < Duration::from_secs(3),
            "{:?}",
            started.elapsed()
        );
    }

    #[test]
    fn place_from_text_needs_no_network() {
        let place = place_from_text("Zürich HB\nhttps://maps.app.goo.gl/abc".into()).unwrap();
        assert_eq!(place.source_uri, "https://maps.app.goo.gl/abc");
        assert_eq!(place.name.as_deref(), Some("Zürich HB"));
        assert_eq!(place.point, None);
        assert!(!place.from_pinakes && !place.pinakes_unreachable && !place.short_link_unfollowed);
        let view = place_from_text("https://www.google.com/maps/@47.37,8.54,15z".into()).unwrap();
        assert!(view.point_is_view);
        let search = place_from_text("geo:47.37,8.54?q=restaurants".into()).unwrap();
        assert_eq!(search.query.as_deref(), Some("restaurants"));
        assert!(place_from_text("hello".into()).is_none());
    }

    #[tokio::test]
    async fn a_card_turns_a_view_into_a_place_and_the_osm_url_is_asked_once() {
        let pasted = "https://www.openstreetmap.org/node/240109189";
        let (base, asked) = server(&[]).await;
        let place = open(client(), pasted.into(), server_urls(&[base]).unwrap())
            .await
            .unwrap();
        assert!(!place.from_pinakes);
        // The pasted link is the canonical element URL: one request, not two.
        assert_eq!(asked.lock().unwrap().len(), 1);

        let view = "https://www.google.com/maps/@52.5,13.4,15z";
        let (base, _) = server(&[(&by_uri(view), CARD)]).await;
        let place = open(client(), view.into(), server_urls(&[base]).unwrap())
            .await
            .unwrap();
        assert!(place.from_pinakes);
        assert!(!place.point_is_view);
        assert_eq!(place.source_uri, view);
    }

    #[test]
    fn the_place_uri_is_the_crates_rule() {
        for link in [
            "https://osm.org/way/4#map=17/47.1/8.1",
            "geo:47.3768712,8.5416949",
            "geo:47.37,8.54?q=restaurants",
            "https://www.google.com/maps/@47.37,8.54,15z",
            "https://maps.app.goo.gl/abc",
        ] {
            assert_eq!(
                map_link_place_uri(parse_map_link(link.into()).unwrap()),
                map_links::canonical_place_uri(&map_links::parse(link).unwrap()),
                "{link}"
            );
        }
    }

    #[tokio::test]
    async fn no_map_link_is_refused() {
        let err = open(client(), "hello there".into(), Vec::new())
            .await
            .unwrap_err();
        assert!(matches!(err, CoreError::Validation { .. }));
        assert!(server_urls(&["ftp://x".into()]).is_err());
    }
}
