# Working on Pubky Maps as an agent

How agents (and people) work in this repository. Read it before changing anything.

## Gates

A change is done when all of these pass on its head commit:

- `./gradlew check`: unit tests (`:shared` host tests, `:androidApp` Robolectric tests), Android lint,
  ktlint (Kotlin official style; run `./gradlew ktlintFormat` first).
- `./gradlew verifyRoborazziDebug`: the screens match the reference screenshots.
- CI (`.github/workflows/android.yml`) green, including the emulator smoke test that installs the
  debug APK, waits for the map to draw, opens Settings and goes back.

Every behaviour gets a test: logic in JVM unit tests (`shared/src/commonTest`), screens in Roborazzi
screenshot tests (`androidApp/src/test`).

## Screenshot rule

- Every screen has Roborazzi screenshots on a phone (412x915 dp) and a tablet (1280x800 dp), light and
  dark, in `androidApp/screenshots/`.
- After a UI change, run `./gradlew recordRoborazziDebug`, open the new PNGs and look at them before
  committing. Compare them with how Google Maps, CoMaps and OsmAnd handle the same screen.
- MapLibre cannot draw on the JVM. Keep screens split into a composable that takes plain state (tested
  with a placeholder map) and a route that wires the live map (checked by the emulator test).

## Server-list rule

Every server address (map styles, search, Pubky services, relays) is an ordered list with fallbacks
that the user can edit in settings. Defaults live only in `config/ServerDefaults.kt`. Code never
hard-codes a server elsewhere, and a failing server hands over to the next one in the list.

## Works without Synonym

The app must start and show the map with every Synonym host blocked (`*.pubky.app`, `*.synonym.to`,
Synonym homeservers and our development hosts). Never block the UI on a network call; a missing
service makes a feature degrade, never the app stop.

## No secrets

No keys, tokens, passwords or keystores in the repository, in logs, in command lines or in CI output.
CI secrets go through GitHub secrets only. Prefer public keyless sources; a source that needs a key
stays out until its owner provides one.

## Decisions

Product, UX and technical decisions are made by whoever does the work and written down with their
reason (in the pull request and in the code where it matters). The reason is what lets the next person
change the decision safely.

## Conventions

- Kotlin official style, ktlint enforced. Composables are PascalCase.
- No dead code and no stubs that pretend a feature works. A control is shown only when it works.
- Conventional commit messages (`feat(map): ...`, `fix: ...`, `ci: ...`).
- Docs describe the code as it is: no plans, no history.
