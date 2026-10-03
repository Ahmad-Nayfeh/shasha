# Shasha on TVs: what it runs on

Written for the guide writer and for whoever maintains the installers. Shasha is an ordinary Android app installed
from a computer over the home network (the kit in `Shasha-Setup/`), so it runs wherever the TV's system is Android
7.1 or newer and accepts ADB over the network.

## In short

| Runs | Does not run |
|---|---|
| Google TV and Android TV, Android 7.1 or newer, any brand: Sony, TCL, Hisense, Philips, Xiaomi, Nvidia Shield, Chromecast with Google TV, Google TV Streamer, projectors (XGIMI, Nebula and others) | Samsung (Tizen), LG (webOS), Roku and Roku TVs, Apple TV |
| Amazon Fire TV with **Fire OS 6 or newer** (6, 7, 8, 14): Fire TV Sticks, Fire TV Cube, Fire TV smart TVs (Amazon Omni and 2/4-Series, Toshiba, Insignia, Hisense, TCL, Xiaomi, Panasonic, JVC, Grundig) | Fire TV models with **Vega OS**: Fire TV Stick 4K Select (2025), Fire TV Stick 4K 3rd gen (2026), Fire TV Stick HD (2026) |
| | Fire OS 5 (Android 5.1): Fire TV Stick 2nd gen and Basic Edition, Fire TV 1st and 2nd gen, Element 4K Fire TV (2017) |
| | TVs whose own system is not Android: Hisense VIDAA, Philips Saphi or Titan OS, TCL Roku TV, Panasonic My Home Screen |

For the TVs on the right: an Android TV, Google TV or Fire TV (Fire OS, not Vega OS) stick on an HDMI port.

**Same name, different system.** «Fire TV Stick HD» of 2024 is Fire OS 7 (works); the one of 2026 is Vega OS (does not).
Check the box or the shop page for «Fire OS», or on the TV: Settings > My Fire TV > About. Brands such as Hisense, TCL and
Philips sell both Android or Google TV sets and sets with other systems: the system's name is on the box and in the TV's
About screen.

## Android versions

`minSdk` is **25** (Android 7.1), `targetSdk` 35.

| System | Android | API | Example devices |
|---|---|---|---|
| Fire OS 6 | 7.1 | 25 | Fire TV Stick 4K 1st gen (2018, AFTMM), Fire TV Cube 1st gen (AFTA), Fire TV 3rd gen (AFTN), Fire TV Edition sets 2018-2020 |
| Fire OS 7 | 9 | 28 | Fire TV Stick 3rd gen, Lite, HD (2024), 4K Max 1st gen, Cube 2nd and 3rd gen, most Fire TV sets 2019-2023 |
| Fire OS 8 | 10 / 11 | 30 | Fire TV Stick 4K and 4K Max 2nd gen (2023), Fire TV Stick 4K Plus (2025), Fire TV sets 2024-2025 |
| Fire OS 14 | 12-14 | 34 | Fire TV sets of 2026 (Hisense U7, TCL QM64L) |
| Android TV / Google TV | 7.1 to 14 | 25-34 | everything above Android 7.0; Google TV itself starts at Android 10 |

Amazon's table of every Fire TV model, with its `ro.product.model` code (AFT…) and Fire OS version:
<https://developer.amazon.com/docs/device-specs/identify-fire-tv-devices.html>.

### Why 25, and why not lower

- **Libraries.** The highest `minSdkVersion` among the dependencies' manifests is 24 (WorkManager 2.12, Navigation
  2.10). Compose 1.12, tv-material 1.1, Media3 1.11, Room 2.8, DataStore 1.2, Coil 3.6, Activity 1.13, Core 1.19 and
  Lifecycle 2.11 declare 23; core-splashscreen 21. Ktor (the phone server, CIO) and NewPipeExtractor are plain JVM
  libraries; Ktor's server sockets use NIO channel methods that Android has from API 24. Java 8+ APIs that Android 7.1
  lacks (`java.time`, `java.util.Base64`, `java.nio.file`, used by us and by NewPipeExtractor) are backported by core
  library desugaring (`desugar_jdk_libs_nio` 2.1.5 rewrites `java.util.Base64` for API 25 and lower).
