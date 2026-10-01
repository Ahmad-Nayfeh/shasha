# :remote

The phone remote's server and web page: a Ktor CIO server that the TV runs on the home network, the single-file page it serves, pairing (QR, token) and LAN discovery. It depends on neither `:policy` nor `:youtube`: it defines the `RemoteController` interface and the JSON wire format, and `:app` implements them (`TvRemoteController`).

Entry points, under `src/main/java/com/ahmadnayfeh/shasha/remote/`:
- `RemoteServer.kt` (start and stop, bound to one address) and `RemoteRoutes.kt` (the HTTP API and the pairing-token check).
- `RemoteController.kt` and `RemoteModels.kt`: what the phone can do, and its JSON shapes.
- `PinGate.kt` and `ParentSessions.kt`: the brake on PIN guesses and the parent sessions.
- `src/main/resources/web/index.html`: the phone page, one self-contained file.

Tests (JUnit and the Ktor test host, in `src/test/java`): `./gradlew :remote:testDebugUnitTest`. `src/test/screenshots/` holds phone-page screenshots (no test compares them); `src/test/java/com/ahmadnayfeh/shasha/remote/qa/QaServer.kt` serves the page with demo data. Project map: [docs/dev/ARCHITECTURE.md](../docs/dev/ARCHITECTURE.md).
