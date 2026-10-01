# :app

The Android TV application (package `com.ahmadnayfeh.shasha`): Compose UI, the Media3 player, the parental-control glue, the guard, the host of the phone remote and the self-updater. It depends on `:youtube`, `:policy` and `:remote`, and never imports NewPipeExtractor.

Entry points, under `src/main/java/com/ahmadnayfeh/shasha/`:
- `ShashaApp.kt` and `AppContainer.kt`: process start and manual dependency injection.
- `MainActivity.kt`, then `ui/app/ShashaRoot.kt` and `ui/navigation/ProfileNavHost.kt`: the UI root and the screens of each profile kind.
- `policy/PolicyGate.kt` (every rule), `lock/GuardService.kt` (the guard), `remote/RemoteService.kt` (phone remote), `update/Updater.kt` (updates).

Tests (Robolectric and Compose, in `src/test/java`): `./gradlew :app:verifyRoborazziDebug` runs them all and compares screens with the PNGs in `src/test/screenshots/`. After an intended UI change run `./gradlew :app:recordRoborazziDebug`. Real-network tests are skipped unless `-Dyoutube.realNetwork=true`.

Project map: [docs/dev/ARCHITECTURE.md](../docs/dev/ARCHITECTURE.md).
