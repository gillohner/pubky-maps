//! A stored session whose homeserver cannot be reached. The app keeps such a user signed in,
//! showing what it cached, so restoring must fail with `Network` (retry later), never with `Auth`
//! (which signs the user out).

mod support;

use pubky_maps_core::{CoreError, PubkyMapsCore, SecretValue, default_core_config};
use support::grant_fixture;

#[tokio::test]
async fn an_unreachable_homeserver_is_a_network_error_not_a_sign_out() {
    let mut config = default_core_config();
    // No DHT, and a Pkarr relay that refuses: the homeserver's record cannot be looked up.
    config.dht_enabled = false;
    config.pkarr_relays = vec!["http://127.0.0.1:9".into()];
    config.request_timeout_ms = 5_000;
    let core = PubkyMapsCore::create(config).await.unwrap();
    let fixture = grant_fixture::mint(3600);
    assert_eq!(fixture.public_key.len(), 52);
    let client_key = fixture.secret.split(':').nth(2).unwrap().to_string();

    let error = core
        .restore_session(SecretValue::new(fixture.secret))
        .await
        .unwrap_err();

    assert!(matches!(error, CoreError::Network { .. }), "{error:?}");
    assert!(core.current_session().await.is_none());
    // The error names neither the credential nor its key.
    assert!(!format!("{error:?} {error}").contains(&client_key));
}

#[tokio::test]
async fn an_expired_grant_is_an_auth_error_without_any_request() {
    let core = PubkyMapsCore::create({
        let mut config = default_core_config();
        config.dht_enabled = false;
        config.pkarr_relays = vec!["http://127.0.0.1:9".into()];
        config
    })
    .await
    .unwrap();
    let fixture = grant_fixture::mint(0);
    assert_ne!(fixture.homeserver, fixture.public_key);
    // Valid until "now": expired by the time it is restored.
    std::thread::sleep(std::time::Duration::from_millis(1100));

    let error = core
        .restore_session(SecretValue::new(fixture.secret))
        .await
        .unwrap_err();

    assert!(matches!(error, CoreError::Auth { .. }), "{error:?}");
}
