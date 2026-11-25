# Contributing

1. Fork, branch from `main`, keep the change small and focused.
2. Run `./gradlew ktlintFormat check verifyRoborazziDebug` before you push. After a UI change, run
   `./gradlew recordRoborazziDebug` and commit the updated screenshots in `androidApp/screenshots/`.
3. Add tests for new behaviour: JVM unit tests for logic, Roborazzi screenshots for screens.
4. New servers or data sources go into `config/ServerDefaults.kt` as part of a list with fallbacks,
   with their attribution. Only public sources that need no key.
5. Open a pull request that says what changes for the user and why. CI must be green.

By contributing you agree that your work is released under the MIT licence of this repository.
