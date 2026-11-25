# pubky-maps-core

The Rust core of Pubky Maps. It signs the user in with Pubky Ring, reads and writes their
homeserver, and builds the social objects the app writes about places: tags, reviews, photos,
lists and saved places. Kotlin calls it through UniFFI (package `to.pubky.maps.core`).

It bundles the `pubky` SDK (0.18) and `pubky-social-specs` (1.0.0-rc.2). Object ids, paths and
validation come from the specs crate.

## API in short

- `PubkyMapsCore(config)`: one per process. `defaultCoreConfig()` gives the defaults:
  Pkarr relays `https://pkarr.pubky.app` and `https://pkarr.pubky.org`, DHT on, auth relays
  `[https://httprelay.pubky.app/inbox]`, 30 s request timeout. Every field can be overridden;
  the auth relays are tried in order.
- Sign-in: `startSignIn()` (or `startSignUp(homeserver, token)`) returns an `AuthRequest`.
  Open `authorizationUrl()` (Ring handles `pubkyauth://`) or show it as a QR code, then call
  `awaitSignIn()`. It returns a `SignInEvent`:
  - `SignedIn(result)`: done; the session is installed only now.
  - `RelayChanged(request)`: the relay kept failing, so the sign-in moved to the next auth
    relay with a new link. The core probes the relay when it starts listening and every 25 s,
    and 4 failures in a row (refused connections, timeouts, block pages or other non-relay
    answers) leave it: about 14 s for a refused or blocked relay, about a minute for one that
    never answers. Show
    `request.authorizationUrl()` instead of the old link, store `request.pendingState()`
    instead of the old state, and call `awaitSignIn()` again. When the last relay fails too,
    it throws `Network` ("the sign-in relay is unreachable").

  `pollSignIn()` is the non-waiting variant (it also returns `Waiting`), and
  `currentSignIn()` gives the running request. Ring returns to
  `pubkymaps://auth/{success|error|cancel}?nonce=…`; check those links with
  `parseAuthCallback(url, request.callbackNonce())`. Keep `pendingState()` in secure storage
  while the flow runs, so `resumeSignIn(state)` can pick it up after the process was killed.
  `cancelSignIn()` stops it. If an approval was already being exchanged, its result is
  handed out by the next `awaitSignIn`/`pollSignIn`, or revoked by the next `startSignIn`.
- Session: `SignedIn.secret` is a `SecretValue`. Store `secret.expose()` in Keystore-backed
  storage and restore with `restoreSession(SecretValue(stored))`. If `currentSession()` is set
  but the app has no stored secret (its coroutine was cancelled just as the result arrived),
  `currentSessionSecret()` returns it again. `signOut()` revokes the
  grant, and `forgetSession()` only drops it locally.
- Storage: `put`, `get`, `exists`, `delete` and `list` (with cursor pages) on `/pub/…` and
  `/priv/…`, plus `publicGet` and `publicList` for other users' `/pub` data.
- Places: `normalizePlaceUri`, `osmPlaceUri` and `geoPlaceUri`. Places are canonical OSM URLs,
  `geo:lat,lon` with at most 5 fraction digits, or the link the user pasted (an Android
  search link `geo:0,0?q=<name or address>` is kept as it is, never moved to 0,0).
- Objects: `tagPlace` and `untagPlace`, `postReview`, `postPhoto` (JPEG/PNG/WebP, no HEIC,
  EXIF stripped by the caller), `createCollection` and `editCollection` (at most 100 places),
  and `bookmarkPlace`, `removeBookmark` and `listBookmarks`. The pure `build*` functions return
  the same objects without the network.
- Errors: one `CoreException` with the cases `Network`, `Auth`, `NotSignedIn`, `NotFound`,
  `Validation`, `RateLimited`, `Server`, `Cancelled` and `Internal`.

The sign-in asks for `/pub/social/v1/{tags,posts,files}/`, `/priv/social/v1/{bookmarks,posts}/`
and the app folder `/priv/pubky.maps/`, all read-write. `/priv` is alpha in Pubky: the
homeserver operator can read it.

## Build and test

```sh
cargo test                                   # unit tests
TEST_PUBKY_CONNECTION_STRING=postgres://postgres@localhost:5432/postgres \
  cargo test -- --include-ignored            # plus the local-testnet integration tests
scripts/gen-kotlin-bindings.sh               # Kotlin bindings into core/bindings/kotlin
scripts/build-android.sh                     # arm64-v8a and x86_64 .so files into core/jniLibs
```

The Android build needs `ANDROID_NDK_HOME` (NDK r27 or newer), `cargo install cargo-ndk`, and
`rustup target add aarch64-linux-android x86_64-linux-android`. The libraries are linked with
16 KB page alignment. The generated bindings need JNA and `androidx.annotation` on the Kotlin side.
No TLS setup is needed in Kotlin, because the core uses its own Mozilla root store.
