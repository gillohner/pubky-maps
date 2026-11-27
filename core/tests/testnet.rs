//! End-to-end tests against a local Pubky testnet (DHT, homeserver and HTTP relay in
//! process). They need Postgres: set `TEST_PUBKY_CONNECTION_STRING` and run
//! `cargo test -- --include-ignored`. CI does.
//!
//! The core is configured only through `CoreConfig`, as the app does: the testnet's DHT
//! bootstrap nodes, no Pkarr relays, and the testnet's HTTP relay for sign-in. A signer
//! holding the user's key plays Pubky Ring and approves the `pubkyauth://` link.

use std::sync::Arc;
use std::time::Duration;

use pubky_maps_core::{
    CollectionEdit, CollectionItem, CoreError, ListOptions, PubkyMapsCore, SignInEvent, SignedIn,
    default_core_config, requested_capabilities,
};
use pubky_testnet::EphemeralTestnet;
use pubky_testnet::pubky::{Keypair, PubkySigner};

async fn testnet() -> EphemeralTestnet {
    EphemeralTestnet::builder()
        .with_http_relay()
        .build()
        .await
        .expect("testnet starts (is Postgres reachable?)")
}

async fn core_for(testnet: &EphemeralTestnet) -> Arc<PubkyMapsCore> {
    let mut config = default_core_config();
    config.pkarr_relays = Vec::new();
    config.dht_enabled = true;
    config.dht_bootstrap_nodes = testnet
        .testnet
        .dht_bootstrap_nodes()
        .iter()
        .map(ToString::to_string)
        .collect();
    config.auth_relay_urls = vec![format!("{}inbox", testnet.http_relay().local_url())];
    config.request_timeout_ms = 10_000;
    PubkyMapsCore::create(config).await.expect("core builds")
}

/// The result of a wait that must end signed in.
fn signed(event: SignInEvent) -> SignedIn {
    match event {
        SignInEvent::SignedIn { result } => result,
        other => panic!("expected a signed-in result, got {other:?}"),
    }
}

/// A user with an account on the testnet homeserver, standing in for Pubky Ring.
async fn ring_user(testnet: &EphemeralTestnet) -> PubkySigner {
    let signer = testnet.sdk().unwrap().signer(Keypair::random());
    signer
        .signup(&testnet.homeserver_app().public_key(), None)
        .await
        .expect("signup on testnet");
    signer
}

