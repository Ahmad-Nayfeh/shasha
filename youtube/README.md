# :youtube

Everything that touches YouTube, behind the `VideoSource` interface (search, channels, playlists, stream extraction, RSS uploads, link parsing), plus the building of Media3 playback sources. It is the only module that depends on NewPipeExtractor (as `implementation`, never `api`), so replacing the extractor means editing this module only. Failures leave it as `SourceException`.

Entry points, under `src/main/java/com/ahmadnayfeh/shasha/youtube/`:
- `VideoSource.kt` (the contract), `YouTube.kt` (`YouTube.create()` builds it), `SourceException.kt` (the error kinds).
- `newpipe/NewPipeVideoSource.kt`: the implementation over the extractor.
- `playback/YouTubeMediaSourceFactory.kt`: turns `StreamDetails` into a Media3 `MediaSource`.
- `consumer-rules.pro` (module root): R8 rules for the extractor, Rhino and exception names, applied to the app.

Tests (JUnit, Robolectric, MockWebServer, in `src/test/java`): `./gradlew :youtube:testDebugUnitTest`. The real-YouTube tests (`RealNetwork*`) are skipped unless run as `./gradlew :youtube:testDebugUnitTest --tests '*RealNetwork*' -Dyoutube.realNetwork=true`. When YouTube breaks, start at [docs/dev/MAINTENANCE.md](../docs/dev/MAINTENANCE.md); project map: [docs/dev/ARCHITECTURE.md](../docs/dev/ARCHITECTURE.md).
