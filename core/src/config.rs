//! Network configuration and the HTTP client built from it.

use std::sync::Arc;
use std::time::Duration;

use pubky::PubkyHttpClient;
use url::Url;

use crate::error::CoreError;

/// Default request timeout: the SDK sets none, and a phone must never wait forever.
pub const DEFAULT_REQUEST_TIMEOUT_MS: u64 = 30_000;
/// Shortest and longest request timeout the core accepts.
const MIN_REQUEST_TIMEOUT_MS: u64 = 1_000;
const MAX_REQUEST_TIMEOUT_MS: u64 = 600_000;

/// Network settings of the core. Every field is editable from the app's settings.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CoreConfig {
    /// Pkarr relays, tried together with the DHT. Default: the SDK's `pkarr::DEFAULT_RELAYS`.
    pub pkarr_relays: Vec<String>,
    /// Resolve keys over the Mainline DHT straight from the phone. Default: on.
    pub dht_enabled: bool,
    /// DHT bootstrap nodes as `host:port`. Empty = the Mainline defaults. Prefer IP
    /// addresses: host names are looked up once, when the core is built.
    pub dht_bootstrap_nodes: Vec<String>,
    /// HTTP relays for the Pubky Ring sign-in, tried in order: a sign-in moves to the next
    /// one (with a new link) when a relay keeps failing. Default: the SDK's
    /// `DEFAULT_HTTP_RELAY_INBOX`. The relay is part of the sign-in link, so Ring follows it.
    pub auth_relay_urls: Vec<String>,
    /// Deadline for each HTTP request, in milliseconds (1 s to 10 min).
    pub request_timeout_ms: u64,
}

/// The defaults, read from the SDK constants.
#[uniffi::export]
pub fn default_core_config() -> CoreConfig {
    CoreConfig {
        pkarr_relays: pubky::pkarr::DEFAULT_RELAYS
            .iter()
            .map(|relay| (*relay).to_string())
            .collect(),
        dht_enabled: true,
        dht_bootstrap_nodes: Vec::new(),
        auth_relay_urls: vec![pubky::DEFAULT_HTTP_RELAY_INBOX.to_string()],
        request_timeout_ms: DEFAULT_REQUEST_TIMEOUT_MS,
    }
}

/// A config whose URLs parsed and whose values are in range.
#[derive(Debug, Clone)]
pub(crate) struct ValidConfig {
    pub pkarr_relays: Vec<Url>,
    pub dht_enabled: bool,
    pub dht_bootstrap_nodes: Vec<String>,
    pub auth_relay_urls: Vec<Url>,
    pub request_timeout: Duration,
}

fn http_url(field: &str, raw: &str) -> Result<Url, CoreError> {
    let url = Url::parse(raw.trim())
        .map_err(|e| CoreError::validation(format!("{field}: not a URL ({e}): {raw}")))?;
    if !matches!(url.scheme(), "http" | "https") || url.host_str().is_none() {
        return Err(CoreError::validation(format!(
            "{field}: must be an http or https URL: {raw}"
        )));
    }
    Ok(url)
}

impl CoreConfig {
    pub(crate) fn validate(&self) -> Result<ValidConfig, CoreError> {
        let pkarr_relays = self
            .pkarr_relays
            .iter()
            .map(|relay| http_url("pkarr relay", relay))
            .collect::<Result<Vec<_>, _>>()?;
        if !self.dht_enabled && pkarr_relays.is_empty() {
            return Err(CoreError::validation(
                "no way to find homeservers: enable the DHT or add a Pkarr relay",
            ));
        }
        let mut dht_bootstrap_nodes = Vec::with_capacity(self.dht_bootstrap_nodes.len());
        for node in &self.dht_bootstrap_nodes {
            let node = node.trim();
            match node.rsplit_once(':') {
                Some((host, port)) if !host.is_empty() && port.parse::<u16>().is_ok() => {
                    dht_bootstrap_nodes.push(node.to_string());
                }
                _ => {
                    return Err(CoreError::validation(format!(
                        "DHT bootstrap node must be host:port: {node}"
                    )));
                }
            }
        }
        let auth_relay_urls = self
            .auth_relay_urls
            .iter()
            .map(|relay| http_url("auth relay", relay))
            .collect::<Result<Vec<_>, _>>()?;
        if auth_relay_urls.is_empty() {
            return Err(CoreError::validation(
                "add at least one auth relay for the Pubky Ring sign-in",
            ));
        }
        if !(MIN_REQUEST_TIMEOUT_MS..=MAX_REQUEST_TIMEOUT_MS).contains(&self.request_timeout_ms) {
            return Err(CoreError::validation(format!(
                "request timeout must be {MIN_REQUEST_TIMEOUT_MS} to {MAX_REQUEST_TIMEOUT_MS} ms"
            )));
        }
        Ok(ValidConfig {
            pkarr_relays,
            dht_enabled: self.dht_enabled,
            dht_bootstrap_nodes,
            auth_relay_urls,
            request_timeout: Duration::from_millis(self.request_timeout_ms),
        })
    }
}

/// TLS for ICANN hosts with the Mozilla roots compiled in.
///
/// Used for the Pkarr relay client so no request ever reaches reqwest's default verifier
/// (rustls-platform-verifier), which on Android panics unless JVM classes were initialised
/// from Kotlin first. The SDK's own ICANN client already uses the same roots.
fn webpki_tls_config() -> Result<rustls::ClientConfig, CoreError> {
    let mut roots = rustls::RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let config = rustls::ClientConfig::builder_with_provider(Arc::new(
        rustls::crypto::aws_lc_rs::default_provider(),
    ))
    .with_safe_default_protocol_versions()
    .map_err(|e| CoreError::internal(format!("TLS setup failed: {e}")))?
    .with_root_certificates(roots)
    .with_no_client_auth();
    Ok(config)
}

