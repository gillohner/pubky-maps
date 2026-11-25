//! A stored grant credential for a made-up user on a made-up homeserver, in the SDK's storage
//! format (`pubky-grant-credential-v1:<homeserver>:<client key>:<grant JWS>`), signed like Ring
//! signs one. No homeserver ever issued a session for it: restoring it can only fail, with
//! `Network` while the homeserver cannot be reached.

use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use pubky::{Capability, ClientId, GRANT_JWS_TYP, GrantClaims, GrantId, Keypair};
use std::time::{SystemTime, UNIX_EPOCH};

pub struct GrantFixture {
    /// The user's public key, z-base-32.
    pub public_key: String,
    /// The homeserver's public key, z-base-32.
    pub homeserver: String,
    /// The credential, as the app stores `SignedIn.secret`.
    pub secret: String,
}

/// A credential valid for `lifetime_secs` from now, for the app's client id and capabilities.
pub fn mint(lifetime_secs: u64) -> GrantFixture {
    let user = Keypair::random();
    let client = Keypair::random();
    let homeserver = Keypair::random().public_key();
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("clock after 1970")
        .as_secs();
    let caps = pubky_maps_core::requested_capabilities()
        .iter()
        .map(|cap| {
            cap.parse::<Capability>()
                .expect("the app's capabilities parse")
        })
        .collect();
    let claims = GrantClaims {
        iss: user.public_key(),
        client_id: ClientId::new(pubky_maps_core::CLIENT_ID).expect("the app's client id"),
        caps,
        cnf: client.public_key(),
        jti: GrantId::generate(),
        iat: now,
        exp: now + lifetime_secs,
    };
    let jws = claims.sign(&user, GRANT_JWS_TYP);
    let secret = format!(
        "pubky-grant-credential-v1:{}:{}:{jws}",
        homeserver.z32(),
        URL_SAFE_NO_PAD.encode(client.secret())
    );
    GrantFixture {
        public_key: user.public_key().z32(),
        homeserver: homeserver.z32(),
        secret,
    }
}
