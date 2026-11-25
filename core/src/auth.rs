//! Pubky Ring sign-in: capabilities, return links and resumable flow state.

use std::sync::Arc;

use pubky::deep_links::XCallbackParams;
use pubky::{AuthFlowKind, Capabilities, ClientId, GrantAuthFlowState, PublicKey};
use pubky_social_specs::{Root, social_path};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use zeroize::Zeroizing;

use crate::error::CoreError;
use crate::secret::SecretValue;

/// The client id the homeserver records for this app's grants.
pub const CLIENT_ID: &str = "pubky.maps";
/// The app's own private folder (`/priv` is alpha: access-controlled, not encrypted).
pub const APP_PRIVATE_DIR: &str = "/priv/pubky.maps/";
/// The scheme of the app links Ring returns to.
pub const CALLBACK_SCHEME: &str = "pubkymaps";
/// The host of those links: `pubkymaps://auth/{success|error|cancel}?nonce=…`.
pub const CALLBACK_HOST: &str = "auth";
const CALLBACK_SOURCE: &str = "Pubky Maps";

/// The directories the app asks write access for, narrowest first in mind: each write
/// capability is total inside its directory.
pub(crate) fn capability_scopes() -> Vec<String> {
    vec![
        social_path(Root::Pub, "tags/"),
        social_path(Root::Pub, "posts/"),
        social_path(Root::Pub, "files/"),
        social_path(Root::Priv, "bookmarks/"),
        social_path(Root::Priv, "posts/"),
        APP_PRIVATE_DIR.to_string(),
    ]
}

pub(crate) fn capabilities() -> Result<Capabilities, CoreError> {
    let mut builder = Capabilities::builder();
    for scope in capability_scopes() {
        builder = builder
            .read_write(&scope)
            .map_err(|e| CoreError::internal(format!("capability {scope}: {e}")))?;
    }
    Ok(builder.finish())
}

/// The capabilities a sign-in asks for, as `scope:rw` strings (for the consent text).
#[uniffi::export]
pub fn requested_capabilities() -> Vec<String> {
    capability_scopes()
        .into_iter()
        .map(|scope| format!("{scope}:rw"))
        .collect()
}

pub(crate) fn client_id() -> Result<ClientId, CoreError> {
    ClientId::new(CLIENT_ID).map_err(|e| CoreError::internal(format!("client id: {e}")))
}

/// A fresh random nonce for the return links of one flow.
pub(crate) fn new_nonce() -> String {
    let mut bytes = [0_u8; 16];
    rand::rng().fill_bytes(&mut bytes);
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn callback_url(outcome: &str, nonce: &str) -> String {
    format!("{CALLBACK_SCHEME}://{CALLBACK_HOST}/{outcome}?nonce={nonce}")
}

pub(crate) fn x_callback(nonce: &str) -> XCallbackParams {
    XCallbackParams {
        x_source: Some(CALLBACK_SOURCE.into()),
        x_success: Some(callback_url("success", nonce)),
        x_error: Some(callback_url("error", nonce)),
        x_cancel: Some(callback_url("cancel", nonce)),
    }
}

/// What Ring reported when it sent the user back to the app.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AuthCallback {
    /// Approved: the session arrives through the relay; keep awaiting or poll now.
    Success,
    /// Ring failed.
    Error,
    /// The user declined in Ring.
    Cancel,
}

/// Reads a `pubkymaps://auth/...` link and checks it belongs to the flow with `expected_nonce`.
#[uniffi::export]
pub fn parse_auth_callback(url: String, expected_nonce: String) -> Result<AuthCallback, CoreError> {
    let parsed = url::Url::parse(url.trim())
        .map_err(|e| CoreError::validation(format!("not a link: {e}")))?;
    if parsed.scheme() != CALLBACK_SCHEME || parsed.host_str() != Some(CALLBACK_HOST) {
        return Err(CoreError::validation("not a Pubky Maps sign-in link"));
    }
    let nonce = parsed
        .query_pairs()
        .find_map(|(k, v)| (k == "nonce").then(|| v.into_owned()));
    if expected_nonce.is_empty() || nonce.as_deref() != Some(expected_nonce.as_str()) {
        return Err(CoreError::auth(
            "sign-in link does not belong to the running sign-in",
        ));
    }
    match parsed.path() {
        "/success" => Ok(AuthCallback::Success),
        "/error" => Ok(AuthCallback::Error),
        "/cancel" => Ok(AuthCallback::Cancel),
        other => Err(CoreError::validation(format!(
            "unknown sign-in result: {other}"
        ))),
    }
}

/// What kind of flow a request is.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum AuthFlowType {
    SignIn,
    SignUp,
}

/// A running sign-in. Show `authorization_url()` as a QR code or open it (Ring handles
/// `pubkyauth://`), keep `pending_state()` in secure storage until the flow ends.
#[derive(uniffi::Object)]
pub struct AuthRequest {
    url: Zeroizing<String>,
    nonce: String,
    flow_type: AuthFlowType,
    state: Arc<SecretValue>,
}