const USER_AGENT: &str = concat!("pubky-maps-core/", env!("CARGO_PKG_VERSION"));

/// A plain HTTP client for ICANN hosts with the compiled-in roots and a total `timeout`.
pub(crate) fn icann_client(timeout: Duration) -> Result<reqwest::Client, CoreError> {
    reqwest::Client::builder()
        .user_agent(USER_AGENT)
        .timeout(timeout)
        .tls_backend_preconfigured(webpki_tls_config()?)
        .build()
        .map_err(|e| CoreError::internal(format!("HTTP client: {e}")))
}

/// The map-link client: like [`icann_client`], but it never follows a redirect, so a short
/// link's hops are read one `Location` header at a time.
pub(crate) fn link_client(timeout: Duration) -> Result<reqwest::Client, CoreError> {
    reqwest::Client::builder()
        .user_agent(USER_AGENT)
        .timeout(timeout)
        .redirect(reqwest::redirect::Policy::none())
        .tls_backend_preconfigured(webpki_tls_config()?)
        .build()
        .map_err(|e| CoreError::internal(format!("HTTP client: {e}")))
}

/// Builds the Pubky HTTP client (homeservers, Pkarr relays, the DHT).
pub(crate) fn build_client(config: &ValidConfig) -> Result<PubkyHttpClient, CoreError> {
    let relay_http = icann_client(config.request_timeout)?;

    let mut builder = PubkyHttpClient::builder();
    builder
        .request_timeout(config.request_timeout)
        .user_agent_extra(USER_AGENT);

    let mut relay_error = None;
    builder.pkarr(|pkarr| {
        pkarr.no_default_network().reqwest_client(relay_http);
        if !config.pkarr_relays.is_empty()
            && let Err(e) = pkarr.relays(&config.pkarr_relays)
        {
            relay_error = Some(e.to_string());
        }
        if config.dht_enabled {
            if config.dht_bootstrap_nodes.is_empty() {
                pkarr.dht(|dht| dht);
            } else {
                pkarr.bootstrap(&config.dht_bootstrap_nodes);
            }
        }
        pkarr
    });
    if let Some(e) = relay_error {
        return Err(CoreError::validation(format!("pkarr relay: {e}")));
    }
    builder
        .build()
        .map_err(|e| CoreError::internal(format!("HTTP client: {e}")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_come_from_the_sdk() {
        let config = default_core_config();
        assert_eq!(
            config.pkarr_relays,
            vec!["https://pkarr.pubky.app", "https://pkarr.pubky.org"]
        );
        assert_eq!(
            config.pkarr_relays.len(),
            pubky::pkarr::DEFAULT_RELAYS.len()
        );
        assert!(config.dht_enabled);
        assert!(config.dht_bootstrap_nodes.is_empty());
        assert_eq!(
            config.auth_relay_urls,
            vec!["https://httprelay.pubky.app/inbox"]
        );
        assert_eq!(config.auth_relay_urls[0], pubky::DEFAULT_HTTP_RELAY_INBOX);
        assert_eq!(config.request_timeout_ms, 30_000);
        let valid = config.validate().unwrap();
        assert_eq!(valid.request_timeout, Duration::from_secs(30));
    }

    #[test]
    fn rejects_unusable_configs() {
        let mut config = default_core_config();
        config.dht_enabled = false;
        config.pkarr_relays.clear();
        assert!(matches!(
            config.validate(),
            Err(CoreError::Validation { .. })
        ));

        let mut config = default_core_config();
        config.pkarr_relays = vec!["ftp://relay.example".into()];
        assert!(matches!(
            config.validate(),
            Err(CoreError::Validation { .. })
        ));

        let mut config = default_core_config();
        config.auth_relay_urls = vec!["https://relay.example/inbox".into(), "not a url".into()];
        assert!(matches!(
            config.validate(),
            Err(CoreError::Validation { .. })
        ));

        let mut config = default_core_config();
        config.auth_relay_urls.clear();
        assert!(matches!(
            config.validate(),
            Err(CoreError::Validation { .. })
        ));

        let mut config = default_core_config();
        config.request_timeout_ms = 10;
        assert!(matches!(
            config.validate(),
            Err(CoreError::Validation { .. })
        ));

        let mut config = default_core_config();
        config.dht_bootstrap_nodes = vec!["router.example".into()];
        assert!(matches!(
            config.validate(),
            Err(CoreError::Validation { .. })
        ));
    }

    #[test]
    fn relays_only_and_dht_only_are_both_valid() {
        let mut config = default_core_config();
        config.dht_enabled = false;
        assert!(config.validate().is_ok());

        let mut config = default_core_config();
        config.pkarr_relays.clear();
        config.dht_bootstrap_nodes = vec!["127.0.0.1:6881".into()];
        let valid = config.validate().unwrap();
        assert!(valid.pkarr_relays.is_empty());
        assert_eq!(valid.dht_bootstrap_nodes, vec!["127.0.0.1:6881"]);
    }

    #[test]
    fn client_builds_from_defaults_and_custom_lists() {
        let runtime = tokio::runtime::Runtime::new().unwrap();
        let _guard = runtime.enter();
        let mut config = default_core_config();
        config.dht_enabled = false;
        build_client(&config.validate().unwrap()).unwrap();

        let mut config = default_core_config();
        config.pkarr_relays = vec!["https://relay.example.org".into()];
        config.dht_enabled = false;
        build_client(&config.validate().unwrap()).unwrap();
    }
}
