//! Following short map links (`maps.app.goo.gl`, `goo.gl/maps`, `maps.apple/p/`).
//!
//! Only the `Location` header of each hop is read; no page body is ever fetched or parsed.
//! The HTTP itself is the caller's ([`HeaderFetcher`]), so an app and a server can each use
//! their own client and network rules.

use std::future::Future;

use url::Url;

use crate::parse;

/// The most hops [`resolve_redirect`] follows. Google and Apple short links need one.
pub const MAX_REDIRECTS: u8 = 4;

/// The request a hop makes.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Method {
    /// Tried first.
    Head,
    /// Only when the server refuses HEAD (405 or 501). The caller must not read the body.
    Get,
}

/// What a hop answered.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Hop {
    pub status: u16,
    pub location: Option<String>,
}

/// Makes one request without following redirects and returns its status and `Location`
/// header. Implementations must not follow redirects, must not read the body, and should apply
/// a timeout.
pub trait HeaderFetcher {
    fn fetch(&self, url: &str, method: Method) -> impl Future<Output = Result<Hop, String>> + Send;
}

/// Why a short link could not be followed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ResolveError {
    /// The URL is not a short map link; nothing was fetched.
    NotShortLink,
    /// The request failed (no connection, timeout, TLS).
    Network(String),
    /// The server answered without a redirect: 404 for a dead or mistyped link.
    NoRedirect { status: u16 },
    /// The `Location` header was missing or not a URL.
    BadLocation,
    /// More than [`MAX_REDIRECTS`] hops, or a loop.
    TooManyRedirects,
}

impl std::fmt::Display for ResolveError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::NotShortLink => write!(f, "not a short map link"),
            Self::Network(reason) => write!(f, "network error: {reason}"),
            Self::NoRedirect { status } => write!(f, "the link did not redirect (HTTP {status})"),
            Self::BadLocation => write!(f, "the link redirected to no usable address"),
            Self::TooManyRedirects => write!(f, "the link redirected too often"),
        }
    }
}

impl std::error::Error for ResolveError {}

/// True for a short map link: one whose place is only behind a redirect.
pub fn is_short_link(url: &str) -> bool {
    parse(url).is_some_and(|link| link.needs_redirect)
}

/// Follows a short map link and returns the first URL that is not a short link any more
/// (usually after one hop), for [`parse`]. Every request goes over HTTPS, to short-link hosts
/// only; the result itself is never fetched. Google's cookie-consent interstitial is unwrapped
/// from its `continue` parameter without fetching it.
pub async fn resolve_redirect<F: HeaderFetcher + Sync>(
    fetcher: &F,
    url: &str,
    max_hops: u8,
) -> Result<String, ResolveError> {
    if !is_short_link(url) {
        return Err(ResolveError::NotShortLink);
    }
    let mut current = https(url.trim()).ok_or(ResolveError::NotShortLink)?;
    let mut seen = vec![current.to_string()];
    for _ in 0..max_hops.min(MAX_REDIRECTS) {
        let mut hop = fetcher
            .fetch(current.as_str(), Method::Head)
            .await
            .map_err(ResolveError::Network)?;
        if matches!(hop.status, 405 | 501) {
            hop = fetcher
                .fetch(current.as_str(), Method::Get)
                .await
                .map_err(ResolveError::Network)?;
        }
        if !matches!(hop.status, 301 | 302 | 303 | 307 | 308) {
            return Err(ResolveError::NoRedirect { status: hop.status });
        }
        let location = hop.location.ok_or(ResolveError::BadLocation)?;
        let next = current
            .join(location.trim())
            .map_err(|_| ResolveError::BadLocation)?;
        let next = unwrap_consent(next);
        if !matches!(next.scheme(), "http" | "https") {
            return Err(ResolveError::BadLocation);
        }
        if !is_short_link(next.as_str()) {
            return Ok(next.to_string());
        }
        current = https(next.as_str()).ok_or(ResolveError::BadLocation)?;
        if seen.contains(&current.to_string()) {
            return Err(ResolveError::TooManyRedirects);
        }
        seen.push(current.to_string());
    }
    Err(ResolveError::TooManyRedirects)
}