impl AuthRequest {
    pub(crate) fn new(
        url: String,
        nonce: String,
        flow_type: AuthFlowType,
        state: Arc<SecretValue>,
    ) -> Arc<Self> {
        Arc::new(Self {
            url: Zeroizing::new(url),
            nonce,
            flow_type,
            state,
        })
    }
}

#[uniffi::export]
impl AuthRequest {
    /// The `pubkyauth://` link for the Ring intent and the QR code. It carries the relay
    /// secret: never log it.
    pub fn authorization_url(&self) -> String {
        self.url.to_string()
    }

    /// The nonce in this flow's return links; pass it to `parse_auth_callback`.
    pub fn callback_nonce(&self) -> String {
        self.nonce.clone()
    }

    pub fn flow_type(&self) -> AuthFlowType {
        self.flow_type
    }

    /// State to resume this flow after a process kill (`resume_sign_in`). Holds the flow's
    /// private key: keep it in secure storage only while the flow runs, delete it after.
    pub fn pending_state(&self) -> Arc<SecretValue> {
        Arc::clone(&self.state)
    }
}

impl std::fmt::Debug for AuthRequest {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("AuthRequest")
            .field("url", &"<redacted>")
            .field("nonce", &self.nonce)
            .field("flow_type", &self.flow_type)
            .field("state", &self.state)
            .finish()
    }
}

/// How long a sign-in may wait for approval. The relay keeps an approval about 5 minutes
/// after Ring sends it; the flow itself gets this long in total.
pub const SIGN_IN_DEADLINE_SECS: u64 = 10 * 60;

/// What kind of flow to build: enough to start it again on another relay.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub(crate) enum FlowSpec {
    SignIn,
    SignUp {
        /// Homeserver key, z-base-32.
        homeserver: String,
        signup_token: Option<String>,
    },
}

impl FlowSpec {
    pub fn flow_type(&self) -> AuthFlowType {
        match self {
            Self::SignIn => AuthFlowType::SignIn,
            Self::SignUp { .. } => AuthFlowType::SignUp,
        }
    }

    pub fn kind(&self) -> Result<AuthFlowKind, CoreError> {
        match self {
            Self::SignIn => Ok(AuthFlowKind::signin()),
            Self::SignUp {
                homeserver,
                signup_token,
            } => {
                let homeserver = PublicKey::try_from(homeserver.as_str())
                    .map_err(|e| CoreError::validation(format!("homeserver key: {e}")))?;
                Ok(AuthFlowKind::signup(homeserver, signup_token.clone()))
            }
        }
    }
}

/// The saved form of a pending flow.
#[derive(Serialize, Deserialize)]
struct SavedFlow {
    v: u8,
    flow: GrantAuthFlowState,
    nonce: String,
    spec: FlowSpec,
    /// Position of the flow's relay in the configured relay list.
    relay_index: usize,
    /// Unix seconds when the flow started.
    started_at: u64,
}

/// A pending flow read back from its saved form.
pub(crate) struct LoadedFlow {
    pub flow: GrantAuthFlowState,
    pub nonce: String,
    pub spec: FlowSpec,
    pub relay_index: usize,
    pub started_at: u64,
}

impl LoadedFlow {
    /// The relay base URL inside the flow's link, as the SDK puts it in relay request URLs.
    pub fn relay_base(&self) -> Option<String> {
        relay_base_of(&self.flow.authorization_url)
    }
}

pub(crate) fn relay_base_of(authorization_url: &str) -> Option<String> {
    url::Url::parse(authorization_url)
        .ok()?
        .query_pairs()
        .find_map(|(k, v)| (k == "relay").then(|| v.trim_end_matches('/').to_string()))
}

pub(crate) fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_secs())
}

/// Whether a flow started at `started_at` is past its deadline at `now`.
pub(crate) fn flow_expired(started_at: u64, now: u64) -> bool {
    now.saturating_sub(started_at) > SIGN_IN_DEADLINE_SECS
}

const SAVED_FLOW_VERSION: u8 = 2;

pub(crate) fn save_flow(
    flow: GrantAuthFlowState,
    nonce: &str,
    spec: &FlowSpec,
    relay_index: usize,
    started_at: u64,
) -> Result<Arc<SecretValue>, CoreError> {
    let saved = SavedFlow {
        v: SAVED_FLOW_VERSION,
        flow,
        nonce: nonce.to_string(),
        spec: spec.clone(),
        relay_index,
        started_at,
    };
    let json = serde_json::to_string(&saved)
        .map_err(|e| CoreError::internal(format!("saving sign-in state: {e}")))?;
    Ok(SecretValue::wrap(json))
}

