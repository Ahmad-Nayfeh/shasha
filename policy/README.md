# :policy

Every parental-control rule, as pure Kotlin/JVM (no Android imports, an injected `java.time.Clock`): who may see or play what, the daily quota and schedule, the playback lock, the parent's time grants, PIN hashing and attempt limits, usage statistics. `:app` asks it before showing or playing anything. It depends on no other module.

Entry points, under `src/main/kotlin/com/ahmadnayfeh/shasha/policy/`:
- `PolicyEngine.kt`: all decisions (`canShow`, `canPlay`, `canPlayLink`, `session`, `guardShouldEnforce`, the lock and grant functions).
- `Profile.kt`, `PlaybackLock.kt`, `SessionState.kt`, `PolicyConfig.kt`: the data the engine works on.
- `PinHasher.kt`, `PinAttemptLimiter.kt`, `AdultExitChallenge.kt`: PIN and exit-challenge helpers.

Tests (JUnit 4 and Truth, in `src/test/kotlin`, one file per rule family): `./gradlew :policy:test`

Project map: [docs/dev/ARCHITECTURE.md](../docs/dev/ARCHITECTURE.md).