/// Runs a full sign-in: the core starts it, "Ring" approves the link, the core gets the
/// session.
async fn sign_in(core: &Arc<PubkyMapsCore>, ring: &PubkySigner) -> SignedIn {
    let request = core.start_sign_in().unwrap();
    ring.approve_auth(&request.authorization_url())
        .await
        .expect("ring approves");
    signed(
        tokio::time::timeout(Duration::from_secs(60), core.await_sign_in())
            .await
            .expect("sign-in finishes in time")
            .expect("sign-in succeeds"),
    )
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[pubky_testnet::test]
#[ignore = "needs Postgres: set TEST_PUBKY_CONNECTION_STRING and run with --include-ignored"]
async fn ring_sign_in_restore_and_sign_out() {
    let testnet = testnet().await;
    let ring = ring_user(&testnet).await;
    let core = core_for(&testnet).await;

    let signed = sign_in(&core, &ring).await;
    assert_eq!(signed.info.public_key, ring.public_key().z32());
    assert_eq!(signed.info.client_id.as_deref(), Some("pubky.maps"));
    assert_eq!(
        signed.info.homeserver.as_deref(),
        Some(testnet.homeserver_app().public_key().z32().as_str())
    );
    let mut granted = signed.info.capabilities.clone();
    granted.sort();
    let mut requested = requested_capabilities();
    requested.sort();
    assert_eq!(granted, requested);
    assert!(!core.has_pending_sign_in());
    assert!(!format!("{signed:?}").contains(&signed.secret.expose()));

    // The grant covers only the requested folders.
    core.put("/priv/pubky.maps/settings.json".into(), b"{}".to_vec())
        .await
        .unwrap();
    let denied = core
        .put("/pub/other.app/x.json".into(), b"{}".to_vec())
        .await
        .unwrap_err();
    assert!(matches!(denied, CoreError::Auth { .. }), "{denied:?}");

    // A new process restores the session from the stored secret.
    let restored_core = core_for(&testnet).await;
    let info = restored_core
        .restore_session(signed.secret.clone())
        .await
        .unwrap();
    assert_eq!(info.public_key, signed.info.public_key);
    let read = restored_core
        .get("/priv/pubky.maps/settings.json".into())
        .await
        .unwrap();
    assert_eq!(read, b"{}");
    assert!(restored_core.revalidate_session().await.unwrap().is_some());

    // Signing out revokes the grant: the secret no longer restores.
    restored_core.sign_out().await.unwrap();
    assert!(restored_core.current_session().await.is_none());
    let third = core_for(&testnet).await;
    let err = third.restore_session(signed.secret).await.unwrap_err();
    assert!(matches!(err, CoreError::Auth { .. }), "{err:?}");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[pubky_testnet::test]
#[ignore = "needs Postgres: set TEST_PUBKY_CONNECTION_STRING and run with --include-ignored"]
async fn sign_in_resumes_after_process_death_and_cancels() {
    let testnet = testnet().await;
    let ring = ring_user(&testnet).await;

    // The first process starts a flow and dies; only the pending state survives.
    let first = core_for(&testnet).await;
    let request = first.start_sign_in().unwrap();
    let url = request.authorization_url();
    let state = request.pending_state();
    drop(request);
    drop(first);

    let second = core_for(&testnet).await;
    let resumed = second.resume_sign_in(state).unwrap();
    assert_eq!(resumed.authorization_url(), url);
    ring.approve_auth(&url).await.unwrap();
    let signed = signed(
        tokio::time::timeout(Duration::from_secs(60), second.await_sign_in())
            .await
            .unwrap()
            .unwrap(),
    );
    assert_eq!(signed.info.public_key, ring.public_key().z32());

    // A waiting sign-in ends with Cancelled when the user cancels.
    let third = core_for(&testnet).await;
    third.start_sign_in().unwrap();
    let waiting = {
        let third = Arc::clone(&third);
        tokio::spawn(async move { third.await_sign_in().await })
    };
    tokio::time::sleep(Duration::from_millis(600)).await;
    third.cancel_sign_in();
    let outcome = tokio::time::timeout(Duration::from_secs(10), waiting)
        .await
        .unwrap()
        .unwrap();
    assert!(matches!(outcome, Err(CoreError::Cancelled)), "{outcome:?}");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[pubky_testnet::test]
#[ignore = "needs Postgres: set TEST_PUBKY_CONNECTION_STRING and run with --include-ignored"]
async fn sign_in_falls_back_to_the_next_relay() {
    // A relay that refuses connections, and one whose host answers 403 (a block page).
    let blocked = forbidden_relay().await;
    for first_relay in ["http://127.0.0.1:9/inbox".to_string(), blocked] {
        falls_back_from(first_relay).await;
    }
}

/// A relay host that answers every request with 403. Returns its inbox URL.
async fn forbidden_relay() -> String {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    tokio::spawn(async move {
        while let Ok((mut socket, _)) = listener.accept().await {
            tokio::spawn(async move {
                let mut buf = [0_u8; 4096];
                let _ = socket.read(&mut buf).await;
                let _ = socket
                    .write_all(
                        b"HTTP/1.1 403 Forbidden\r\ncontent-length: 9\r\nconnection: close\r\n\r\nForbidden",
                    )
                    .await;
            });
        }
    });
    format!("http://{address}/inbox")
}

async fn falls_back_from(first_relay: String) {
    let testnet = testnet().await;
    let ring = ring_user(&testnet).await;
    // The first relay is blocked or down, the second is the testnet's.
    let mut config = default_core_config();
    config.pkarr_relays = Vec::new();
    config.dht_bootstrap_nodes = testnet
        .testnet
        .dht_bootstrap_nodes()
        .iter()
        .map(ToString::to_string)
        .collect();
    let working = format!("{}inbox", testnet.http_relay().local_url());
    config.auth_relay_urls = vec![first_relay.clone(), working.clone()];
    config.request_timeout_ms = 10_000;
    let core = PubkyMapsCore::create(config).await.unwrap();

    let first = core.start_sign_in().unwrap();
    let event = tokio::time::timeout(Duration::from_secs(90), core.await_sign_in())
        .await
        .expect("the dead relay is given up in time")
        .unwrap();
    let SignInEvent::RelayChanged { request } = event else {
        panic!("expected a relay change, got {event:?}");
    };
    assert_ne!(request.authorization_url(), first.authorization_url());
    let relay = url::Url::parse(&request.authorization_url())
        .unwrap()
        .query_pairs()
        .find_map(|(k, v)| (k == "relay").then(|| v.into_owned()))
        .unwrap();
    assert_eq!(relay.trim_end_matches('/'), working.trim_end_matches('/'));

    // Ring approves the new link (the app showed it instead of the old one).
    ring.approve_auth(&request.authorization_url())
        .await
        .unwrap();
    let result = signed(
        tokio::time::timeout(Duration::from_secs(60), core.await_sign_in())
            .await
            .unwrap()
            .unwrap(),
    );
    assert_eq!(result.info.public_key, ring.public_key().z32());
    assert_eq!(core.current_session().await.unwrap(), result.info);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[pubky_testnet::test]
#[ignore = "needs Postgres: set TEST_PUBKY_CONNECTION_STRING and run with --include-ignored"]
async fn storage_put_get_list_delete_and_public_reads() {
    let testnet = testnet().await;
    let ring = ring_user(&testnet).await;
    let core = core_for(&testnet).await;
    let signed = sign_in(&core, &ring).await;
    let me = signed.info.public_key;

    for i in 0..5 {
        core.put(
            format!("/priv/pubky.maps/items/{i}.json"),
            format!("{{\"i\":{i}}}").into_bytes(),
        )
        .await
        .unwrap();
    }
    assert_eq!(
        core.get("/priv/pubky.maps/items/3.json".into())
            .await
            .unwrap(),
        b"{\"i\":3}"
    );
    assert!(
        core.exists("/priv/pubky.maps/items/3.json".into())
            .await
            .unwrap()
    );

    // Cursor pagination walks every entry once.
    let mut seen = Vec::new();
    let mut options = ListOptions {
        limit: 2,
        ..ListOptions::default()
    };
    loop {
        let page = core
            .list("/priv/pubky.maps/items/".into(), options.clone())
            .await
            .unwrap();
        seen.extend(page.entries.iter().map(|e| e.path.clone()));
        match page.next_cursor {
            Some(cursor) => options.cursor = Some(cursor),
            None => break,
        }
    }
    let expected: Vec<String> = (0..5)
        .map(|i| format!("/priv/pubky.maps/items/{i}.json"))
        .collect();
    assert_eq!(seen, expected);

    let reversed = core
        .list(
            "/priv/pubky.maps/items/".into(),
            ListOptions {
                reverse: true,
                limit: 1,
                ..ListOptions::default()
            },
        )
        .await
        .unwrap();
    assert_eq!(reversed.entries[0].path, "/priv/pubky.maps/items/4.json");
    assert_eq!(
        reversed.entries[0].uri,
        format!("pubky://{me}/priv/pubky.maps/items/4.json")
    );

    core.delete("/priv/pubky.maps/items/0.json".into())
        .await
        .unwrap();
    let gone = core
        .get("/priv/pubky.maps/items/0.json".into())
        .await
        .unwrap_err();
    assert!(matches!(gone, CoreError::NotFound { .. }), "{gone:?}");
    // Deleting twice is fine.
    core.delete("/priv/pubky.maps/items/0.json".into())
        .await
        .unwrap();

    // Public data of another user, read with no session.
    let tag = core
        .tag_place(
            "https://www.openstreetmap.org/node/270803095".into(),
            "cafe".into(),
        )
        .await
        .unwrap();
    let reader = core_for(&testnet).await;
    let body = reader.public_get(tag.uri.clone()).await.unwrap();
    assert_eq!(body, tag.json.as_bytes());
    let listed = reader
        .public_list(
            format!("pubky://{me}/pub/social/v1/tags/"),
            ListOptions::default(),
        )
        .await
        .unwrap();
    assert_eq!(listed.entries.len(), 1);
    assert_eq!(listed.entries[0].uri, tag.uri);
    assert!(
        reader
            .public_get(format!("pubky://{me}/priv/pubky.maps/items/1.json"))
            .await
            .is_err()
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[pubky_testnet::test]
#[ignore = "needs Postgres: set TEST_PUBKY_CONNECTION_STRING and run with --include-ignored"]
async fn place_objects_round_trip() {
    let testnet = testnet().await;
    let ring = ring_user(&testnet).await;
    let core = core_for(&testnet).await;
    sign_in(&core, &ring).await;
    let place = "https://www.openstreetmap.org/node/270803095";

    // Tag: written once, a second tag is no new write, untag deletes it.
    let tag = core
        .tag_place(place.into(), "Cappuccino".into())
        .await
        .unwrap();
    assert!(tag.path.starts_with("/pub/social/v1/tags/"));
    let again = core
        .tag_place("https://osm.org/node/270803095".into(), "cappuccino".into())
        .await
        .unwrap();
    assert_eq!(again.path, tag.path);
    assert_eq!(again.json, tag.json);
    let stored = core.get(tag.path.clone()).await.unwrap();
    assert_eq!(stored, tag.json.as_bytes());
    // Unknown members a newer client stored survive a rewrite.
    let mut with_ext: serde_json::Value = serde_json::from_slice(&stored).unwrap();
    with_ext["ext"] = serde_json::json!({"from": "newer-app"});
    with_ext["label"] = "Cappuccino".into();
    core.put(tag.path.clone(), with_ext.to_string().into_bytes())
        .await
        .unwrap();
    let rewritten = core
        .tag_place(place.into(), "cappuccino".into())
        .await
        .unwrap();
    let rewritten: serde_json::Value = serde_json::from_str(&rewritten.json).unwrap();
    assert_eq!(rewritten["ext"]["from"], "newer-app");
    assert_eq!(rewritten["label"], "cappuccino");
    core.untag_place(place.into(), "cappuccino".into())
        .await
        .unwrap();
    assert!(!core.exists(tag.path.clone()).await.unwrap());

    // Review.
    let review = core
        .post_review(place.into(), "Best espresso in town.".into())
        .await
        .unwrap();
    let stored: serde_json::Value =
        serde_json::from_slice(&core.get(review.path.clone()).await.unwrap()).unwrap();
    assert_eq!(stored["kind"], "note");
    assert_eq!(stored["parent"], place);

    // Photo: file first, then the post that attaches it.
    let mut jpeg = vec![0xFF, 0xD8, 0xFF, 0xE0];
    jpeg.extend_from_slice(b"JFIF not really a photo");
    let photo = core
        .post_photo(
            "geo:47.376871,8.541694".into(),
            jpeg.clone(),
            "image/jpeg".into(),
            Some("The bar".into()),
            Some("Sunday".into()),
        )
        .await
        .unwrap();
    assert_eq!(core.get(photo.file.path.clone()).await.unwrap(), jpeg);
    let post: serde_json::Value =
        serde_json::from_slice(&core.get(photo.post.path.clone()).await.unwrap()).unwrap();
    assert_eq!(post["kind"], "image");
    assert_eq!(post["embed"], "geo:47.37687,8.54169");
    assert_eq!(post["attachments"][0]["uri"], photo.file.uri);
    let heic = core
        .post_photo(
            place.into(),
            b"\0\0\0\x18ftypheic\0\0\0\0".to_vec(),
            "image/heic".into(),
            None,
            None,
        )
        .await
        .unwrap_err();
    assert!(matches!(heic, CoreError::Validation { .. }), "{heic:?}");

    // List: create, add, remove; each edit is a new version on the homeserver.
    let list = core
        .create_collection(
            "Coffee".into(),
            Some("Where I go".into()),
            vec![CollectionItem {
                uri: place.into(),
                note: None,
            }],
            false,
        )
        .await
        .unwrap();
    let added = core
        .edit_collection(
            list.id.clone(),
            CollectionEdit::Add {
                place: "https://www.openstreetmap.org/way/4".into(),
                note: Some("terrace".into()),
            },
            false,
        )
        .await
        .unwrap();
    assert_ne!(Some(added.edit_id.clone()), list.edit_id);
    let removed = core
        .edit_collection(
            list.id.clone(),
            CollectionEdit::Remove {
                place: place.into(),
            },
            false,
        )
        .await
        .unwrap();
    assert_ne!(removed.edit_id, added.edit_id);
    let head = core
        .edit_collection(
            list.id.clone(),
            CollectionEdit::Remove {
                place: "geo:1,1".into(),
            },
            false,
        )
        .await
        .unwrap();
    assert_eq!(head.edit_id, removed.edit_id, "a no-op edit writes nothing");
    assert_eq!(head.items.len(), 1);
    assert_eq!(head.items[0].uri, "https://www.openstreetmap.org/way/4");
    assert_eq!(head.items[0].note.as_deref(), Some("terrace"));

    // Saved places, short and long; bookmarks of other apps' Pubky objects in the same folder
    // (a pubky.app post) are not saved places, a web page is kept (a Pinakes card can be one).
    let long = format!(
        "https://www.google.com/maps/place/{}/@47.3703,8.548,17z",
        "Kunsthaus".repeat(25)
    );
    core.bookmark_place(place.into()).await.unwrap();
    core.bookmark_place(long.clone()).await.unwrap();
    let author = "8pinxxgqs41n4aididenw5apqp1urfmzdztr8jt4abrkdn435ewo";
    for other in [
        format!("pubky://{author}/pub/pubky.app/posts/0034A0000001"),
        format!("pubky://{author}/pub/pubky.app/posts/0034A0000002"),
    ] {
        core.bookmark_place(other).await.unwrap();
    }
    let web_page = format!("https://example.com/{}", "x".repeat(200));
    core.bookmark_place(web_page.clone()).await.unwrap();
    let saved = core.list_bookmarks(ListOptions::default()).await.unwrap();
    let mut places: Vec<String> = saved.places.iter().map(|p| p.place.clone()).collect();
    places.sort();
    let mut expected = vec![place.to_string(), long.clone(), web_page.clone()];
    expected.sort();
    assert_eq!(places, expected);
    // One bookmark a page: a page of another app's bookmark is skipped and the next page is
    // read in the same call (fewer than 20 skipped pages in a row), so here no page before the
    // end is empty.
    let mut paged = Vec::new();
    let mut cursor = None;
    loop {
        let page = core
            .list_bookmarks(ListOptions {
                cursor: cursor.take(),
                limit: 1,
                ..ListOptions::default()
            })
            .await
            .unwrap();
        if page.places.is_empty() {
            assert_eq!(page.next_cursor, None);
            break;
        }
        paged.extend(page.places.into_iter().map(|p| p.place));
        cursor = page.next_cursor;
        if cursor.is_none() {
            break;
        }
    }
    paged.sort();
    assert_eq!(paged, expected);
    core.remove_bookmark(long).await.unwrap();
    core.remove_bookmark(web_page).await.unwrap();
    let saved = core.list_bookmarks(ListOptions::default()).await.unwrap();
    assert_eq!(saved.places.len(), 1);
    assert_eq!(saved.places[0].place, place);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[pubky_testnet::test]
#[ignore = "needs Postgres: set TEST_PUBKY_CONNECTION_STRING and run with --include-ignored"]
async fn own_lists_public_and_private() {
    let testnet = testnet().await;
    let ring = ring_user(&testnet).await;
    let core = core_for(&testnet).await;
    sign_in(&core, &ring).await;
    let place = "https://www.openstreetmap.org/node/270803095";

    // Nothing posted yet: no lists, no error.
    let empty = core.list_collections(false, vec![]).await.unwrap();
    assert!(empty.collections.is_empty());
    assert!(empty.other_post_ids.is_empty());

    let review = core
        .post_review(place.into(), "Good coffee.".into())
        .await
        .unwrap();
    let public = core
        .create_collection("Coffee".into(), None, vec![], false)
        .await
        .unwrap();
    let private = core
        .create_collection(
            "Secret spots".into(),
            Some("Only mine".into()),
            vec![CollectionItem {
                uri: place.into(),
                note: None,
            }],
            true,
        )
        .await
        .unwrap();
    assert!(private.path.starts_with("/priv/social/v1/posts/"));
    assert!(private.uri.contains("/priv/social/v1/posts/"));

    let scan = core.list_collections(false, vec![]).await.unwrap();
    assert_eq!(scan.collections.len(), 1);
    assert_eq!(scan.collections[0].id, public.id);
    assert_eq!(scan.collections[0].name, "Coffee");
    assert!(!scan.collections[0].private);
    assert_eq!(scan.other_post_ids, vec![review.id.clone()]);

    // A known non-list is not read again.
    let skipped = core
        .list_collections(false, scan.other_post_ids.clone())
        .await
        .unwrap();
    assert_eq!(skipped.collections.len(), 1);
    assert!(skipped.other_post_ids.is_empty());

    let added = core
        .edit_collection(
            public.id.clone(),
            CollectionEdit::Add {
                place: place.into(),
                note: None,
            },
            false,
        )
        .await
        .unwrap();
    let scan = core.list_collections(false, vec![]).await.unwrap();
    assert_eq!(scan.collections[0].edit_id, added.edit_id);
    assert_eq!(scan.collections[0].items.len(), 1);
    assert_eq!(scan.collections[0].items[0].uri, place);

    let scan = core.list_collections(true, vec![]).await.unwrap();
    assert_eq!(scan.collections.len(), 1);
    assert!(scan.collections[0].private);
    assert_eq!(scan.collections[0].uri, private.uri);
    assert_eq!(
        scan.collections[0].description.as_deref(),
        Some("Only mine")
    );
    let removed = core
        .edit_collection(
            private.id.clone(),
            CollectionEdit::Remove {
                place: place.into(),
            },
            true,
        )
        .await
        .unwrap();
    assert!(removed.private);
    assert_eq!(removed.uri, private.uri);
    assert_eq!(
        removed.description.as_deref(),
        Some("Only mine"),
        "an edit keeps the description"
    );
    let scan = core.list_collections(true, vec![]).await.unwrap();
    assert!(scan.collections[0].items.is_empty());
    assert_eq!(scan.collections[0].edit_id, removed.edit_id);

    // A list that also holds another app's objects (pubky.app shares the posts folder): the
    // app is shown only its places, the others counted, and its edits keep them.
    let post = format!(
        "pubky://{}/pub/pubky.app/posts/0034A0000001",
        ring.public_key().z32()
    );
    let mixed = core
        .create_collection(
            "Reading and coffee".into(),
            None,
            vec![
                CollectionItem {
                    uri: post.clone(),
                    note: Some("theirs".into()),
                },
                CollectionItem {
                    uri: place.into(),
                    note: None,
                },
            ],
            false,
        )
        .await
        .unwrap();
    let scan = core.list_collections(false, vec![]).await.unwrap();
    let shown = scan
        .collections
        .iter()
        .find(|list| list.id == mixed.id)
        .unwrap();
    assert_eq!(shown.items.len(), 1);
    assert_eq!(shown.items[0].uri, place);
    assert_eq!(shown.other_items, 1);
    let edited = core
        .edit_collection(
            mixed.id.clone(),
            CollectionEdit::Remove {
                place: place.into(),
            },
            false,
        )
        .await
        .unwrap();
    assert!(edited.items.is_empty());
    assert_eq!(edited.other_items, 1, "the edit kept the other app's post");
    let scan = core.list_collections(false, vec![]).await.unwrap();
    assert!(
        scan.collections.iter().all(|list| list.id != mixed.id),
        "with no place left it is not a list of places"
    );
    let edited = core
        .edit_collection(
            mixed.id.clone(),
            CollectionEdit::Add {
                place: "geo:47.37687,8.54169".into(),
                note: None,
            },
            false,
        )
        .await
        .unwrap();
    assert_eq!(edited.items.len(), 1);
    assert_eq!(edited.other_items, 1);
}
