//! Writes a stored grant credential for a made-up user on a made-up homeserver (see
//! `tests/support/grant_fixture.rs`) as JSON `{"publicKey", "homeserver", "secret"}` to a new
//! file only its owner can read. The emulator test that blocks every Synonym host seeds the app
//! with it: the app then holds a session it cannot restore, as when the user's homeserver is
//! unreachable.
//!
//! Usage: cargo run -p pubky-maps-core --example offline_session_fixture -- <out file>

#[path = "../tests/support/grant_fixture.rs"]
mod grant_fixture;

use std::io::Write;
use std::os::unix::fs::OpenOptionsExt;

fn main() {
    let path = std::env::args()
        .nth(1)
        .expect("usage: offline_session_fixture <out file>");
    // A day: long enough for any CI run, short enough to be useless afterwards.
    let fixture = grant_fixture::mint(24 * 3600);
    let json = serde_json::json!({
        "publicKey": fixture.public_key,
        "homeserver": fixture.homeserver,
        "secret": fixture.secret,
    });
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(&path)
        .expect("a new output file");
    file.write_all(json.to_string().as_bytes())
        .expect("writing the fixture");
    println!(
        "wrote a session fixture for {} to {path}",
        fixture.public_key
    );
}
