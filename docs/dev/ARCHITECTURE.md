# Shasha architecture

Shasha («شاشة») is a private YouTube client for one family's Google TV (it also runs on any Android TV and on Amazon Fire TV; minSdk 25, that is Android 7.1 / Fire OS 6, see [tv-compatibility.md](tv-compatibility.md)): no ads, no account, no analytics; kids' profiles with allowlists and a daily quota; a lock that keeps a started video on screen; and a phone page that works as a remote. All data stays on the TV. The only network peers are YouTube (through NewPipeExtractor), SponsorBlock and GitHub Releases (the updater).

This file is the map of the code. For "YouTube broke", signing and releases, read [MAINTENANCE.md](MAINTENANCE.md) (Arabic). The owner's original requirements are in [SPEC.md](SPEC.md) (Arabic); their status is in [acceptance.md](acceptance.md).

## 1. Repository map

| Path | What it is |
|---|---|
| `app/`, `policy/`, `remote/`, `youtube/`, `mobile/` | the five Gradle modules (section 2); all but `mobile/` have a short `README.md` |
| `gradle/libs.versions.toml` | the only version catalog: exact versions, never ranges or `+` |
| `.github/` | CI: `workflows/android.yml`, `youtube-canary.yml`, `voice.yml`, and `dependabot.yml` (section 8) |
| `docs/dev/` | developer documents: this file, `MAINTENANCE.md`, `SPEC.md`, `acceptance.md`, `youtube-test-results.md`, `feed-sync-results.md` |
| `docs/guide/` | sources of the user guide in Arabic and English (section 9); built PDFs: `docs/دليل-شاشة.pdf`, `docs/Shasha-Guide.pdf` |
| `docs/images/install/`, `docs/video/` | install photos used by the guide; the explainer video and its poster |
| `Shasha-Setup/`, `Shasha-Setup.zip`, `tools/` | the install kit users download (installers for Windows, macOS and Linux, the signed APK, the bundled `adb`, both guides), and `tools/make-setup-zip.sh`, which zips it |
| `branding/` | logo and icon sources (SVG), PNG exports, generator scripts; see `branding/README.md` |
| `promo/` | source of the explainer video: Remotion project, narration text, voice scripts; see `promo/README.md` |

Build: JDK 21, Gradle 9.8.0 (wrapper), AGP 9.4.1, Kotlin 2.4.20. AGP's built-in Kotlin is used, so `org.jetbrains.kotlin.android` must not be applied. `settings.gradle.kts` resolves from Google's Maven, Google's mirror of Maven Central, and JitPack (only for NewPipeExtractor).

## 2. Modules

```
:app ──► :youtube   YouTube access and Media3 sources; the only user of NewPipeExtractor
 ├─────► :policy    every rule; pure Kotlin/JVM
 └─────► :remote    the phone server and page; knows neither :policy nor :youtube

:mobile ──► :youtube   the phone app: a separate APK of the same family; shares nothing else (not :app)
```

The three libraries do not depend on each other: `:app` maps its models to theirs and enforces every rule itself. The boundaries that hold the design together:

- Only `:youtube` sees NewPipeExtractor, as `implementation`, never `api`. No `org.schabi.*` import exists elsewhere (the one mention in `:app` is a class-name string in the test helper `app/src/test/java/com/ahmadnayfeh/shasha/TestStreams.kt`). Replacing the extractor means editing `:youtube` only.
- `:policy` is pure Kotlin/JVM (`policy/build.gradle.kts` applies `kotlin.jvm`): no Android imports, an injected clock, plain JUnit tests.
- `:remote` defines the HTTP API, the wire format and the `RemoteController` interface; `:app` implements the interface.
- Scope limits from SPEC (for the TV app): no analytics, no Google sign-in, no downloads, no background play. The phone app (`:mobile`, section 12) has downloads and background play by design.
- Suggested videos («الفيديوهات المقترحة», YouTube's related videos) exist since v1.1.1 by the owner's decision. They are a per-profile setting (`Profile.showSuggestions`: on for adults, off for children) and pass the same rules as any list (`PolicyEngine.canSuggest`).

### `:youtube` (`youtube/src/main/java/com/ahmadnayfeh/shasha/youtube/`)

- Contract: `VideoSource.kt` (search, suggestions, channel, channelVideos, channelShorts, channelTab, playlist, stream, related, recentVideos, parseLink, resolveChannelId), `Models.kt`, `PlaybackHandle.kt`, `YouTubeLinkParser.kt`. `YouTube.create()` in `YouTube.kt` builds the implementation.
- `channelTab(channelId, tab, sort, page)` reads one section of a channel's page (`ChannelTab`: videos, Shorts, live, playlists) in one order (`ChannelSort`: latest, popular, oldest), page by page. `ChannelDetails.tabs` says which sections a channel has, and the first page of a section says which orders it offers (`ChannelTabPage.sorts`). The orders come from the order chips of YouTube's own answer, which the extractor does not read: `newpipe/ChannelTabLoader` (the flow), `ChannelChips` (the chips and the answer to a chip's token, pure text) and `BrowseTaps` (how the HTTP layer lets one extractor call see its browse answers). MAINTENANCE.md has the details; a sort that is not offered fails, it is never guessed.
- `related(videoId)` answers from the related items of the `stream()` extraction that playback already made (`newpipe/RelatedCache`, 6 hours); only a video that was not played in that time costs a request of its own.
- Every suspend call runs on `Dispatchers.IO` and throws only `SourceException` (kinds such as `BLOCKED_403`, `BOT_CHECK`, `LOW_QUALITY_ONLY`, `NETWORK`, `EXTRACTION_BROKEN`). Its `technicalName` is the failing exception's class name and appears on the error screen, so R8 must not rename exceptions: `youtube/consumer-rules.pro` keeps them (and `app/proguard-rules.pro` keeps the app's own `*Error` and `*Exception` names), and `android.yml` fails if that stops working.
- `newpipe/` is the only code that imports the extractor: `NewPipeVideoSource`, `NewPipeDownloader` (over OkHttp), `ErrorMapper`, `ItemMapper`, `StreamMapper`, `PageStore` (the opaque `PageKey`), and the channel sections' `ChannelTabLoader`, `ChannelChips` and `BrowseTaps`. Only `:youtube`'s runtime sees NewPipeExtractor's own JSON library (nanojson), so the module reads JSON itself with kotlinx.serialization.
- `playback/`: `YouTubeMediaSourceFactory` turns `StreamDetails` into a Media3 `MediaSource`.
  - An adaptive DASH manifest generated in memory (`PlaybackPlanner`, `DashManifestWriter`), HLS for live.
  - googlevideo request quirks (`GoogleVideoRequests`, `YouTubeHttpDataSource`) and playback error mapping (`PlaybackErrorClassifier`).
  - Stream URLs expire after about 6 hours (`StreamExpiry`); the app extracts again.
- `download/` (added for the phone app): `DownloadOptions.kt` (`StreamDetails.downloadOptions()`: one H.264 MP4 video-only stream per quality, plus the best M4A audio, with their sizes from `clen`) and `StreamDownloader` (fetches a stream in 4 MB ranges shaped like `GoogleVideoQuirks.DASH`, resumable, retried, 403 reported as `BLOCKED_403` so the caller extracts again). `VideoSource.channelPlaylists` (a channel's Playlists tab) was added the same way, with an empty default.
- A video that only offers muxed 360p fails with `LOW_QUALITY_ONLY`, unless the caller passes `allowLowQuality` (child profiles do; MAINTENANCE.md explains why). YouTube's own texts come back in English whatever locale is requested.

### `:policy` (`policy/src/main/kotlin/com/ahmadnayfeh/shasha/policy/`)

- `PolicyEngine.kt` makes every decision: `canShow`, `canPlay`, `canPlayLink` (phone and external links; a `list=` parameter is ignored), `canShowChannel`, `canShowPlaylist`, `canSuggest`, `isShort`, `features(profile)`, `session(...)`, `guardShouldEnforce(...)`, the playback-lock functions and the parent's time grants.
- Data: `Profile.kt` (`Profile`, `Schedule`, `Allowlist`, `Blocklist`), `Candidate`, `Decision` / `DenyReason`, `Features`, `SessionState` / `LockState`, `PlaybackLock`, `Overrides` (the parent's grants), `PolicyConfig` (the adjustable rules, section 4), usage records and summaries (`UsageStats.kt`).
- Security helpers: `PinHasher` (PBKDF2WithHmacSHA256), `PinAttemptLimiter` (lockouts of 5 min, 15 min, 1 h, 4 h by default), `AdultExitChallenge` (long press plus a typed phrase). Also `TextNormalizer` (keyword matching), `UsageStatsCalculator`, `PolicyJson`.

### `:remote`

The phone server, its API and its page: see section 6.

## 3. The `:app` module

Source root: `app/src/main/java/com/ahmadnayfeh/shasha/`. `ShashaApp` creates `AppContainer` (manual dependency injection, lazy, one per process: the shared OkHttp client, `VideoSource`, the Room database, the DataStores, `PolicyEngine`, the repositories, and the guard, remote and updater objects). `MainActivity` hosts `ui/app/ShashaRoot`.

| Package | What lives there |
|---|---|
| `data/` | repositories over Room and DataStore (`SubscriptionRepository`, `FeedRepository`, `WatchRepository`, `ProfileRepository`, `UsageRepository`, `StreamRepository`, ...) and the small stores (`PolicyStore`, `BehaviorSettings`, `PlaybackSettings`, `PlaybackLockStore`, `ParentPinRepository`, `OverridesRepository`); `db/` is Room; `takeout/` imports Google Takeout files |
| `policy/` | glue between the UI and `:policy`: `PolicyGate` (the single door), `SessionController`, `UsageTracker`, `ContentFilter`. It uses the same Kotlin package name as `:policy`: never add a file there named like a `:policy` file |
| `lock/` | the in-app lock (`LockController`, `ExitHold`) and the guard (`GuardService`, `GuardDecider`, `GuardOverlay`, `UsageStatsProbe`, `BootReceiver`, `ProtectionStatus`); section 5 |
| `player/` | Media3 wrapper `PlaybackController`, `PlayerHub` (what the phone can control), `WatchSession`, `PlayedMeter`, play queues |
| `feed/` | WorkManager home-feed sync: every 2 hours, stands down while a video plays, catches up 15 minutes later |
| `remote/` | host of the phone remote: `RemoteService`, `RemoteRuntime`, `TvRemoteController`, `PhonePin`, `PhoneSettings`, `PhoneParental`, `RemoteSettings`, `RemoteDisplay`; section 6 |
| `sponsorblock/` | SponsorBlock client, cache, settings, `SkipController` |
| `search/` | voice search (Arabic or English) and the per-profile search history. The system's voice screen (`RecognizerIntent.ACTION_RECOGNIZE_SPEECH`, launched for a result; on Google TV the remote's microphone reaches only it) is tried first; the in-app `SpeechRecognizer` (`VoiceSearch`, with `RECORD_AUDIO`) is the fallback when no activity answers. With neither (Fire TV, whose Alexa button never reaches apps) the search screen shows no microphone |
| `update/` | the self-updater; section 7 |
| `backup/` | manual JSON backup and restore |
| `ui/` | Compose for TV: `app/` (root, `SessionGate`), `navigation/` (`Screen`, `ProfileNavHost`: one graph for adults, a smaller one for children), `main/` (rail and tabs), `screens/<feature>/`, `settings/` (the two-pane Settings hub), `components/`, `lock/`, `parental/`, `theme/`, `format/` |

Conventions:

- UI text is Arabic, right to left, in `app/src/main/res/values/strings*.xml`. Numbers inside strings use `%1$s` with `n.toString()`, never `%d` (an Arabic system locale would print Arabic-Indic digits). Titles and names inserted into Arabic sentences are wrapped in FSI/PDI (`\u2068` and `\u2069`) so English text keeps its place.
- Everything is reachable with the D-pad and shows a focus state. Each screen owns a `ContentFocus` (`ui/components/ContentFocus.kt`); focusable elements of a Settings pane use `Modifier.paneItem` (`ui/settings/PaneParts.kt`). The child profile has no text input (`Features.textInput`), and PINs and exit phrases use the app's own pad and keyboard, never the system one.
- A channel's page (`ui/screens/channel/`) has the header, the sections row (الفيديوهات · القصيرة · البث المباشر · القوائم: only those the channel has and the profile may see; no Shorts where the profile hides them) and, for the sections that list videos, the order chips (الأحدث · الأكثر مشاهدة · الأقدم: only those YouTube offers that section). `ChannelViewModel` keeps one `PagedLoader` per section and order, read when first picked and kept; every item passes the profile's `ContentFilter` (a channel's playlists open with their owner, so a child may open those of an allowlisted channel). Focus enters on the first card, UP climbs to the chips and the header's buttons, and each chip row takes focus on its selected chip (`ChannelFocusTest`).
- Lists that load page by page (search, a channel's sections, a playlist) use `ui/paging/PagedLoader`. A page is not a screenful (YouTube's «all» search comes through the extractor in pages of 2 to 11 items, and the profile's rules hide more), so it asks for pages until the first screen holds 24 shown items and each scroll brings 12 more, bounded per run, and stops where the source ends.
- A channel's uploads (a child's home through `ui/screens/kids/KidsFeed`, a channel's page through `ChannelViewModel`) are read through `data/ChannelUploads`'s chain, so one way failing on a TV does not blank the screen: the Videos tab, then the channel's long-form uploads playlist («UULF» + the channel id without «UC»: the same list with every duration, 100 a page, and paging goes on from it), then RSS only where no duration is needed (a profile that hides Shorts never shows a video whose duration is unknown, `ContentFilter.needsDuration`). Every failure is logged (`adb logcat -s ChannelUploads KidsHome ChannelViewModel AllowedChannels`); only a channel that fails everywhere counts as failed, and the unified error shows when every channel did.
- Any `SourceException` that reaches the viewer is shown by the one unified error screen (`ui/screens/error/ErrorScreen.kt`): what happened, the extractor version, the technical name, and "check for updates".
- UI code never re-implements a rule: lists, plays and profile switches go through `PolicyGate`.

## 4. Data storage

**Room**: `data/db/ShashaDatabase.kt`, file `shasha.db`, version 3, schemas exported to `app/schemas/`.

- Tables: `subscriptions`, `feed_items` (home-feed cache), `watch_progress`, `history`, `watch_later`, `profiles` (each profile's rules as `PolicyJson`), `usage_records`, `surrender_events`, `sponsor_segments` (SponsorBlock cache).
- **Every schema change bumps the version and ships a migration** (`AutoMigration` or manual): a TV that has an older build installed would otherwise fail Room's identity check when it opens the file. `MigrationTest` opens an old file.

**DataStore** (preferences; one file per concern; `AppContainer` creates each once; a damaged file starts empty instead of crashing):

| File (`<name>.preferences_pb`) | Class | Holds |
|---|---|---|
| `policy` | `data/PolicyStore.kt` | current profile id, parent PIN hash, PIN-attempt state of the TV and of the phone, the parent's grants (`overrides`), the playback lock |
| `behavior` | `data/BehaviorSettings.kt` | every adjustable behaviour (`Behavior`, keys in `BehaviorKeys`) |
| `playback` | `data/PlaybackSettings.kt` | highest quality (default 1080p) |
| `sponsorblock` | `sponsorblock/SponsorBlockSettings.kt` | skipped categories |
| `remote` | `remote/RemoteSettings.kt` | on/off, pairing token, port, whether a phone reached the server with the current token (`phone_paired`, v1.2; forgotten with a new token, never backed up) |
| `search` | `search/SearchHistory.kt` | recent searches per profile |
| `guard` | `lock/GuardReturnTimes.kt` | the last 10 guard return times |

`BehaviorSettings` is the single source for what the parent can adjust. The TV's Settings and the phone page both edit it, and `data/SettingsReset.kt` resets it. It holds:

- Serious Mode («الوضع الجاد»): since v1.2 two independent switches, one for the children's profiles (`serious_mode_children`) and one for the adult's (`serious_mode_adult`), both on by default; v1.1's single `serious_mode` key is moved into both once (`migrateSeriousMode`). Also the adult's exit (on/off: turning it off on the TV needs a paired phone, `setAdultEscape(on, phonePaired)`), its hold and phrases, and the skip allowance.
- Time and the parent PIN: warning time, extra-time choices, PIN lockout, relock delay, and whether entering the adult profile asks for the PIN.
- Playback and children: default speed, seek step, auto-play countdowns, SponsorBlock timings, the Shorts limit; and the voice-search language.

`values` is a `StateFlow<Behavior>`; `policyConfig()` feeds `PolicyEngine` and `pinLockout()` feeds the PIN limiters.

**Backup**: `backup/BackupManager.kt` writes and reads one JSON file (`FORMAT` 3), from Settings («الاستيراد والتصدير») or from the phone. Older files are still read: a format-2 file's single Serious Mode switch sets both switches (`SettingsSection.upgrade`, `BehaviorSettings.upgradeBackup`).

- Kept: profiles, the PIN hash (never the PIN), grants, subscriptions, watch later, history, progress, usage, surrenders, and the `behavior`, `playback`, `sponsorblock` and `remote` settings (on/off and port, never the token).
- Not kept: the feed and SponsorBlock caches, the home's suggestions cache (`data/HomeSuggestions.kt`, `home_suggestions.json` in the cache directory), the playback lock, PIN lockouts, search history, the pairing token. Each profile's Shorts and suggestions choices are part of the profile and are kept.
- Import validates everything first and replaces the tables in one Room transaction. A setting that should survive a restore must be listed in its store's `backupSection`.

## 5. Protection: rules, time, lock and guard

- **Rules.** `PolicyEngine` decides; `policy/PolicyGate.kt` in `:app` is the single door.
  - Lists are filtered through `PolicyGate.filter` and its `visible*` functions.
  - Every play goes through `checkPlay(videoId, origin)` (origin `Navigation`, `Playlist` or `Link`) before anything is extracted, and again before a released player restarts. Phone links go through `checkLink`; profile switches through `switchProfile`, which asks for the PIN where needed.
  - A child sees only allowlisted channels and playlists, has no search tab, and may have Shorts hidden.
  - Shorts (`PolicyEngine.isShort`: flagged, a `/shorts/` link, or at most the Shorts limit long) are hidden everywhere or shown everywhere with the «Shorts» badge, which uses the same test. Suggestions (`PolicyGate.visibleSuggestions`) go through the same rules; a child's are off unless the parent turns them on, and then come only from its allowed channels.
- **Time.** `SessionController` recomputes the `SessionState` (Ok, Warning, TimeUp, OutsideSchedule) every 15 seconds and on every change.
  - `UsageTracker` adds up foreground time, which is what the daily quota counts (only while the app is visible, a profile's screens are shown and the session counts toward the quota), and playback time per channel for the stats.
  - `ui/app/SessionGate.kt` replaces every screen with `TimeBlockScreen` when watching is not allowed. Only the parent PIN (on the TV or the phone) gives time back, as `Overrides` in `PolicyStore`.
- **In-app lock (Serious Mode).** When a video starts, `lock/LockController.kt` stores a `PlaybackLock` in `PlaybackLockStore`, so killing the app or rebooting keeps it.
  - While it exists BACK does nothing, nothing else can start, and `KeepLockedVideoOnScreen` (`ui/lock/LockedNavigation.kt`) forces the player destination.
  - A video that really plays to its end releases it (`PlayedPart.endedByItself`: seeking to the end does not count).
  - A child leaves with the parent PIN on the TV (the PIN pad on BACK, `LockController.unlockWithPin`, or the picker with the PIN, `PolicyGate.switchProfile`). The adult holds BACK or OK (10 seconds by default, `lock/ExitHold.kt`) and types a phrase (`AdultExitChallenge`); that is recorded as a "surrender" in the stats. The PIN never ends the adult's lock. The parent can release any lock from the phone.
  - v1.2: each kind of profile has its own switch (`SeriousMode` in `PolicyConfig`; `PolicyEngine.lockFor` reads the current profile's kind). Turning a switch off ends the lock of that kind only (`LockController`'s `seriousMode` flow, `SeriousMode.holds`).
  - v1.2: a video that would start locked while nothing on the TV could end it early (the adult's exit turned off: `PolicyEngine.tvWayOut` is null, `needsPhoneConfirmation`) first waits for the parent's «تأكيد» on the phone. The player asks (`StartConfirmation` → `PhoneStartConfirmation`, `PlayerViewModel.Load.AwaitingPhone`, `ui/screens/player/PhoneConfirmPrompt.kt`); `remote/PhoneConfirmations.kt` holds the question two minutes; the phone answers with `POST /api/parent/confirm`. «رفض», the time or «إلغاء» on the TV: nothing plays. With no phone there (`remote/PhoneReach.kt`: none paired, or none polled in the last minute) the prompt says how to pair one. A video the phone itself sent (`Screen.Player.phoneApproved`) and a video the lock already holds ask nothing.
- **Guard outside the app** (`lock/GuardService.kt`), best effort.
  - A foreground service (`specialUse`, `START_STICKY`) started by `ShashaApp`, `MainActivity` and `lock/BootReceiver` (after boot and after an update). It runs only while both permissions that the installers in `Shasha-Setup/` grant over ADB are present (`ProtectionChecks.guardCanRun`: `SYSTEM_ALERT_WINDOW` and usage access).
  - `guardDemand(...)` (`lock/GuardDemand.kt`) feeds `PolicyEngine.guardShouldEnforce`: true while a video is locked, time is up, it is outside the schedule, or a child profile is current and the parent PIN has not let it out (`AppContainer.childLetOut`).
  - While it enforces and Shasha is not in front, the service reads the app in front through `UsageStatsProbe` (every second, faster right after Shasha left), and `GuardDecider` decides what to do: cover that app with `GuardOverlay` and start `MainActivity` again.
  - Return times are kept by `GuardReturnTimes` and shown under Settings → «حالة الحماية» (`ui/screens/settings/ProtectionScreen.kt`). What the guard cannot stop is listed in MAINTENANCE.md, «الحارس وحدوده».

## 6. The phone remote

- **`:remote`** (`remote/src/main/java/com/ahmadnayfeh/shasha/remote/`): `RemoteServer` is a Ktor CIO server bound to one address (the wildcard is refused). `RemoteRoutes.kt` holds the HTTP API, `RemoteController.kt` is the interface behind it (everything the phone can ask for) and `RemoteModels.kt` is the JSON wire format shared with the page. Also `PinGate`, `ParentSessions`, `RemoteTokens`, `QrCodes`, `LanAddress`, `NsdAdvertiser`, `TakeoutFiles`.
- **The page**: `remote/src/main/resources/web/index.html`, one self-contained Arabic RTL file (there is no build step); `unpaired.html` is shown when it is opened without the token. `WebPage.kt` loads them and builds a Content-Security-Policy that allows only the page's own inline script (by its SHA-256, computed at load), so editing the script needs no other change. No CORS headers; `Referrer-Policy: no-referrer`.
- **Host in `:app`** (`app/src/main/java/com/ahmadnayfeh/shasha/remote/`):
  - `RemoteService` (foreground service of type `connectedDevice`; runs while the remote is on and a parent PIN exists; started by `ShashaApp`, `MainActivity` and `RemoteBootReceiver`) drives `RemoteRuntime`. It binds only the TV's LAN IPv4 (`LanAddress.find()`) on its remembered port (8765 at first), else 8765 to 8774, else any free port; rebinds when the network changes; and announces over NSD.
  - `TvRemoteController` implements `RemoteController` on top of `PolicyGate`, `SessionController`, `LockController`, `ParentalLists`, `SubscriptionRepository`, `BackupManager` and `PlayerHub`. `TvDisplay` brings the app to the front and navigates (`ui/navigation/NavigationRequests.kt`).
  - `PhoneSettings`, `PhoneParental` and `RemoteRevision` let the page edit settings, profiles, the household blocklist and stats with the TV's own stores and checks.
- **Token and pairing**: a random 32-character URL-safe token (`RemoteSettings.token()`, kept in the `remote` DataStore). The QR code on the TV (`ui/settings/remote/PairingQr.kt`) encodes `http://<ip>:<port>/?t=<token>`. Every request, `/` included, must carry the token as `?t=` or `Authorization: Bearer` (compared in constant time); the page then keeps it in localStorage. "Regenerate token" in Settings (behind the PIN) unpairs every phone at once.
- **Parent session**: `POST /api/parent/login` with the PIN returns a session id that the page sends as `X-Parent-Session`; every `/api/parent/*` route needs it. Sessions live in memory only (30 minutes sliding, at most 8) and are revoked when the PIN or the token changes.
- **PIN brakes**: `PinGate` in `:remote` (memory only; after a few wrong PINs in a row, five by default, every PIN check answers 429 for 30 seconds, doubling up to 1 hour) sits in front of `PhonePin` in `:app` (its own `PinAttemptLimiter`, saved as `phone_pin_attempts`, so a reboot grants no new guesses). The phone's count is separate from the TV's: a child's wrong guesses on the TV never lock the parent's phone. All paired phones share one count.
- **Rules apply on the phone too**: links go through `PolicyGate.checkLink` (only the video's own channel counts); while a video is locked the phone is refused STOP, new links, profile switches and restores until the parent unlocks the video; a child profile has no search.
- **A link never plays by itself (v1.2)**: `POST /api/play` checks the rules (a refusal answers at once and the TV shows nothing), then `remote/PhonePlayRequests.kt` holds the link and `ui/components/PlayRequestOverlay.kt` asks «طلب تشغيل من الجوال» over whatever is on screen (focus on «قبول», BACK is «رفض», rejected by itself after 30 s; one at a time, a newer link replaces it). Only «قبول» opens it, after the rules are asked again; the page follows the request through `status.playRequest` (PENDING, ACCEPTED, REJECTED, TIMED_OUT, FAILED). A request is dropped when the TV leaves its profile or a video gets locked (`AppContainer.playRequests`).
- **Presence (v1.2)**: `PhonePresence` in `:remote` notes when a request with the token last came; `RemoteRuntime` marks the phone paired (`RemoteSettings.markPhonePaired`) and `TvPhoneReach` answers "paired" and "there now" (a poll in the last 60 s).

## 7. The updater

Code in `app/src/main/java/com/ahmadnayfeh/shasha/update/`, screen in `ui/screens/updates/`. Settings → «فحص التحديثات» is one button (and, at the bottom of the same pane, «معلومات تقنية والتراخيص»: SponsorBlock's attribution and the libraries' licences, `ui/settings/TechnicalInfo.kt`); the same screen opens from the error screens. One press does everything, with no second button:

1. `UpdatesViewModel` checks as soon as the screen opens. `GitHubReleases.latest()` reads `repos/<UPDATE_REPO>/releases/latest` (`BuildConfig.UPDATE_REPO`) with a read-only token (`BuildConfig.UPDATE_TOKEN`; the repository is private, so the texts never name or point to GitHub). `ReleaseParser` expects the tag `v<versionName>-<versionCode>` and takes the TV's asset `shasha-<versionName>-<versionCode>.apk`; the phone app's `shasha-mobile-<versionName>-<versionCode>.apk` rides in the same release and is never taken (nor a debug build).
2. The same installed version or older: «لديك أحدث إصدار». Newer: `Updater.download()` starts at once (progress, «إلغاء») into `cacheDir/updates`. The token goes only to the GitHub API host; the redirect to storage is requested without it. A build without a token, or without the install permission, says so in plain words (`TokenMissing`, `InstallNotAllowed`) and sends the viewer to the installer kit.
3. `ApkVerifier.refusal()` checks the file before the system installer sees it: same package, a higher `versionCode`, and exactly the installed app's signing-certificate SHA-256 set. Android refuses an update signed with another key, so the release key must never change. `ApkInspector` reads certificates and version codes the way each API level offers (`signingInfo` and the long version code from API 28, `signatures` and `versionCode` below), so every call in `update/` is safe from API 25.
4. The screen says «سيُغلق التطبيق لحظة ثم يُفتح من جديد», then `UpdateInstaller` hands the APK to a `PackageInstaller` session. From Android 12 the session asks for no user action (`setRequireUserAction(USER_ACTION_NOT_REQUIRED)`; the manifest declares `UPDATE_PACKAGES_WITHOUT_USER_ACTION`; `REQUEST_INSTALL_PACKAGES` is the app-op the installer in `Shasha-Setup/` grants; the app updates itself and targets a recent SDK), so the system installs without a question. Where it still asks (before Android 12, or when the system insists) `STATUS_PENDING_USER_ACTION` arrives as `InstallEvent.Confirm` and the screen opens the system's window. A child profile only sees a notice.
5. After the update the process is gone. `RelaunchAfterUpdate` (a note on disk, valid 10 minutes, written just before the hand-over) lets `AppUpdatedReceiver` (`ACTION_MY_PACKAGE_REPLACED`, unexported) start `MainActivity` in the new version. An update the app did not start (the kit's reinstall) opens nothing. Starting an activity from the background is free before Android 10 and, from Android 10, allowed for an app with «display over other apps» (`SYSTEM_ALERT_WINDOW`), which the kit grants and the guard relies on for the same intent; where a system refuses anyway, the TV simply stays on its home screen.

The token reaches the build as CI secret `UPDATE_TOKEN`, then environment variable `SHASHA_UPDATE_TOKEN`, then `buildConfigField` in `app/build.gradle.kts`; empty means the screen says updates are not available in this copy. It ends up inside the APK, so it must stay read-only (Contents: read, this repository). Renewing it, and what to do if the signing key is lost, are in MAINTENANCE.md.

## 8. CI (`.github/`)

- **`workflows/android.yml`**: runs on a push to any branch (not docs-, kit-, branding- or promo-only changes), on pull requests, Fridays 03:17 UTC and manually.
  - `test`: `:policy:test :youtube:testDebugUnitTest :remote:testDebugUnitTest :mobile:testDebugUnitTest :app:verifyRoborazziDebug`.
  - `build`: R8 release APK, signed when the four `SIGNING_*` secrets exist (mandatory on `main`). It checks that R8 kept the exception names, the package, `versionCode` (1000 + the run number) and the signing certificate. It then builds the phone app (`:mobile:assembleRelease`) with the same key and `versionCode` and runs the same checks on it.
  - `release` (signed builds of `main` only): a GitHub Release tagged `v<versionName>-<versionCode>` holding `shasha-<versionName>-<versionCode>.apk` (the TV app, which its updater reads) and `shasha-mobile-<versionName>-<versionCode>.apk` (the phone app, which its own updater reads).
- **`workflows/youtube-canary.yml`**: Tuesday and Friday 04:41 UTC, manually, and on pull requests touching `youtube/**`, `gradle/libs.versions.toml` or the workflow. It runs the real-network tests (`:youtube:testDebugUnitTest --tests '*RealNetwork*' -Dyoutube.realNetwork=true`, one retry) and prints `REAL-RESULT` lines in the run summary. Red means YouTube probably changed something.
- **`workflows/voice.yml`**: a push that touches `promo/voice/request.json`, or manually. `promo/voice/run.py` generates narration and voice samples with ElevenLabs (secret `ELEVEN_LABS`); the audio is uploaded as the `voice-output` artifact.
- **`dependabot.yml`**: weekly, Thursday: version PRs for the catalog and the pinned Actions. NewPipeExtractor always gets its own PR.

Secrets: `SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`, `UPDATE_TOKEN`, `ELEVEN_LABS`. The contract between `android.yml` and `app/build.gradle.kts` (the `SHASHA_*` environment variables) is written at the top of `android.yml`; setting the secrets up is in MAINTENANCE.md.

## 9. User guide and promo video

- **`docs/guide/`**: `guide.html` (Arabic) and `en/guide.html` (English) share `guide.css`, `img/` (JPG copies of the app's screenshots) and `qr/` (QR codes of the author's other apps).
  - `bash docs/guide/build.sh` renders both with Playwright's Chromium (Node 18+, `playwright-core` outside the repository) into `docs/دليل-شاشة.pdf` and `docs/Shasha-Guide.pdf`, and copies them into `Shasha-Setup/`. It fails when a page overflows its A4 sheet.
  - `--images` first remakes `img/` from the screenshot baselines (`prepare_images.py`, needs Pillow). The install photos are `docs/images/install/`. `make_qr.py` redraws `qr/` and is needed only when a link changes.
  - `--pages <dir>` also writes each guide as 1280 px WebP pages (`<dir>/{ar,en}/page-NN.webp`): the TV app bundles them from `app/src/main/assets/guide/`, so after any change to a guide run `bash docs/guide/build.sh --pages app/src/main/assets/guide`, keep `PAGES` in `GuidePagesTest` equal to the page count (24 in 1.2, ten parts), and record the `guide_ar`, `guide_en` and `guide_ar_turned` baselines again.
  - Part 8 (the phone app) shows three of `mobile/src/test/screenshots/` (the light ones: `phone_home`, `phone_player`, `phone_downloads`), scaled to 480 px by `prepare_images.py` and shown in `phone` frames (`phone-trio` in `guide.css`). To use others, change the three `img` names in both guides and run `build.sh --images`.
  - Then `bash tools/make-setup-zip.sh` rebuilds `Shasha-Setup.zip` and refuses an incomplete kit.
- **`promo/`**: `narration/` (the text in three forms per scene, and the final audio), `tts/` (voice generation and checks), `remotion/` (the Remotion 4 scenes that render the 1080p video, which is compressed into `docs/video/shasha-explainer.mp4`), `timeline.json`, and `voice/` (what `voice.yml` runs). Screenshots are copied in from the two baseline folders by `remotion/sync-screens.sh`. Step by step: `promo/README.md` (Arabic).

## 10. Tests and screenshot baselines

| Module | Tests | Run |
|---|---|---|
| `:policy` | `policy/src/test/kotlin/`: one file per rule family (`QuotaTest`, `ScheduleTest`, `ShortsTest`, `LockStateTest`, ...) | `./gradlew :policy:test` |
| `:youtube` | `youtube/src/test/java/` (Robolectric for playback) | `./gradlew :youtube:testDebugUnitTest` |
| `:remote` | `remote/src/test/java/` (routes, auth, PIN gate, a real socket) | `./gradlew :remote:testDebugUnitTest` |
| `:app` | `app/src/test/java/` (Robolectric and Compose; mirrors the package layout) | `./gradlew :app:verifyRoborazziDebug` (what CI runs) |
| `:mobile` | `mobile/src/test/java/` (JVM, and Robolectric for the screens and the activity; the screenshot tests are in `.../screenshots/`) | `./gradlew :mobile:testDebugUnitTest` (CI), `:mobile:verifyRoborazziDebug` (with the screenshots checked) |

- Tests that talk to the real YouTube or SponsorBlock (`RealNetwork*`, `FeedSyncRealNetworkTest`, `SponsorBlockRealNetworkTest`) are skipped unless `-Dyoutube.realNetwork=true`; they print `REAL-RESULT` lines.
- **TV screenshot baselines**: `app/src/test/screenshots/*.png`, written by Roborazzi. `:app:verifyRoborazziDebug` fails when a screen no longer matches its PNG; after an intended UI change run `./gradlew :app:recordRoborazziDebug` and review the diff. The guide and the video reuse these images.
- **Phone-app screenshot baselines**: `mobile/src/test/screenshots/phone_*.png` (Arabic, 411 x 891 dp, light, and `_dark`: home, search, player, downloads, settings, about), written by Roborazzi from `PhoneScreenshotTest.kt` over fake Arabic data and no network: the real screens and database inside `ScreenshotApp`/`ScreenshotContainer` (a subclass of the open `AppContainer` with a fake `VideoSource` and a download manager that never finishes), pictures drawn by `Samples.picture` through Coil's preview handler, and the player screen's `WatchContent` over a `FakePlayer` and a `FakeDetails`. `:mobile:verifyRoborazziDebug` fails when a screen no longer matches its PNG; after an intended change run `./gradlew :mobile:recordRoborazziDebug` and review the diff. CI runs `:mobile:testDebugUnitTest`, which only renders them (switch the step to `verifyRoborazziDebug`, as for `:app`, to check them there). The guide and the video reuse these images.
- **Phone-page screenshots**: `remote/src/test/screenshots/*.png`. No Gradle test compares them; they come from driving the page in a browser against `remote/src/test/java/com/ahmadnayfeh/shasha/remote/qa/QaServer.kt` (demo data). `app/src/test/java/com/ahmadnayfeh/shasha/remote/PhonePageQaServer.kt` serves the page over the app's real rules (it needs `SHASHA_QA_DIR`).
- Tests of code that must work below the app's newest Android (the updater, from API 25) list the old level too, `@Config(sdk = [25, 35])`, and run under `FloorAwareRobolectricRunner` (`app/src/test/java/com/ahmadnayfeh/shasha/`): Robolectric cannot start at an SDK below the app's `minSdk` (it parses the merged manifest), so the runner leaves such levels out until `minSdk` reaches them.
- `app/schemas/` holds the exported Room schemas that `MigrationTest` uses.

## 11. Where to change what

| To | Start at |
|---|---|
| change a rule (allow or block, Shorts, quota, schedule, lock, PIN) | `policy/.../PolicyEngine.kt` and its test; the app side is `policy/PolicyGate.kt` |
| add a parent setting | `Behavior` and `BehaviorKeys` in `data/BehaviorSettings.kt`; its pane in `ui/settings/BehaviorPanes.kt`; the phone page reads the same keys through `remote/PhoneSettings.kt` |
| add a screen | `ui/screens/<feature>/`; its route in `ui/navigation/Screen.kt`, registered in `ui/navigation/ProfileNavHost.kt` (adult-only routes in `adultDestinations`) |
| change the database | `data/db/ShashaDatabase.kt`: bump `version`, add the migration, commit the new `app/schemas/.../<n>.json` |
| change what the phone can do | `RemoteController.kt`, `RemoteModels.kt` and `RemoteRoutes.kt` in `:remote`; `TvRemoteController` in `:app`; then `web/index.html` |
| change a channel's sections or their orders | the source side: `youtube/.../newpipe/ChannelTabLoader.kt` (and `ChannelChips.kt`); the page: `ui/screens/channel/ChannelViewModel.kt` (what is read and shown) and `ChannelScreen.kt` (the chips and the grid) |
| react to a YouTube change | `youtube/` only (`newpipe/`, `playback/`, `download/`), after reading MAINTENANCE.md |
| change the phone app | `mobile/` (section 12); a new screen is a route in `ui/nav/Routes.kt` and a `composable<>` in `ui/ShashaRoot.kt`; a new text goes in `values/strings.xml` and `values-ar/strings.xml` (`StringsParityTest` checks both) |

## 12. The phone app (`:mobile`)

A second app of the family, for one phone: `com.ahmadnayfeh.shasha.mobile`, minSdk 26, Compose Material 3, Arabic and English (`values/` English, `values-ar/` Arabic; the app's own language setting overrides the phone's through `MainActivity.attachBaseContext`), light and dark. It depends on `:youtube` only (small helpers were copied from `:app`, never shared). Source root `mobile/src/main/java/com/ahmadnayfeh/shasha/mobile/`; manual dependency injection in `AppContainer`, like the TV app.

| Package | What lives there |
|---|---|
| `data/` | Room (`db/`: subscriptions, feed cache, history, watch later, resume positions, downloads, SponsorBlock cache, recent searches; schemas in `mobile/schemas/`), `AppSettings` (a Preferences DataStore with a reset per group), the repositories |
| `player/` | `PlayerController` (one ExoPlayer per process: stream extraction, `YouTubeMediaSourceFactory`, quality overrides, speed, resume, SponsorBlock ticker) and `PlaybackService` (a `MediaSessionService` around that same player: notification, lock screen, background audio) |
| `sponsorblock/` | the TV app's client, cache and `SkipController`, ported; defaults are sponsor, self-promotion and interaction |
| `download/` | `DownloadManager` (one download at a time, rows in Room, restart-safe), `DownloadService` (foreground service with the progress notification), `MediaMuxerRemuxer` over `SampleInterleaver` (merges DASH video-only MP4 and M4A audio without re-encoding), `MediaStoreSaver` (Movies/Shasha, Music/Shasha) |
| `update/` | the same model as the TV's updater (read the latest Release with the built-in read-only token, verify package, versionCode and signing certificate, `PackageInstaller`), reading the asset `shasha-mobile-<versionName>-<versionCode>.apk` and taking the version from its name, because the release's tag follows the TV app's version |
| `ui/` | `ShashaRoot` (theme, bottom tabs, type-safe navigation), one package per screen, `ActivityBridge` (rotation, system bars and picture-in-picture, which belong to the activity) |

Choices worth knowing: leaving the player screen stops playback, leaving the app keeps the sound going (Settings can turn that off); a new video from the player replaces the current one in the back stack, and a player screen that comes back to the front after a detour (video A, its channel, video B, back, back) plays its own video again (`WatchViewModel.onShown`); the Watch route is the only one without the bottom bar; downloads look the streams up again when they start, since the URLs of a queued one expire; user texts never name where the updates come from.

Tests (`./gradlew :mobile:testDebugUnitTest`, JVM and Robolectric): view models over in-memory DAOs and a fake `VideoSource` (`FakeDaos.kt`, `TestSupport.kt`), repositories on a real in-memory Room, the download manager with fakes for the extractor, fetcher, remuxer and MediaStore, `SampleInterleaver`, the updater, settings on a real DataStore, the ported SponsorBlock tests, and string parity between the two languages. `ui/ShashaRootTest.kt` composes the whole app on Robolectric in English and in Arabic (tabs, Library, Settings, About, the update page, right-to-left order) `MainActivityTest.kt` runs the real activity (the app's language over the phone's, a notification opening Downloads) and `WatchOfflineTest.kt` opens a saved video in the player screen with the real ExoPlayer (a file that is not a video ends in the error state). `./gradlew :mobile:lintDebug` is clean apart from the deliberate `targetSdk 35` note (CI does not run lint). What only a device can run (playing real media, `MediaMuxer`, MediaStore, the services, picture-in-picture, gestures) is not covered by tests.

CI (`android.yml`, section 8): the `test` job also runs `:mobile:testDebugUnitTest`; the `build` job builds the phone APK with the same key and version code, checks that R8 kept its exception names (`mobile/proguard-rules.pro`), and uploads `shasha-mobile-<versionName>-<versionCode>.apk` as its own artifact; the `release` job attaches it to the same Release as the TV's APK. No file name travels between jobs as an output (the key alias is the secret "shasha", and GitHub drops an output that contains a secret): only the phone's `versionName` does. The TV installers in `Shasha-Setup/` take the newest `shasha-*.apk` next to them, a pattern that also matches the phone's file: keep the phone APK out of that folder.
