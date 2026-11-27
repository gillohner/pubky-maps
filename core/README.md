# pubky-maps-core

The Rust core of Pubky Maps. It signs the user in with Pubky Ring, reads and writes their
homeserver, and builds the social objects the app writes about places: tags, reviews, photos,
lists and saved places. Kotlin calls it through UniFFI (package `to.pubky.maps.core`).

It bundles the `pubky` SDK (0.18), `pubky-social-specs` (1.0.0-rc.2) and the workspace's
`map-links` crate. Object ids, paths and validation come from the specs crate.

## API in short

- `PubkyMapsCore.create(config)`: a `suspend` factory, one core per process. It builds the
  HTTP client and the DHT node on the core's own thread. With the DHT on, that resolves the
  DHT bootstrap host names (a few DNS lookups, which can take seconds on a bad network). The
  calling thread is never blocked, and no other request is made. `defaultCoreConfig()` gives
  the defaults:
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
  `mayBeMapPlace(target)` says whether a bookmark's or collection item's target can be a place
  (a map link or a web page; not another app's `pubky://` object): the rule `listBookmarks`
  filters with, for counting other users' collections in the app.
- Map links (from the `map-links` crate in `core/map-links`): `parseMapLink(url)` and
  `findMapLink(sharedText)` read a Google Maps, Apple Maps, Waze, OpenStreetMap, OsmAnd,
  Organic Maps, CoMaps, HERE or `geo:` link from its text (name, point, OSM element, zoom,
  search text, the provider's place id); `buildPlaceLink` and `buildDirectionsLink` make the
  link that opens a place in one of those apps (`null` where the app has none);
  `mapLinkPlaceUri` gives the canonical OSM URL or `geo:` URI. Two calls use the network:
  `resolveMapLink(url)` follows a short link (`maps.app.goo.gl`, `goo.gl/maps`, `maps.apple/p/`)
  reading only `Location` headers (HEAD, or a GET whose body is never read), at most 4 hops,
  HTTPS to short-link hosts only; `openMapLink(text, pinakesServers)` does that, then asks
  Pinakes (`/v1/resources/by-uri`, the link as pasted, then its OSM element; the next server
  when one fails) and falls back to the link text. Its `LinkedPlace.sourceUri` is the link
  as the user had it, the URI tags and posts go on; `pointIsView` marks a map-view or search
  centre (not a place), `shortLinkUnfollowed` and `pinakesUnreachable` say which network step
  failed. Link requests give up after 5 s to connect and 10 s in all. `placeFromText(text)` gives
  the same place from the text alone (no network, no core instance), to show at once.
- Objects: `tagPlace` and `untagPlace`, `postReview`, `postPhoto` (JPEG/PNG/WebP, no HEIC,
  EXIF stripped by the caller), `createCollection` and `editCollection` (at most 100 items;
  `private` puts the list under `/priv/social/v1/posts/`; an edit returns the list as
  `listCollections` gives it), `listCollections(private, skip)` (the
  user's lists: the newest version of each of their posts, newest posts first, at most 2000;
  each list's `items` are only those that can be places, as for bookmarks below, the others
  (another app's posts or profiles) counted in `otherItems` and kept by edits; a list holding
  only such objects is left out, one without items is kept;
  `otherPostIds` are posts that are not lists, to pass as `skip` next time, and `unreadPostIds`
  folders that could not be read now), and `bookmarkPlace`, `removeBookmark` and `listBookmarks`
  (only bookmarks that can be places: map links the link reader reads and web pages, which a
  Pinakes place card can be; other apps' `pubky://` objects in the same folder, such as
  pubky.app posts, are left out; one call follows up to 20 listing pages that hold no place,
  so a page can be empty and still have a `nextCursor`).
  The pure `build*` functions (`buildTag`, `buildReview`, `buildPhotoFile`, `buildPhotoPost`,
  `buildCollection`, `buildList` with `private`, `buildBookmark`) return the same objects without
  the network, with their ids minted, so the app can queue them and `put` them later to the same
  path.
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
cargo run --example offline_session_fixture -- <file>   # a stored session that cannot be restored
```

`offline_session_fixture` writes a stored grant credential, in the SDK's format and signed like Ring
signs one, for a made-up user on a made-up homeserver. Restoring it fails with `Network`, as for a
user whose homeserver is unreachable; the emulator test that blocks every Synonym host seeds the app
with it.

The app's Gradle build runs both scripts itself (into its build directories). The Android build
needs `ANDROID_NDK_HOME` (NDK r27 or newer), `cargo install cargo-ndk`, and
`rustup target add aarch64-linux-android x86_64-linux-android`. The libraries are linked with
16 KB page alignment. The generated bindings need JNA and `androidx.annotation` on the Kotlin side.
No TLS setup is needed in Kotlin, because the core uses its own Mozilla root store.

## Advisories

`cargo deny check --deny advisory-not-detected advisories` (`cargo install cargo-deny`; CI:
`.github/workflows/advisories.yml`, when the crate graph or `deny.toml` changes and every Monday)
checks the crates the app ships against the RustSec advisories: the core for both Android ABIs,
without dev dependencies (the test homeserver and relay) and without `uniffi-bindgen`. A
vulnerable, unsound or yanked crate fails it, and so does an unmaintained crate the core depends
on itself. The ignores in `deny.toml` each name the crate that pins the advisory and why it cannot
happen in the app; one that matches nothing any more fails the check, so it is dropped once its
cause is gone. They all come with `pubky 0.10`, which `pubky-social-specs` 1.0.0-rc.2 depends on
beside the core's `pubky 0.18` (lru 0.16 through mainline 7 and pkarr 7, heapless 0.7 through
postcard). The ABIs are those `scripts/build-android.sh` builds; a new one goes into `deny.toml` too.
