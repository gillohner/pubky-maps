//! Opaque secrets handed to Kotlin: session credentials and pending sign-in state.

use std::fmt;
use std::sync::Arc;

use zeroize::Zeroizing;

/// A secret the app stores (Android Keystore-backed storage) and hands back later.
///
/// It is an object, not a record, so Kotlin's generated class has no `toString` that prints
/// the value, and the Rust `Debug` output is redacted. Read the value only to store it.
#[derive(uniffi::Object)]
pub struct SecretValue {
    value: Zeroizing<String>,
}

impl SecretValue {
    pub(crate) fn wrap(value: String) -> Arc<Self> {
        Arc::new(Self {
            value: Zeroizing::new(value),
        })
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.value
    }
}

#[uniffi::export]
impl SecretValue {
    /// Wraps a value read back from secure storage.
    #[uniffi::constructor]
    pub fn new(value: String) -> Arc<Self> {
        Self::wrap(value)
    }

    /// The raw value, for writing it to secure storage. Never log it.
    pub fn expose(&self) -> String {
        self.value.to_string()
    }
}

impl fmt::Debug for SecretValue {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("SecretValue(<redacted>)")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn debug_output_is_redacted() {
        let secret = SecretValue::new("pubky-grant-credential-v1.very-secret".into());
        let shown = format!("{secret:?}");
        assert_eq!(shown, "SecretValue(<redacted>)");
        assert!(!shown.contains("very-secret"));
        assert_eq!(secret.expose(), "pubky-grant-credential-v1.very-secret");
    }
}