- **25 = Fire OS 6**, which brings the Fire TV Stick 4K of 2018 and the first Fire TV Cube, still common.
- **Below 24 is not possible** without replacing WorkManager, Navigation and the phone server. That rules out Fire OS 5
  (API 22). API 24 (Android TV 7.0, a few 2016-2017 sets) was not evaluated.

### What Android 7.1 lacks, and how the app copes

Found with `:app:lintDebug` at `minSdk = 25` (NewApi, 11 errors) plus what lint cannot see (a crypto algorithm name):

| Missing below | What | Where | Now |
|---|---|---|---|
| 8 (26) | Notification channels | `lock/GuardService`, `remote/RemoteService` | `NotificationChannelCompat` (no-op below 8) |
| 8 (26) | `TYPE_APPLICATION_OVERLAY` | `lock/GuardOverlay` | `TYPE_PHONE` below 8 |
| 8 (26) | Per-app "install unknown apps" (`canRequestPackageInstalls`, the `REQUEST_INSTALL_PACKAGES` app-op) | `lock/ProtectionStatus`, installers | the TV-wide «Unknown sources» setting (`Settings.Secure.INSTALL_NON_MARKET_APPS`); the installers turn it on and `--undo` turns it off; «حالة الحماية» explains it |
| 8 (26) | `PBKDF2WithHmacSHA256` in the system's crypto provider | `:policy` `PinHasher` | the same PBKDF2 computed over `HmacSHA256` when the algorithm is missing; identical hashes (tests compare with the JVM's own implementation, Python and RFC 7914) |
| 8 (26) | `android:font` attributes in font XML | `res/font/plex_arabic.xml` | `app:` attributes added for AndroidX |
| 9 (28) | `PackageInfo.signingInfo`, `getLongVersionCode` | `update/ApkVerifier` | **handled by the updater work**: `GET_SIGNATURES` and `versionCode` below 28 |
| 8 (26) | `SessionParams.setInstallReason` | `update/UpdateInstaller` | **handled by the updater work**: skipped below 26 |

## Fire TV specifics

- **No Google Play services**: Shasha uses none. Its only network peers are YouTube (through NewPipeExtractor),
  SponsorBlock and GitHub.
- **Launcher**: Fire TV shows apps that declare `LEANBACK_LAUNCHER` (Shasha also declares `LAUNCHER`) with their
  banner. After installing: Home > the apps row («Your Apps», «See all») > Shasha; or Settings > Applications > Manage
  Installed Applications > Shasha > Launch application. The installer also starts it.
- **Manifest**: touchscreen, leanback, Wi-Fi and microphone are declared not required, so no device is refused for
  lacking one; landscape only.
- **Voice**: Fire TV has neither the system voice screen (`RecognizerIntent`) nor a recognition service for apps, and
  the Alexa button never reaches apps. The search screen then shows no microphone (`VoiceSearch.availability`); search by
  typing or from the phone page. «تشخيص» says which voice the TV offers.
- **Guard**: `appops set … SYSTEM_ALERT_WINDOW allow` and `GET_USAGE_STATS allow` work over ADB on Fire OS (signage apps
  use them on Fire OS 8), but Amazon does not officially support apps drawing over other apps. On a Fire TV, «حالة
  الحماية» says so and asks to try it once: enter a kid's profile, press Home, and Shasha should come back by itself.
  The lock inside the app does not depend on it.
- **The official YouTube app** is `com.amazon.firetv.youtube` («YouTube for Fire TV»). The installers find it and can
  disable it like Google's `com.google.android.youtube.tv`; «حالة الحماية» shows it and its command.
- **Fire OS 6**: «Install updates» is the TV-wide «Apps from Unknown Sources» (Developer options); the installer turns it
  on, since nothing else lets «فحص التحديثات» open the system installer there.
- Amazon stopped apps from using the device's own ADB (Fire OS 7.6.6.9 and 8.1.0.3). That concerns apps on the TV
  only; a computer on the network still connects.

## What was tested

Without a TV (this machine has no Android device or emulator):