/// The URL with `https`, the only scheme requests are made with.
fn https(url: &str) -> Option<Url> {
    let mut parsed = Url::parse(url).ok()?;
    match parsed.scheme() {
        "https" => {}
        "http" => parsed.set_scheme("https").ok()?,
        _ => return None,
    }
    Some(parsed)
}

/// `consent.google.com/…?continue=<url>` → `<url>`.
fn unwrap_consent(url: Url) -> Url {
    if url.host_str() == Some("consent.google.com")
        && let Some(inner) = url
            .query_pairs()
            .find(|(k, _)| k == "continue")
            .and_then(|(_, v)| Url::parse(&v).ok())
    {
        return inner;
    }
    url
}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;
    use std::pin::pin;
    use std::sync::Mutex;
    use std::task::{Context, Poll, Waker};

    use super::*;
    use crate::Point;

    /// A fetcher answering from a table, recording every request.
    struct Table {
        answers: HashMap<(String, Method), Hop>,
        asked: Mutex<Vec<(String, Method)>>,
    }

    impl Table {
        fn new(answers: &[(&str, Method, u16, Option<&str>)]) -> Self {
            Self {
                answers: answers
                    .iter()
                    .map(|(url, method, status, location)| {
                        (
                            (url.to_string(), *method),
                            Hop {
                                status: *status,
                                location: location.map(str::to_string),
                            },
                        )
                    })
                    .collect(),
                asked: Mutex::new(Vec::new()),
            }
        }
    }

    impl HeaderFetcher for Table {
        fn fetch(
            &self,
            url: &str,
            method: Method,
        ) -> impl Future<Output = Result<Hop, String>> + Send {
            self.asked.lock().unwrap().push((url.to_string(), method));
            let answer = self
                .answers
                .get(&(url.to_string(), method))
                .cloned()
                .ok_or_else(|| "connection refused".to_string());
            async move { answer }
        }
    }

    /// The futures here never wait, so one poll finishes them.
    fn run<T>(future: impl Future<Output = T>) -> T {
        let mut future = pin!(future);
        match future
            .as_mut()
            .poll(&mut Context::from_waker(Waker::noop()))
        {
            Poll::Ready(value) => value,
            Poll::Pending => panic!("the fetcher never waits"),
        }
    }

    const PLACE: &str = "https://www.google.com/maps/place/Zürich+HB/@47.3721439,8.5351212,17z/data=!3m1!4b1!4m6!3m5!1s0x47900a08cc0e6e41:0xf32e3e8f0a8a1a3c!8m2!3d47.3778579!4d8.5403226";

    #[test]
    fn one_hop_to_a_readable_place() {
        let short = "https://maps.app.goo.gl/AbCdEf123";
        let table = Table::new(&[(short, Method::Head, 302, Some(PLACE))]);
        let resolved = run(resolve_redirect(&table, short, MAX_REDIRECTS)).unwrap();
        let link = parse(&resolved).unwrap();
        assert_eq!(link.point, Point::new(47.377_857_9, 8.540_322_6));
        assert_eq!(link.name.as_deref(), Some("Zürich HB"));
        assert_eq!(table.asked.lock().unwrap().len(), 1);
    }

    #[test]
    fn legacy_goo_gl_over_https_with_an_http_location() {
        // Recorded 2026-11-25: goo.gl/maps/Cji0V answers 302 to an http maps.google.com URL.
        let table = Table::new(&[(
            "https://goo.gl/maps/Cji0V",
            Method::Head,
            302,
            Some("http://maps.google.com/?q=28.118860,98.008069&hl=en&gl=us"),
        )]);
        let resolved = run(resolve_redirect(&table, "http://goo.gl/maps/Cji0V", 4)).unwrap();
        assert_eq!(
            parse(&resolved).unwrap().point,
            Point::new(28.118_86, 98.008_069)
        );
    }

    #[test]
    fn apple_short_link() {
        let table = Table::new(&[(
            "https://maps.apple/p/AbC.dEf",
            Method::Head,
            301,
            Some("https://maps.apple.com/place?address=Main%20St&coordinate=35.307955,-97.537610"),
        )]);
        let resolved = run(resolve_redirect(&table, "https://maps.apple/p/AbC.dEf", 4)).unwrap();
        assert_eq!(
            parse(&resolved).unwrap().point,
            Point::new(35.307_955, -97.537_61)
        );
    }

    #[test]
    fn get_without_body_when_head_is_refused() {
        let short = "https://maps.app.goo.gl/x1";
        let table = Table::new(&[
            (short, Method::Head, 405, None),
            (short, Method::Get, 302, Some(PLACE)),
        ]);
        assert!(run(resolve_redirect(&table, short, 4)).is_ok());
    }

    #[test]
    fn relative_locations_chains_and_consent() {
        let table = Table::new(&[
            ("https://maps.app.goo.gl/a", Method::Head, 301, Some("/b")),
            (
                "https://maps.app.goo.gl/b",
                Method::Head,
                302,
                Some(&format!(
                    "https://consent.google.com/m?continue={}&gl=CH",
                    url::form_urlencoded::byte_serialize(PLACE.as_bytes()).collect::<String>()
                )),
            ),
        ]);
        let resolved = run(resolve_redirect(&table, "https://maps.app.goo.gl/a", 4)).unwrap();
        assert!(
            resolved.starts_with("https://www.google.com/maps/place/"),
            "{resolved}"
        );
    }

    #[test]
    fn failures() {
        let dead = Table::new(&[("https://maps.app.goo.gl/dead", Method::Head, 404, None)]);
        assert_eq!(
            run(resolve_redirect(&dead, "https://maps.app.goo.gl/dead", 4)),
            Err(ResolveError::NoRedirect { status: 404 })
        );
        let offline = Table::new(&[]);
        assert!(matches!(
            run(resolve_redirect(&offline, "https://maps.app.goo.gl/x", 4)),
            Err(ResolveError::Network(_))
        ));
        let looping = Table::new(&[
            (
                "https://maps.app.goo.gl/a",
                Method::Head,
                302,
                Some("https://maps.app.goo.gl/b"),
            ),
            (
                "https://maps.app.goo.gl/b",
                Method::Head,
                302,
                Some("https://maps.app.goo.gl/a"),
            ),
        ]);
        assert_eq!(
            run(resolve_redirect(&looping, "https://maps.app.goo.gl/a", 4)),
            Err(ResolveError::TooManyRedirects)
        );
        let no_location = Table::new(&[("https://maps.app.goo.gl/x", Method::Head, 302, None)]);
        assert_eq!(
            run(resolve_redirect(
                &no_location,
                "https://maps.app.goo.gl/x",
                4
            )),
            Err(ResolveError::BadLocation)
        );
        let to_file = Table::new(&[(
            "https://maps.app.goo.gl/x",
            Method::Head,
            302,
            Some("file:///etc/passwd"),
        )]);
        assert_eq!(
            run(resolve_redirect(&to_file, "https://maps.app.goo.gl/x", 4)),
            Err(ResolveError::BadLocation)
        );
        // Not a short link: nothing is fetched.
        let table = Table::new(&[]);
        for url in [PLACE, "https://example.com/x", "geo:1,2"] {
            assert_eq!(
                run(resolve_redirect(&table, url, 4)),
                Err(ResolveError::NotShortLink)
            );
        }
        assert!(table.asked.lock().unwrap().is_empty());
    }
}
