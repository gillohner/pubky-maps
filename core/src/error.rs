//! The one error type every exported function returns.

use pubky::errors::{AuthError, RequestErrorKind};

/// Errors as Kotlin sees them: one sealed `CoreException` class with a subclass per variant.
/// The text field is `reason` (Kotlin's `Throwable.message` is generated from it).
///
/// Messages never carry secrets: grant credentials and relay secrets are cut out of every
/// message before it leaves the core (see [`redact`]).
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    /// The network failed: no connection, a timeout, TLS, or a Pkarr/DHT lookup that found
    /// nothing. Safe to retry later; a timed-out write may still have landed.
    #[error("network error: {reason}")]
    Network { reason: String },
    /// The session is missing, expired or revoked, or the grant does not cover the path.
    /// The app asks the user to sign in with Pubky Ring again.
    #[error("authentication error: {reason}")]
    Auth { reason: String },
    /// No signed-in session. Call `restore_session` or finish a sign-in first.
    #[error("not signed in")]
    NotSignedIn,
    /// The path or object does not exist.
    #[error("not found: {reason}")]
    NotFound { reason: String },
    /// Input was rejected before or by the homeserver: a bad label, URI, path or size.
    #[error("invalid input: {reason}")]
    Validation { reason: String },
    /// The homeserver throttled the request (HTTP 429). Retry with backoff.
    #[error("rate limited: {reason}")]
    RateLimited { reason: String },
    /// The homeserver answered with another error status.
    #[error("server error {status}: {reason}")]
    Server { status: u16, reason: String },
    /// The operation was cancelled, for example a sign-in cancelled by the user.
    #[error("cancelled")]
    Cancelled,
    /// A bug or an unexpected state inside the core.
    #[error("internal error: {reason}")]
    Internal { reason: String },
}

impl CoreError {
    pub(crate) fn validation(message: impl Into<String>) -> Self {
        Self::Validation {
            reason: redact(&message.into()),
        }
    }

    pub(crate) fn internal(message: impl Into<String>) -> Self {
        Self::Internal {
            reason: redact(&message.into()),
        }
    }

    pub(crate) fn auth(message: impl Into<String>) -> Self {
        Self::Auth {
            reason: redact(&message.into()),
        }
    }
}

fn from_status(status: u16, reason: String) -> CoreError {
    match status {
        401 | 403 => CoreError::Auth { reason },
        404 | 410 => CoreError::NotFound { reason },
        429 => CoreError::RateLimited { reason },
        400 | 409 | 413 | 414 | 422 => CoreError::Validation { reason },
        _ => CoreError::Server { status, reason },
    }
}

impl From<pubky::Error> for CoreError {
    fn from(error: pubky::Error) -> Self {
        let reason = redact(&error.to_string());
        match error {
            pubky::Error::Request(request) => match request.kind() {
                RequestErrorKind::Http { status } => from_status(status, reason),
                RequestErrorKind::InvalidRequest => CoreError::Validation { reason },
                _ => CoreError::Network { reason },
            },
            pubky::Error::CredentialRefresh(refresh) => match refresh {
                pubky::CredentialRefreshError::Request {
                    status: Some(status),
                } => from_status(status.as_u16(), reason),
                _ => CoreError::Network { reason },
            },
            pubky::Error::Pkarr(_) => CoreError::Network { reason },
            pubky::Error::Parse(_) => CoreError::Validation { reason },
            pubky::Error::Authentication(AuthError::RequestExpired) => CoreError::Auth {
                reason: "the sign-in request expired; start a new one".into(),
            },
            pubky::Error::Authentication(_) => CoreError::Auth { reason },
            pubky::Error::Build(_) => CoreError::Internal { reason },
        }
    }
}

/// Markers that start a secret inside SDK messages: a relay secret in a `pubkyauth://` URL,
/// and an exported grant credential.
const SECRET_MARKERS: [&str; 2] = ["secret=", "pubky-grant-credential"];

/// Cuts every secret out of a message: from a marker up to the next `&`, whitespace or quote.
pub(crate) fn redact(message: &str) -> String {
    let mut out = String::with_capacity(message.len());
    let mut rest = message;
    loop {
        let next = SECRET_MARKERS
            .iter()
            .filter_map(|marker| rest.find(marker).map(|at| (at, *marker)))
            .min_by_key(|(at, _)| *at);
        let Some((at, marker)) = next else {
            out.push_str(rest);
            return out;
        };
        out.push_str(&rest[..at]);
        out.push_str(marker);
        out.push_str("<redacted>");
        let tail = &rest[at + marker.len()..];
        let end = tail
            .find(|c: char| c == '&' || c == '"' || c == '\'' || c.is_whitespace())
            .unwrap_or(tail.len());
        rest = &tail[end..];
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn redact_cuts_relay_secrets_and_credentials() {
        let msg =
            "bad url pubkyauth://signin_grant?caps=/pub/x/:rw&secret=kqnceEMgrNQM&relay=https://r";
        let out = redact(msg);
        assert!(!out.contains("kqnceEMgrNQM"), "{out}");
        assert!(out.contains("secret=<redacted>&relay=https://r"), "{out}");

        let msg = "restore failed for pubky-grant-credential-v1.abc.def: bad";
        let out = redact(msg);
        assert!(!out.contains("abc.def"), "{out}");
        assert_eq!(
            out,
            "restore failed for pubky-grant-credential<redacted> bad"
        );
    }

    #[test]
    fn redact_leaves_plain_messages_alone() {
        assert_eq!(redact("404 Not Found"), "404 Not Found");
        assert_eq!(redact(""), "");
    }

    #[test]
    fn status_codes_map_to_variants() {
        assert!(matches!(
            from_status(404, String::new()),
            CoreError::NotFound { .. }
        ));
        assert!(matches!(
            from_status(401, String::new()),
            CoreError::Auth { .. }
        ));
        assert!(matches!(
            from_status(403, String::new()),
            CoreError::Auth { .. }
        ));
        assert!(matches!(
            from_status(429, String::new()),
            CoreError::RateLimited { .. }
        ));
        assert!(matches!(
            from_status(409, String::new()),
            CoreError::Validation { .. }
        ));
        assert!(matches!(
            from_status(503, String::new()),
            CoreError::Server { status: 503, .. }
        ));
    }
}
