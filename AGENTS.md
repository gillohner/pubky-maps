# Working on Pubky Maps as an agent

How agents (and people) work in this repository. Read it before changing anything.

## Gates

A change is done when all of these pass on its head commit:

- `./gradlew check`: unit tests (`:shared` host tests, `:androidApp` Robolectric tests), Android lint,
  ktlint (Kotlin official style; run `./gradlew ktlintFormat` first).
- `./gradlew verifyRoborazziDirectDebug`: the screens match the reference screenshots.
- `cargo fmt --check`, `cargo clippy --workspace --all-targets -- -D warnings` and `cargo test` when
  `core/` changed (CI: `.github/workflows/core.yml`); `cargo deny check --deny advisory-not-detected
  advisories` when the crate graph or `deny.toml` changed (CI: `.github/workflows/advisories.yml`,
  also every Monday on main).
- CI (`.github/workflows/android.yml`) green, including the emulator smoke test that installs the
  debug APK, waits for the map to draw (within the start-time budget of `scripts/ci/emulator-smoke.sh`),
  opens Settings and goes back, then the sign-in probe
  (`scripts/ci/sign-in-probe.py`, Pubky Ring sign-in up to the link and back on a fixture relay);
  when the app, the core or CI changed, the Synonym-blocked test (`scripts/ci/synonym-blocked.py`);
  and, when the updater or the release changed, the update probe (`scripts/ci/update-probe.py`): two signed release builds, a
  tampered feed and a tampered APK refused, the real update installed from inside the app.
  On a branch, every job tests the branch merged with main as the run's `changes` job found it
  (`scripts/ci/merge-main.py`; the "merge main" notice names that commit), and a branch that
  conflicts with main fails. A run says nothing about a main that moved on after it: a branch merges
  only on a run that tested main's current head ("Re-run all jobs" otherwise; "Re-run failed jobs"
  keeps the old main).
  Each emulator test on the debug APK also fails when StrictMode caught our code reading disk on the
  main thread or leaking a resource (`scripts/ci/strict_mode.py`); network on the main thread crashes
  the debug app as it crashes the release app.
- `.github/workflows/gradle-advisories.yml` green when a dependency changed (also every Monday on main):
  no Maven library the app ships has an OSV advisory that `gradle/advisory-ignores.toml` does not
  ignore with a reason (`scripts/ci/gradle-advisories.py`).

Every behaviour gets a test: logic in JVM unit tests (`shared/src/commonTest`), Android-side code
and real calls into the Rust core in host tests (`shared/src/androidHostTest`, which load a build of
the core for this machine), screens in Roborazzi screenshot tests (`androidApp/src/test`).

## Screenshot rule

- Every screen has Roborazzi screenshots on a phone (412x915 dp) and a tablet (1280x800 dp), light and
  dark, in `androidApp/screenshots/`.
- After a UI change, run `./gradlew recordRoborazziDirectDebug`, open the new PNGs and look at them before
  committing. Compare them with how Google Maps, CoMaps and OsmAnd handle the same screen.
- MapLibre cannot draw on the JVM. Keep screens split into a composable that takes plain state (tested
  with a placeholder map) and a route that wires the live map (checked by the emulator test).
- A screen with a running progress indicator never goes idle in `ScreenshotTest`'s capture; take it
  with the Compose clock stopped, as `SignInScreenshotTest` does.

## Server-list rule

Every server address (map styles, search, Pubky services, relays) is an ordered list with fallbacks
that the user can edit in settings. Defaults live only in `config/ServerDefaults.kt`. Code never
hard-codes a server elsewhere, and a failing server hands over to the next one in the list.

## Works without Synonym

The app must start and show the map with every Synonym host blocked (`*.pubky.app`, `*.synonym.to`,
Synonym homeservers and our development hosts). Never block the UI on a network call; a missing
service makes a feature degrade, never the app stop. A part that is missing because every server of
its list failed goes into `ServiceHealth`, and the map names it in one line. The Synonym-blocked
emulator test checks this on every change to the app, the core or CI.

## Secrets on the phone

Session credentials and a running sign-in's state are stored only through `SecretStore`
(Keystore-encrypted files in the no-backup folder). They and the `pubkyauth://` link (it holds the
relay secret) are never logged; `toString()` of the types that hold them is redacted.

## No secrets

No keys, tokens, passwords or keystores in the repository, in logs, in command lines or in CI output.
CI secrets go through GitHub secrets only. The release keystore (`ANDROID_KEYSTORE_B64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`) is read only by the `sign` job of `release.yml` and the
update-feed key (`UPDATE_FEED_SIGNING_KEY`) only by its `publish` job; Gradle never sees either. They are
secrets of the GitHub environment `release`, which only deploys from `main` and `v*` tags; manual runs
use the environment `dev` with separate dev keys. Only public keys are in the repository
(`release/update-feed-key.pub`, `release/update-feed-key-dev.pub`, and the release certificate's
SHA-256 in `release/release-cert.sha256`, which every edge and stable APK must match, and the dev
certificate's in `release/release-cert-dev.sha256` for dev APKs). Prefer public keyless sources; a source that needs a key
stays out until its owner provides one.

## Releases

- Never push a `v*` tag or touch the `edge` release from a branch; to try a release change, run
  `release.yml` by hand on the branch (it replaces the `dev` pre-release, signed with the dev keys).
- After publishing, `release.yml` installs the APK it just published on the API 35 emulator (after
  checking its certificate and checksum) and runs the smoke test on it. A manual run on a branch does
  this for the `dev` pre-release.
- Raising `pubkymaps.version` in `gradle.properties` comes with setting `pubkymaps.versionRunOffset` to
  the release workflow's latest run number (`scripts/release/version.py`).
- The updater exists only in the `direct` flavour (`androidApp/src/direct/`). Code shared by both
  flavours must not need it; the `.aab` check (`scripts/release/aab-check.py`) fails a store build
  that has it.

## Decisions

Product, UX and technical decisions are made by whoever does the work and written down with their
reason (in the pull request and in the code where it matters). The reason is what lets the next person
change the decision safely.

## Conventions

- Kotlin official style, ktlint enforced. Composables are PascalCase.
- No compiler warnings in non-test Kotlin: they fail the build. Fix the cause; a `@Suppress` carries its reason.
- No dead code and no stubs that pretend a feature works. A control is shown only when it works.
- Conventional commit messages (`feat(map): ...`, `fix: ...`, `ci: ...`).
- Docs describe the code as it is: no plans, no history.