- `LowestSdkSmokeTest` under Robolectric at **API 25**: the guard service starts in the foreground without notification
  channels and stops when its permissions are missing; the cover's window type is `TYPE_PHONE`; «install updates» follows
  Unknown sources and its command; the parent PIN hashes and checks; the protection screen shows the Android 7 and Fire TV
  texts and the Fire TV YouTube command.
- `PinHasherTest`: the fallback PBKDF2 against Python's `hashlib`, RFC 7914 and the JVM's `PBKDF2WithHmacSHA256` (20
  random PINs, salts and iteration counts).
- `:app:lintDebug` at minSdk 25: only the four updater errors listed above remain in this branch.
- Existing tests rerun: `GuardServiceTest`, `VoiceResultsTest`, `StringsParityTest`, `LockScreenshotTest` (screenshots
  verified unchanged).
- The Mac/Linux installer against a fake `adb`, with bash 5 and macOS's bash 3.2: Fire TV Stick 4K on Fire OS 6, Fire
  TV Stick 4K Max on Fire OS 8 (declares only `amazon.hardware.fire_tv`), Chromecast with Google TV, a phone (warned,
  installed), Fire OS 5 (stopped before installing), and `--undo` on Fire OS 6.
- The Windows installer: reviewed line by line only (no Windows here). It stays CRLF, UTF-8 without BOM, ASCII before
  `chcp 65001`.

**Not tested, to try on real devices**: any Fire TV; any Android 7.1 device (Compose speed, video decoding, the phone
server); the guard's cover and return on Fire OS 6, 7 and 8; «فحص التحديثات» on Android 7.1 to 8.1; a 1 GB or 1.5 GB
stick (Fire TV Stick 4K 1st gen, Fire TV Stick Lite) for memory and smoothness. On each: install with the kit, open
«حالة الحماية» (all green), play a video for a few minutes, try Home in a kid's profile, pair the phone, check for
updates.

## Per-brand steps for the guide

Every TV: same home network as the computer; the TV asks «Allow USB debugging?» on the first connection: tick «Always
allow from this computer», then OK. The installer connects to port 5555.

### Google TV (Chromecast with Google TV, Google TV Streamer, Sony, TCL, Hisense and Philips Google TV sets)

1. Developer options: Settings > System > About > select «Android TV OS build» and press OK 7 times («You are now a
   developer»).
2. Settings > System > Developer options > «USB debugging»: on.
3. IP address: Settings > Network & Internet > your Wi-Fi > IP address (with a cable: Network & Internet > Ethernet).
4. Some newer models offer only «Wireless debugging» (Developer options > Wireless debugging > Pair device with pairing
   code): when the TV refuses port 5555, the installer offers this pairing with the 6-digit code.

### Android TV before Google TV (Sony Bravia, Philips, Xiaomi Mi Box and Mi TV Stick, TCL and Hisense Android TV, Nvidia Shield, projectors)

1. Developer options: Settings > Device Preferences > About > select «Build» and press OK 7 times. (Philips: Settings
   > All settings > Android settings > Device Preferences > About. Menus may differ by model and year.)
2. Settings > Device Preferences > Developer options > «USB debugging»: on. Nvidia Shield: also «Network debugging».
3. IP address: Settings > Network & Internet > your network (some Sony and Philips sets show it under Network status
   or Advanced).

### Amazon Fire TV (sticks, Cube, Fire TV smart TVs)

1. Developer options: Settings > My Fire TV > Developer options. If it is missing (newer Fire OS hides it): Settings >
   My Fire TV > About > select the device's name (like «Fire TV Stick») and press OK 7 times, then go back. On Fire TV
   smart TVs the menu is «Device & Software» instead of «My Fire TV».
2. Developer options > «ADB debugging»: on. (Fire OS 6 also has «Apps from Unknown Sources»; it is not needed for the
   installation, and the installer turns it on for updates.)
3. IP address: Settings > My Fire TV (or Device & Software) > About > Network.
4. The TV asks «Allow USB debugging?» like any Android TV: «Always allow from this computer», then OK.
5. The system version is in the same About screen: Fire OS 6, 7, 8 or 14 is fine; Fire OS 5 and Vega OS are not.