pub(crate) fn load_flow(state: &SecretValue) -> Result<LoadedFlow, CoreError> {
    let saved: SavedFlow = serde_json::from_str(state.as_str())
        .map_err(|_| CoreError::validation("saved sign-in state is not readable"))?;
    if saved.v != SAVED_FLOW_VERSION {
        return Err(CoreError::validation(
            "saved sign-in state has an unknown version",
        ));
    }
    Ok(LoadedFlow {
        flow: saved.flow,
        nonce: saved.nonce,
        spec: saved.spec,
        relay_index: saved.relay_index,
        started_at: saved.started_at,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn capabilities_are_the_narrow_social_set() {
        assert_eq!(
            requested_capabilities(),
            vec![
                "/pub/social/v1/tags/:rw",
                "/pub/social/v1/posts/:rw",
                "/pub/social/v1/files/:rw",
                "/priv/social/v1/bookmarks/:rw",
                "/priv/social/v1/posts/:rw",
                "/priv/pubky.maps/:rw",
            ]
        );
        let caps = capabilities().unwrap();
        assert_eq!(caps.len(), 6);
        assert!(caps.iter().all(|cap| !cap.is_root()));
    }

    #[test]
    fn callbacks_carry_the_nonce() {
        let nonce = new_nonce();
        assert_eq!(nonce.len(), 32);
        assert_ne!(nonce, new_nonce());
        let cb = x_callback(&nonce);
        assert_eq!(
            cb.x_success.as_deref(),
            Some(format!("pubkymaps://auth/success?nonce={nonce}").as_str())
        );
        assert_eq!(
            parse_auth_callback(cb.x_success.unwrap(), nonce.clone()).unwrap(),
            AuthCallback::Success
        );
        assert_eq!(
            parse_auth_callback(cb.x_cancel.unwrap(), nonce.clone()).unwrap(),
            AuthCallback::Cancel
        );
        assert_eq!(
            parse_auth_callback(cb.x_error.unwrap(), nonce.clone()).unwrap(),
            AuthCallback::Error
        );
    }

    #[test]
    fn foreign_or_stale_callbacks_are_refused() {
        let nonce = new_nonce();
        let url = format!("pubkymaps://auth/success?nonce={nonce}");
        assert!(matches!(
            parse_auth_callback(url.clone(), new_nonce()),
            Err(CoreError::Auth { .. })
        ));
        assert!(parse_auth_callback(url, String::new()).is_err());
        assert!(
            parse_auth_callback(format!("https://auth/success?nonce={nonce}"), nonce.clone())
                .is_err()
        );
        assert!(
            parse_auth_callback(format!("pubkymaps://auth/other?nonce={nonce}"), nonce).is_err()
        );
    }

    #[test]
    fn flows_expire_after_the_deadline() {
        assert!(!flow_expired(1_000, 1_000));
        assert!(!flow_expired(1_000, 1_000 + SIGN_IN_DEADLINE_SECS));
        assert!(flow_expired(1_000, 1_001 + SIGN_IN_DEADLINE_SECS));
        assert!(
            !flow_expired(2_000, 1_000),
            "a clock that went back is not expiry"
        );
    }

    #[test]
    fn relay_base_comes_from_the_link() {
        let url =
            "pubkyauth://signin_grant?caps=/pub/x/:rw&relay=https://relay.example/inbox/&secret=x";
        assert_eq!(
            relay_base_of(url).as_deref(),
            Some("https://relay.example/inbox")
        );
        assert_eq!(relay_base_of("not a url"), None);
    }

    #[test]
    fn saved_flow_round_trips_and_stays_redacted() {
        let state = GrantAuthFlowState {
            authorization_url: "pubkyauth://signin_grant?secret=abc".into(),
            client_key_secret: [7; 32],
            approval_key_secret: None,
        };
        let spec = FlowSpec::SignUp {
            homeserver: "8pinxxgqs41n4aididenw5apqp1urfmzdztr8jt4abrkdn435ewo".into(),
            signup_token: Some("AAAA-BBBB-CCCC".into()),
        };
        let saved = save_flow(state.clone(), "n1", &spec, 1, 1_000).unwrap();
        assert_eq!(format!("{saved:?}"), "SecretValue(<redacted>)");
        let loaded = load_flow(&saved).unwrap();
        assert!(loaded.flow == state);
        assert_eq!(loaded.nonce, "n1");
        assert_eq!(loaded.spec, spec);
        assert_eq!(loaded.spec.flow_type(), AuthFlowType::SignUp);
        assert!(loaded.spec.kind().is_ok());
        assert_eq!(loaded.relay_index, 1);
        assert_eq!(loaded.started_at, 1_000);
        assert!(load_flow(&SecretValue::new("{}".into())).is_err());

        let request = AuthRequest::new(
            "pubkyauth://signin_grant?secret=abc".into(),
            "n1".into(),
            AuthFlowType::SignIn,
            saved,
        );
        let shown = format!("{request:?}");
        assert!(!shown.contains("secret=abc"), "{shown}");
    }
}
