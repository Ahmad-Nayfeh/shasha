> **Status (v1.1):** this is the full expected-behaviour matrix written by the independent review. Part of it is automated in `app/src/test/java/com/ahmadnayfeh/shasha/remote/ScenarioMatrixTest.kt` and `remote/src/test/java/com/ahmadnayfeh/shasha/remote/ScenarioMatrixRemoteTest.kt`, plus `PhoneLogicChecklistTest`. The review was paused before every row was automated; finishing it is on the v1.2 list.
>
> **v1.2 (owner decisions D1, D11, D12):** Serious Mode is two switches; a video only the phone could end early waits for the phone's «تأكيد»; a link from the phone waits for «قبول» on the TV. Rows changed: D1, ST-01/11/12, the PL note, PR-11, UV-05, SET-10/11, RS-05/06, TV-09/10, UI-13; rows added: ST-18, PL-33…PL-39, PR-21, UV-07/08, BK-09, TV-17, CF-01…CF-11, UI-22…UI-24. Automated: the server rows in `ScenarioMatrixTest` (ST-18, PL-33…39, PR-21, UV-07/08, SET-10/10b/11, TV-17, CF-01…03); CF-04…11 in `PlayerPhoneConfirmTest`, `BehaviorSettingsTest`, `SettingsBehaviorPanesTest`, `TvRemoteControllerV12Test`; BK-09 in `BackupManagerTest`. UI-22…24 are for the browser checklist.

# Shasha: phone ↔ TV scenario matrix (EXPECTED behaviour)

Phase 1 of the independent review. Derived from SPEC.md (§3 policy, §4 lock, §5 phone, §7 settings), the owner decisions
recorded in ARCHITECTURE.md (phases 4–5, the final phase, v1.1) and the decisions restated for this review (D1–D10
below). It was written before the phone page and its API were finished, so it says what MUST happen, not what the code
does. Endpoint names are the documented wire contract (ARCHITECTURE.md §:remote + the v1.1 parent routes); only they
are taken from the code.

## 0. Requirements used (decisions)

| # | Decision |
|---|---|
| D1 | «الوضع الجاد» (Serious Mode, formerly the video lock) is two independent switches since v1.2, «الوضع الجاد لملفات الأطفال» and «الوضع الجاد لملف الكبار», both ON by default (v1.1's single switch becomes both, in the TV's file and in an old backup); each can be switched off in Settings (TV and phone). A video locks only when the switch of the current profile's kind is on. While locked, a video cannot be left before it ends: a child leaves with the parent PIN on the TV (the PIN pad on BACK, or the TV's picker with the PIN) or the parent's unlock from the phone; the adult leaves with a long press on BACK (10 s by default) plus a typed phrase on the TV (the "escape", which can be switched off) or the parent's unlock from the phone (recorded as a surrender); the PIN never ends the adult's lock. Switching a kind's switch off ends the current lock of that kind at once (no surrender), never the other kind's. Switching it on does not lock the video already playing; the next video locks. |
| D2 | `adultEntryPin` (default ON): entering the adult profile asks for the parent PIN, on the TV and from the phone. OFF: no PIN to enter the adult profile, but leaving a CHILD profile still needs the PIN, and Settings (TV) / the parent section (phone) always need the PIN. Nothing may tell the user a PIN will be asked when it will not. |
| D3 | Every TV setting is also editable from the phone's parent section, with the TV's own choices, validation and defaults: a value the TV would not offer is refused (nothing stored, Arabic reason). Per-section reset and a global reset; the global reset touches settings only (never profiles, the PIN, the lists, the subscriptions, the history). |
| D4 | Two separate PIN counts: the TV's limiter (every TV PIN pad) and the phone's (PhonePin, persisted, behind the server's in-memory PinGate). Wrong PINs on the TV never lock the phone out and vice versa. Both follow the PIN brake settings («المحاولات قبل الإيقاف», «مدة الإيقاف الأولى»). |
| D5 | Child profiles: allowlist only (channels and playlists; a video LINK counts only the video's own channel, its `list=` is ignored), search only where the profile allows it (a child never does today: `canSearch` false, the server re-checks), daily quota and bedtime with the quiet warning, «انتهى الوقت» opened with the PIN on the TV or from the phone's parent section. |
| D6 | Two (or more) phones can be paired; they share one pairing token. The token is needed for EVERY request (the page itself included). Parent actions need a parent session (per phone login, 30 min sliding, at most 8, memory only). |
| D7 | (phase 5) While a video is locked the phone gets refusals for STOP, new links (any kind), profile switches and restores; play, pause and seek still work; the parent's «إنهاء قفل هذا الفيديو» comes first. |
| D8 | (phase 4) A natural end releases the lock (a child's playlist moves the lock to the next video); a video skipped to its end stays locked. |
| D9 | (final phase) The guard keeps «شاشة» in front while a video is locked, time is up, outside the schedule, or a child profile is current (until the parent lets it out with «خروج» + PIN). |
| D10 | Content rules come before time rules (a forbidden item never shows "time up"), for every link kind; a locked TV refuses every new link before anything is looked up. |
| D11 | (v1.2) A video that would start locked while nothing on the TV can end it early (the adult profile with its switch on and the escape off; a child always has the PIN on the TV) must first be confirmed from the phone. The TV shows «لتشغيل هذا الفيديو في الوضع الجاد، أكّد من الجوال» with the title, a waiting indicator and «إلغاء»; every page shows «تأكيد تشغيل: <title>» with «تأكيد» / «رفض» (parent session; the PIN is asked when there is none). «تأكيد» starts it; «رفض», 2 minutes or «إلغاء» on the TV: nothing plays. With no phone paired, or none polled in ~60 s, the TV says «لا يوجد جوال متصل؛ اربط الجوال من الإعدادات ← التحكم من الجوال، أو فعّل طريقة الخروج من الشاشة» with «إلغاء» and «ربط الجوال». Turning the escape off on the TV needs a paired phone (otherwise refused with the reason; the setting's text warns of it). A video the phone sent and the TV accepted (D12) asks nothing more. |
| D12 | (v1.2) A link sent from the phone never plays by itself. The TV's rules come first (a refused link never shows a question). Then the TV shows «طلب تشغيل من الجوال: <title>» over whatever is on screen (nothing paused or left), focus on «قبول», «رفض» (BACK too), rejected by itself after 30 s; one at a time (a newer link replaces it). Only «قبول» opens it, after the rules are asked again (a lock: the existing reason). The phone follows it live through the status (PENDING, ACCEPTED, REJECTED, TIMED_OUT, FAILED). A waiting link is dropped when the TV leaves its profile or a video gets locked. |

## 1. Dimensions and their values

| Dim | Values (abbreviation) |
|---|---|
| Profile on the TV | adult `A` (owner «الكبار», id `adult`, no limits unless stated), child `C` («سارة», id `kid`: 60 min/day, 07:00–22:00, hides shorts, allowlist = one channel + one playlist), second child `C2` when needed |
| Session state | `OK` (time left or no limit), `WARN` (≤ warning minutes left), `UP` (time up), `OUT` (outside schedule) |
| Playback | `idle`, `playing`, `paused`, `live`, `ended` (end card), `covered` (a PIN pad over the video), `locked` (Serious Mode lock: child = PIN, adult = escape) |
| Settings | `SM` on/off (Serious Mode; v1.2: `SMk` the children's switch, `SMa` the adult's, `SM` alone = both), `ESC` on/off (adult escape), `AEP` on/off (adultEntryPin) |
| TV's answer to a phone link (v1.2) | `accept` («قبول»), `reject` («رفض»), `none` (30 s pass) |
| Pairing token | `ok`, `missing`, `wrong`, `rotated` (regenerated on the TV) |
| Parent session | `none`, `valid`, `unknown`, `expired` (idle 30 min, or expired mid-action), `revoked` (PIN change, restore, token change) |
| PIN counts | phone `fresh` / `k wrong` / `locked`; PinGate `open` / `blocked`; TV `fresh` / `locked` |
| Phones | `1P`, `2P` |
| Network / server | `up`, phone `offline`, TV `offline`, server `restart` (process), server `stop/start` (network change), port `move` |

Defaults for every row unless the row says otherwise: token ok, network up, 1P, both SM switches on, ESC on, AEP on, parent
session valid on parent routes, all PIN counts fresh, playback idle, session OK, clock 12:00 local (Asia/Riyadh), the TV
accepts a phone link («قبول»).

Notation: `200 ok` = HTTP 200 `{ok:true, message}`; `200 ¬ok` = HTTP 200 `{ok:false, message}` (a refusal by the TV's
rules, shown as is); `403 rejected` / `401 pairing` / `401|403 parent_session` / `401 wrong_pin` / `429 locked_out` /
`400 bad_request` / `413 too_large` / `415 unsupported` / `404 not_found` = `{ok:false, code, message}`.

## 2. Pruning rules (why the full cross-product is not listed)

| Rule | Equivalence | Consequence |
|---|---|---|
| R1 | No network or no server: no HTTP answer at all, whatever the action or the TV state. | One set of network rows (NW), not one per action. |
| R2 | The pairing token is checked before routing, for every path. | Token rows (T) once per route family; with a bad token no other dimension changes anything. |
| R3 | The parent session is checked before the body is read and before the controller runs. | Session rows (PS) once, run over every parent route; business rows assume a valid session. |
| R4 | Body validation (400/413/415) happens before the controller and does not read TV state. | Validation rows (V) once per route. |
| R5 | `AEP` only affects switching INTO an adult profile (phone and TV picker) and the texts about it. | AEP crossed only with PR rows and the TV picker. |
| R6 | `ESC` is a TV-only way out; the phone meets it only through D11 (the «تأكيد» it then asks for). | ESC crossed only with UV/SET/CF rows. |
| R7 | The server cannot tell phones apart (one token). A second phone only matters for: separate parent sessions, the shared PIN count, concurrent writes (base check, grants), concurrent controls, change propagation (`rev`), following a port move. | Two-phone table (P2); every 1P row holds for each phone. |
| R8 | A kind's `SM` off ⇒ no video of that kind is ever `locked`. | Locked rows exist only with that kind's SM on; SM off is crossed with "a video starts", "STOP" and the other kind's lock (UV-07/08). |
| R9 | `WARN` changes only the status and the banner; every action allowed in `OK` is allowed in `WARN`. | WARN rows only for status, UI and time grants. |
| R10 | `UP` and `OUT` refuse the same things; they differ in the reason text and in the grant (quota vs schedule). | Both listed for status, links (one kind each), grants and UI; merged elsewhere. |
| R11 | `playing` ≡ `paused` for every action but PLAY/PAUSE/TOGGLE and `isPlaying`. | Separate only in CT and ST. |
| R12 | Profile kind does not change controls, lists, subscriptions, Takeout, backup or settings (household data); it changes status, search, links, switches and time. | Kind crossed only where it matters; one child row proves control parity. |
| R13 | The TV display (screen off, background, picker, profile changed, no answer) is consulted only after the rules allowed the link; the TV asks «قبول» only then (D12). | Display rows only with an allowed link. |
| R14 | PIN-count states are read only by the PIN-taking routes (`/api/parent/login`, `/api/profile` with a PIN). | Crossed only with LG and PR rows. |
| R15 | A locked TV refuses every link before the content or the time is looked at (D10). | Lock × kind × verdict collapses to one row per profile kind. |

## 3. Gates before every action

### T: pairing token (R2)

| ID | Preconditions | Action | TV (expected) | HTTP / JSON | Phone UI |
|---|---|---|---|---|---|
| T-01 | token missing, browser | `GET /` (Accept text/html) | nothing | 401, small Arabic HTML page (unpaired) | «امسح رمز QR…»; a token remembered in this browser is retried |
| T-02 | token missing, script | `GET /` (no html Accept) | nothing | 401 pairing JSON | — |
| T-03 | token wrong | `GET /api/status` | nothing | 401 pairing «رابط الاقتران غير صالح…» | fatal overlay asking to scan the QR again; polling stops; stored token forgotten |
| T-04 | token wrong | `POST /api/play` (allowed video) | nothing shown, no player, no lock | 401 pairing | fatal overlay |
| T-05 | token wrong, valid parent session header | `POST /api/parent/quota` | quota unchanged | 401 pairing (token before session) | fatal overlay |
| T-06 | token wrong | `GET /icon.png` | — | 401 | — |
| T-07 | token rotated on the TV while two pages are open | next request of each page | parent sessions revoked; phone PIN count and PinGate forgotten | 401 pairing at once (the old token is never accepted again) | both pages: fatal overlay; old token removed from localStorage |
| T-08 | token ok | the same request with `?t=` and with `Authorization: Bearer` | — | 200 both ways | — |
| T-09 | token ok | `GET /no-such-path` | — | 404 not_found | — |
| T-10 | token ok | `GET /` | — | 200 HTML; Cache-Control no-cache/no-store; CSP; Referrer-Policy no-referrer; X-Frame-Options DENY | page loads |
| T-11 | token ok, share link | open `/?t=TOK&v=<allowed video>` | the video opens | one `POST /api/play` → 200 ok | result sheet «أُرسل إلى الشاشة»; the link is removed from the address (a reload does not resend) |
| T-12 | share link that arrived without the token (`ask=1`) | open the page | nothing until confirmed | no request before the yes | sheet «إرسال هذا الرابط إلى الشاشة؟»; «أرسل» sends once |

### PS: parent session (R3; each row is run over every `/api/parent/*` route except login/logout)

| ID | Preconditions | Action | TV (expected) | HTTP / JSON | Phone UI |
|---|---|---|---|---|---|
| PS-01 | no `X-Parent-Session` | any parent route | nothing changes | 401 parent_session «سجّل الدخول إلى قسم الأب أولاً.» | login card |
| PS-02 | unknown session id | any parent route | nothing changes | 403 parent_session «انتهت جلسة قسم الأب…» | PIN sheet |
| PS-03 | session idle 29 min | parent GET | — | 200; the 30 min restart from now | — |
| PS-04 | session idle 30 min | parent route | nothing changes | 403 parent_session | PIN sheet |
| PS-05 | session expired between opening a page and «حفظ» (mid-action) | a write (e.g. quota) | the rejected write changed NOTHING | 403 parent_session; after a new login the same write → 200 ok | PIN sheet «انتهت جلسة قسم الأب»; after the PIN the action is sent again once; cancel → «…فلم يُحفظ آخر تغيير…» on the login card |
| PS-06 | parent PIN changed on the TV | parent route with the old session | — | 403 parent_session | PIN sheet |
| PS-07 | a backup restored that changes the PIN hash | parent route with any old session | — | 403 parent_session | PIN sheet |
| PS-08 | 9 logins | the 1st session | dropped (at most 8) | 403 parent_session | PIN sheet on that phone |
| PS-09 | phone A logs out | phone B's parent route | — | 200 (B's session unaffected) | — |

### V: request validation (R4)

| ID | Route | Bad input | Expected |
|---|---|---|---|
| V-01 | any JSON route | not JSON / a required field missing | 400 bad_request «الطلب غير صالح.»; controller not called |
| V-02 | any JSON route | body > 16 KB | 413 too_large |
| V-03 | `/api/search` | `q` empty / > 200 chars | 400 «اكتب ما تريد البحث عنه.» / 400 «نص البحث طويل جداً…» |
| V-04 | `/api/play` | url empty / > 2048 chars | 400 «الصق رابط يوتيوب أولاً.» / 400 |
| V-05 | `/api/control` | SEEK_BY 0 or \|s\| > 86400; unknown action | 400 |
| V-06 | `/api/profile` | id blank; pin > 32 chars | 400 |
| V-07 | `/api/parent/login` | pin empty / > 32 | 400 «أدخل رمز PIN.» / 400 |
| V-08 | `/api/parent/quota` | minutes < 0 or > 1440; minutes field missing | 400 (a missing value never means "unlimited") |
| V-09 | `/api/parent/unlock-time` | extraMinutes 0 or > 1440; field missing | 400 (missing never means "rest of today") |
| V-10 | `/api/parent/schedule` | only one bound; a bound ≥ 1440 | 400 |
| V-11 | `/api/parent/takeout` | empty / > 20 MB / multipart without a file | 400 «الملف فارغ.» / 413 / 400 «لم يصل أي ملف.» |
| V-12 | `/api/parent/backup` (POST) | empty / > 10 MB | 400 / 413 |
| V-13 | `/api/parent/settings`, `/settings/reset` | key blank or > 64; group blank | 400 |
| V-14 | `/api/parent/blocklist/add` | kind not keyword/channel; keyword empty or > 100 | 400 / 400 «اكتب كلمة من 1 إلى 100 حرف.» |
| V-15 | `/api/parent/profile/create`, `/edit` | name empty; avatar not a key; edit with nothing to change | 400 «اكتب اسماً للملف.» / 400 / 400 |

## 4. Everyone's actions

### ST: status (`GET /api/status`, polled every 2 s by each page)
Enumeration: profile {A, C} × session {OK unlimited, OK limited, WARN, UP, OUT} × playback {idle, playing, paused, live, locked} × SM {on, off}. The fields are computed independently (profile/session vs player vs lock), so each value is covered once plus the interacting pairs (UP/OUT releases the player; SM off makes `locked` impossible, R8).

| ID | Preconditions | TV (expected) | `GET /api/status` (expected fields) | Phone UI |
|---|---|---|---|---|
| ST-01 | A, no limits, idle | adult home | profileId `adult`, profileKind ADULT, canSearch true, nowPlaying null, timeLeftMinutes null, sessionState OK, videoLocked false, seriousMode true (the current kind's switch), confirmation null, playRequest null | who-chip «الكبار»; «لا شيء يُعرض الآن على الشاشة.»; today card «بلا حد يومي» |
| ST-02 | C, 0 used, 12:00 | child home | CHILD, canSearch false, timeLeftMinutes 60, OK | search tab shows a lock; «متبقٍ اليوم: 60 دقيقة» |
| ST-03 | C, 56 min used | quiet warning banner shown once | WARNING, timeLeftMinutes 4 | banner «الوقت يوشك على الانتهاء» + «بقي 4 دقائق…» |
| ST-04 | C, 30 s left | — | timeLeftMinutes 1 (rounded up; never 0 while time is left), WARNING | — |
| ST-05 | C, 60 min used | «انتهى الوقت» screen | TIME_UP, timeLeftMinutes 0 | red banner «انتهى وقت المشاهدة اليوم» + «فتح الوقت» |
| ST-06 | C, 23:00 | outside-schedule screen | OUTSIDE_SCHEDULE, timeLeftMinutes 0 | banner «خارج أوقات المشاهدة المسموحة» + «فتح الوقت» |
| ST-07 | C, 21:50, 0 used | — | OK, timeLeftMinutes 10 (bedtime nearer than the quota) | — |
| ST-08 | A playing | player | nowPlaying {videoId, title, channelName, positionSeconds, durationSeconds, isPlaying true, isLive false} | now-playing card, pause icon |
| ST-09 | A paused | player paused | isPlaying false | play icon |
| ST-10 | A live | live player | durationSeconds null, isLive true | «مباشر»; ±10 disabled |
| ST-11 | the current kind's SM on, a video started (A or C) | video locked | videoLocked true | lock line under the controls; STOP shown disabled |
| ST-12 | the current kind's SM off, a video started | not locked | seriousMode false, videoLocked false | STOP enabled |
| ST-13 | C, locked video, then UP | time-up screen over the released player; the lock kept | TIME_UP, nowPlaying null, videoLocked true | time-up banner; no player controls |
| ST-14 | nothing changes | — | `rev` equal on consecutive polls | parent pages are not reloaded |
| ST-15 | a setting / a profile / a list / a subscription / the current profile changes (phone, other phone or TV) | — | `rev` differs on the next poll | parent pages read again |
| ST-16 | a port move announced | server moves in a moment | movingTo = new port (null otherwise) | "moving" overlay, page follows |
| ST-17 | warning_minutes = 0, C with 4 min left | no warning banner | OK (never WARNING) | no warning banner |
| ST-18 | (v1.2) SMk on, SMa off; A then C, a video started in each | A's not locked, C's locked | A: seriousMode false, videoLocked false; C: seriousMode true, videoLocked true | — |

### SE: search (`POST /api/search`)
Enumeration: profile {A, C} × session/lock {OK, UP, OUT, locked} × YouTube {results, none, failure, slow}. Pruned: a child is refused before anything else (one row); for the adult the session and the lock do not change the server's answer because a search opens nothing on the TV (UP ≡ OUT ≡ locked → one row, the page dims the results).

| ID | Preconditions | Action | TV (expected) | HTTP / JSON | Phone UI |
|---|---|---|---|---|---|
| SE-01 | C (any state) | search «كرتون» | nothing; YouTube not asked | 403 rejected «البحث غير متاح…» (even when the page is bypassed) | search view replaced by the explanation card; tab shows a lock |
| SE-02 | A, results include a blocked channel, a blocked keyword and a short (adult hides shorts) | search | — | 200 list without the blocked/hidden items; items carry type video/channel/playlist, id, title | result rows |
| SE-03 | A, nothing found | search | — | 200 [] | «لا نتائج عن «…». جرّب كلمات أخرى.» |
| SE-04 | A, YouTube fails | search | — | 403 rejected with the unified Arabic error text | card «تعذّر البحث» + the text |
| SE-05 | A, UP / OUT / a locked video | search | nothing changes on the TV | 200 results | results shown dimmed (aria-disabled) with the reason above them; a tap only shows the reason (no request) |
| SE-06 | A, the TV switches to C while the search runs | search | — | the answer is filtered by the CHILD's rules (outside-allowlist items gone) | — |

### PL: play a link (`POST /api/play`)
Enumeration: profile {A, C} × session {OK≡WARN, UP, OUT} × lock {none, locked} × link {video, video+`list=`, video+`t=`, playlist, channel, not YouTube} × verdict {allowed, not in allowlist, blocked channel, blocked keyword, shorts hidden, YouTube failure} × TV display {shown, screen off, background, picker, profile changed, no answer} × SM {on, off} × the TV's answer {accept, reject, none}. Pruned by R10, R13, R15: locked → 1 row per kind of profile; display failures only for an allowed link (one row each, the path does not depend on the link kind); SM only decides whether the started video locks; the TV's answer only matters for an allowed link (PL-33…PL-39).

v1.2 (D12): for every row below whose expected answer is `200 ok`, the immediate answer is `200 {ok:true, message «بانتظار القبول على الشاشة: «title»», request {id, state PENDING, secondsLeft}}`; nothing opens yet and the TV shows «طلب تشغيل من الجوال». The row's TV effect and `ok` text are what follows «قبول» (the request's ACCEPTED state and message in the status). PL-28 and PL-29 happen after «قبول» (the request ends FAILED with that text). Every `200 ¬ok` row is answered at once (no `request`) and the TV never asks. A video opened this way asks no «تأكيد» (D11).

| ID | Preconditions | Link | TV (expected) | HTTP / JSON | Phone UI |
|---|---|---|---|---|---|
| PL-01 | C, OK | video of an allowlisted channel | player opens in the CHILD's screens (link origin) | 200 ok «يُعرض الآن على الشاشة: «title»» | ok toast; input cleared |
| PL-02 | C, OK | video outside the allowlist | NOTHING: no screen asked, no player, no lock | 200 ¬ok «هذا المحتوى ليس ضمن القنوات والقوائم المسموحة لهذا الملف.» | error toast |
| PL-03 | C, OK | outside video with `&list=<allowlisted playlist>` | nothing (list= ignored) | 200 ¬ok (not in allowlist) | error toast |
| PL-04 | C, OK | allowlisted video with `&list=<other playlist>` | player opens; next videos only those the child may see | 200 ok | — |
| PL-05 | C, OK, hides shorts | allowlisted channel, a short (≤ 3 min or /shorts/) | nothing | 200 ¬ok «المقاطع القصيرة (Shorts) مخفية في هذا الملف.» | — |
| PL-06 | C, OK, household blocks a keyword | allowlisted video whose title has it | nothing | 200 ¬ok «العنوان فيه كلمة محظورة…» | — |
| PL-07 | C, OK | allowlisted playlist | player on its first video the child may see, playing the playlist | 200 ok «تُعرض قائمة «title» على الشاشة الآن.» | — |
| PL-08 | C, OK | playlist not allowlisted | nothing | 200 ¬ok (not in allowlist) | — |
| PL-09 | C, OK | allowlisted playlist whose videos are all hidden | nothing | 200 ¬ok «لا يوجد في قائمة التشغيل فيديو مسموح في هذا الملف.» | — |
| PL-10 | C, OK | allowlisted channel (`/channel/UC…`, `@handle`) | channel page | 200 ok «تُعرض صفحة القناة على الشاشة الآن.» | — |
| PL-11 | C, OK | other channel | nothing | 200 ¬ok (not in allowlist) | — |
| PL-12 | C, UP | allowlisted video | nothing (time-up screen stays) | 200 ¬ok «انتهى وقت المشاهدة لهذا اليوم.» | send box disabled with the time-up reason (no request) |
| PL-13 | C, OUT | allowlisted video | nothing | 200 ¬ok «المشاهدة غير مسموحة في هذا الوقت.» | send box disabled with the schedule reason |
| PL-14 | C, UP | video outside the allowlist | nothing | 200 ¬ok NOT IN ALLOWLIST (content before time, D10) | — |
| PL-15 | C, UP / OUT | allowlisted playlist / allowlisted channel | nothing | 200 ¬ok TIME UP / OUTSIDE SCHEDULE | — |
| PL-16 | C, UP | channel outside the allowlist; playlist not allowlisted | nothing | 200 ¬ok NOT IN ALLOWLIST (content before time for every kind, D10) | — |
| PL-17 | C or A, a video locked | any YouTube link (the locked video's own included) | nothing; lock unchanged; YouTube not asked | 200 ¬ok «الوضع الجاد مفعّل: لا يبدأ فيديو آخر…» | send box and search results disabled with the Serious Mode reason |
| PL-18 | A, OK | any video | player opens | 200 ok | — |
| PL-19 | A, OK, household blocks a channel | its video / its channel link | nothing | 200 ¬ok «هذه القناة محظورة في هذا الملف.» | — |
| PL-20 | A, OK | playlist / channel link | playlist plays / channel page | 200 ok | — |
| PL-21 | A with a quota, UP | any video | nothing | 200 ¬ok TIME UP (the adult is not exempt from his own limits) | — |
| PL-22 | A or C, OK | `watch?v=ID&t=95` | player starts at 95 s | 200 ok | — |
| PL-23 | any | «hello», `https://example.com/x` | nothing | 200 ¬ok «هذا ليس رابطاً من يوتيوب…» | — |
| PL-24 | any | removed / unavailable video | nothing | 200 ¬ok with the unified Arabic error text | — |
| PL-25 | allowed link, TV in standby | video | nothing | 200 ¬ok «الشاشة في وضع الاستعداد…» | — |
| PL-26 | allowed link, «شاشة» in the background, no overlay permission | video | nothing | 200 ¬ok «افتح «شاشة» على التلفاز ثم أعد الإرسال…» | — |
| PL-27 | allowed link, the TV asks «من يشاهد الآن؟» | video | nothing | 200 ¬ok «اختر الملف على الشاشة أولاً…» | — |
| PL-28 | allowed link, the profile changed on the TV between the check and the opening | video | the other profile's screens open NOTHING | 200 ¬ok «تغيّر الملف على الشاشة قبل أن يُفتح الرابط…» | — |
| PL-29 | allowed link, the screens did not answer | video | nothing | 200 ¬ok «لم تستجب الشاشة. أعد المحاولة.» | — |
| PL-30 | SM on | allowed video (A, then C) | it starts locked (A: escape; C: PIN) | 200 ok; next status videoLocked true | STOP shown disabled, lock line |
| PL-31 | SM off | allowed video | not locked | 200 ok; videoLocked false | STOP enabled |
| PL-32 | an UNLOCKED video plays (SM off, or after the parent's unlock) | another allowed video | it replaces the first | 200 ok | — |
| PL-33 | (v1.2) an unlocked video plays | an allowed video | «طلب تشغيل من الجوال: «title»» over the video; nothing opens, the video goes on untouched; «قبول» (focused) opens the link | 200 ok + request PENDING (secondsLeft ≤ 30); status playRequest PENDING, then ACCEPTED «يُعرض الآن على الشاشة: «title»» | sheet «بانتظار القبول على الشاشة» with the seconds left, then «قُبل على الشاشة» |
| PL-34 | (v1.2) an unlocked video plays | an allowed video, the TV answers «رفض» | nothing opens; the video goes on | status playRequest REJECTED «رُفض الطلب على الشاشة.» | «رُفض على الشاشة» |
| PL-35 | (v1.2) — | an allowed video, nobody answers for 30 s | the question goes by itself; nothing opens | status playRequest TIMED_OUT «لم يُقبل الطلب على الشاشة خلال 30 ثانية، فأُلغي.» | «انتهت المهلة دون رد» |
| PL-36 | (v1.2) a link waits on the TV | a second link (this phone or another) | the TV asks about the newer one only; the first can no longer be accepted | status playRequest = the newer one | the first page's sheet: «حلّ محلّه طلب أحدث على الشاشة.» |
| PL-37 | (v1.2) a link checked for A waits | the TV switches to C | the question goes; nothing opens | status playRequest FAILED «تغيّر الملف على الشاشة…» | «لم يُفتح على الشاشة» |
| PL-38 | (v1.2) a link waits | a video starts on the TV and locks | the question goes at once; the locked video stays | status playRequest FAILED with the Serious Mode reason | «لم يُفتح على الشاشة» |
| PL-39 | (v1.2) A, SMa on, ESC off | an allowed video, accepted | it starts locked (only the phone ends it), with no «تأكيد» on top (the phone sent it) | 200 ok; status confirmation null | — |

### CT: playback controls (`POST /api/control`)
Enumeration: playback {idle, playing, paused, live, ended, covered, locked, locked+skipped-to-end, locked+UP} × action {PLAY, PAUSE, TOGGLE, SEEK_BY ±, STOP} × SM. Pruned: profile kind (R12; CT-10 runs for both), WARN ≡ OK (R9), SM off has no locked states (R8).

| ID | Preconditions | Action | TV (expected) | HTTP / JSON | Phone UI |
|---|---|---|---|---|---|
| CT-01 | idle | PLAY, PAUSE, TOGGLE, SEEK_BY 10, STOP | nothing | 200 ¬ok «لا شيء يُعرض الآن.» | no controls shown |
| CT-02 | playing | PAUSE | paused | 200 ok | play icon at once |
| CT-03 | paused | PLAY | playing | 200 ok | pause icon |
| CT-04 | playing / paused | TOGGLE | flips | 200 ok | — |
| CT-05 | playing | SEEK_BY +10 / −10 | position ±10 s | 200 ok | bar moves at once |
| CT-06 | live | SEEK_BY / PAUSE | no seek / paused | 200 ¬ok «لا يمكن التقديم في البث المباشر.» / 200 ok | ±10 disabled |
| CT-07 | ended (end card) | PLAY, TOGGLE, SEEK_BY | nothing | 200 ¬ok «انتهى الفيديو…» | — |
| CT-08 | covered (PIN pad over the video) | PLAY / PAUSE | stays paused / paused | 200 ¬ok «على الشاشة طلب رمز الآن…» / 200 ok | — |
| CT-09 | not locked (SM off) | STOP | the player leaves | 200 ok «أُوقف التشغيل.» | — |
| CT-10 | locked, A and C | STOP | player stays; lock unchanged | 403 rejected «الوضع الجاد: الفيديو يُشاهَد حتى نهايته. يُنهيه الأب من قسم الأب.» | STOP shown disabled; a tap explains (sheet «الوضع الجاد» with «قسم الأب»), no request |
| CT-11 | locked | PAUSE, PLAY, TOGGLE, SEEK_BY | carried out (D7) | 200 ok | — |
| CT-12 | locked video skipped to its end (end card, still locked) | STOP | player stays | 403 rejected | — |
| CT-13 | locked + UP (player released) | STOP / PAUSE | nothing | 403 rejected / 200 ¬ok nothing playing | — |
| CT-14 | after the parent's unlock | STOP | player leaves | 200 ok | STOP enabled |

### PR: profiles and switching (`GET /api/profiles`, `POST /api/profile`)
Enumeration: from {A, C} × to {same, A, C, C2, unknown} × PIN {none, wrong, right} × AEP × lock × PIN counts (R14) × the TV picker. Pruned: a locked video refuses every switch before the PIN (R15 analogue, D7); PIN counts only matter when a PIN is checked.

| ID | Preconditions | Action | TV (expected) | HTTP / JSON | Phone UI |
|---|---|---|---|---|---|
| PR-01 | A | `GET /api/profiles` | — | all profiles in the TV's picker order; current isCurrent true, needsPin false; children needsPin false; quotas | tiles; current marked «الآن» |
| PR-02 | C (AEP on or off) | `GET /api/profiles` | — | every other profile needsPin true (leaving a child) | «بالرمز» on the other tiles |
| PR-03 | A, a second adult `A2` exists | `GET /api/profiles` with AEP on / off | — | A2 needsPin true / false | — |
| PR-04 | A | switch to C (no PIN) | C current; child screens; guard enforces (child) | 200 ok «الشاشة الآن على ملف «سارة».» | who-chip «سارة»; search locked |
| PR-05 | A | switch to A (current), with or without a PIN | nothing; no PIN checked or counted | 200 ok ««الكبار» هو الملف المفتوح الآن.» | — |
| PR-06 | C | switch to A, no PIN | nothing; nothing counted | 200 ¬ok «هذا الملف يحتاج رمز الوالدين.» | the page asks the PIN first (PIN sheet) |
| PR-07 | C | switch to A, wrong PIN | nothing; phone count 1, TV count 0 | 200 ¬ok «رمز PIN غير صحيح. بقيت 4 محاولات.» | PIN sheet error + shake |
| PR-08 | C, AEP on | switch to A, right PIN | A current; the TV shows «من يشاهد الآن؟» and asks the PIN for A | 200 ok, the text says the TV asks the PIN too | toast |
| PR-09 | C, AEP off | switch to A, right PIN | A current; the TV asks NO PIN for A | 200 ok, and the text must NOT say a PIN is asked on the TV | toast |
| PR-10 | C | switch to C2 without / with the right PIN | nothing / C2 current | 200 ¬ok needs PIN / 200 ok «الشاشة الآن على ملف «C2».» | — |
| PR-11 | a video locked (C's with the right PIN; A's) | any switch | profile and lock unchanged; the PIN is not checked (phone count unchanged) | 200 ¬ok «فيديو في الوضع الجاد قيد المشاهدة…ثم بدّل الملف.» | other tiles disabled + banner «لا يُبدَّل الملف أثناء فيديو مقفول»; a tap explains |
| PR-12 | any | switch to an unknown id | nothing | 200 ¬ok «هذا الملف غير موجود.» | — |
| PR-13 | C, phone count locked out | switch to A, RIGHT PIN | nothing; PIN not checked | 200 ¬ok «محاولات خاطئة كثيرة. حاول بعد … أو أدخل الرمز على التلفاز.» | PIN sheet warning |
| PR-14 | C, TV count locked out (a child guessed on the TV) | switch to A, right PIN | A current (D4) | 200 ok | — |
| PR-15 | PinGate blocked | switch with a PIN | nothing; controller not called | 429 locked_out + Retry-After | PIN sheet warning |
| PR-16 | C in UP or OUT | switch to A, right PIN | A current, no time-up screen (A has no limits) | 200 ok | — |
| PR-17 | A, C's time is up | switch to C | C current with its «انتهى الوقت» screen | 200 ok; status TIME_UP | time-up banner |
| PR-18 | the TV shows «من يشاهد الآن؟» (start or relock), C current | phone switches to C (current) / to C2 with the PIN | the TV opens that child's screens / C2's | 200 ok | — |
| PR-21 | (v1.2) SMk off, SMa on; C's video plays (not locked), then A's video locked | switch to A with the PIN; then switch to C | C → A switches; A → C refused, the profile and the lock unchanged | 200 ok; then 200 ¬ok «فيديو في الوضع الجاد قيد المشاهدة…» | — |

### LG: parent login and logout
| ID | Preconditions | Action | TV (expected) | HTTP / JSON | Phone UI |
|---|---|---|---|---|---|
| LG-01 | counts fresh | login, right PIN | phone count and PinGate reset | 200 {ok:true, session, «أهلاً بك في قسم الأب.»} | hub |
| LG-02 | counts fresh | wrong PIN ×4 | phone count 1..4; TV count 0 | 401 wrong_pin «…بقيت 4 محاولات.» / «…3 محاولات.» / «…محاولتان.» / «…محاولة واحدة.» | error line + shake |
| LG-03 | 4 wrong | 5th wrong | phone locked 5 min | 429 locked_out, Retry-After ≈ 300, «محاولات خاطئة كثيرة. حاول بعد 5 دقائق.» | login button disabled with a countdown |
| LG-04 | phone locked | right PIN | not checked | 429 locked_out | countdown continues |
| LG-05 | first lockout over, 5 more wrong | 5th wrong | 2nd lockout = 3× (15 min) | 429, Retry-After ≈ 900 | — |
| LG-06 | TV count locked out | phone login right / wrong | — | 200 / 401 «بقيت 4 محاولات» (the phone's own count) | — |
| LG-07 | phone locked out | the TV's PIN pad, right PIN | accepted on the TV | — | — |
| LG-08 | phone locked out, then the app restarts (new server, count read back) | login, right PIN | — | 429 (the lockout survives) | — |
| LG-09 | pin_max_failures 3, pin_first_lockout 1 | 3 wrong | phone locked 1 min | 401, 401, then 429 Retry-After ≈ 60 | — |
| LG-10 | pin_max_failures 8 | 8 wrong | phone locked at the 8th | wrong 1..7 → 401 counting down «بقيت 7…1»; NO 429 before the 8th | — |
| LG-11 | — | 2 wrong via `/api/profile`, then 3 wrong via login | one phone count | the 5th wrong → 429 | — |
| LG-12 | phone locked, TV regenerates the token | login (new token), right PIN | — | 200 (counts forgotten) | — |
| LG-13 | no parent PIN stored (defensive) | login | — | 403 rejected «لم يُضبط رمز الوالدين بعد…» | — |
| LG-14 | logged in | logout | session ended | 200 ok «خرجت من قسم الأب.»; the old session → 403 | login card |
| LG-15 | not logged in / unknown session | logout | — | 200 ok (idempotent) | — |

## 5. Parent section

### Q: daily quota (`POST /api/parent/quota`)
Pruned: playback (a quota edit never touches the lock), SM/ESC/AEP, kind of the current profile except where the edited profile is current.

| ID | Preconditions | Action | TV (expected) | HTTP / JSON | Phone UI |
|---|---|---|---|---|---|
| Q-01 | — | C → 30 | C's quota 30 (TV time pane shows it); `rev` changes | 200 ok «حُفظت الحصة اليومية لملف «سارة»: 30 دقيقة.» | value shown |
| Q-02 | — | C → null | no daily limit | 200 ok «ملف «سارة» بلا حد يومي الآن.» | «بلا حد» |
| Q-03 | — | C → a value the TV does not offer (75, 0, 1440) | nothing stored | refused: 400 or 200 ¬ok with an Arabic reason (D3) | the editor offers only the TV's choices: بلا حد, 15, 30, 45, 60, 90, 120, 180, 240 |
| Q-04 | — | unknown profile | nothing | 200 ¬ok «…غير موجود…» | — |
| Q-05 | C current, 40 min used | C → 30 | «انتهى الوقت» at once | 200 ok; status TIME_UP | red banner |
| Q-06 | C current, UP | C → null | time-up screen goes away | 200 ok; status OK, timeLeft = minutes to bedtime | — |
| Q-07 | A current | A → 120 | the adult gets a limit | 200 ok; status timeLeftMinutes 120 | — |
| Q-08 | a video locked | C → 45 | lock unchanged | 200 ok | — |

### UT: open time (`POST /api/parent/unlock-time`, the CURRENT profile)
| ID | Preconditions | extraMinutes | TV (expected) | HTTP / JSON | Phone UI |
|---|---|---|---|---|---|
| UT-01 | C, UP by quota | 15 | time-up screen gone; 15 min to watch; guard stops for time (still on for the child) | 200 ok «أُضيفت 15 دقيقة إلى وقت ملف «سارة».»; status OK 15 | +N buttons = the extra-minutes setting + «بقية اليوم» |
| UT-02 | C, UP by quota | null | no quota for the rest of today | 200 ok «فُتح الوقت لملف «سارة» لبقية اليوم.»; status OK, timeLeft = minutes to 22:00 | — |
| UT-03 | C, OUT (23:00) | 30 | outside-schedule screen gone for 30 min | 200 ok; status OK 30 | — |
| UT-04 | C, OUT (23:00) | null | schedule lifted until the end of the day | 200 ok; status OK (minutes to midnight) | — |
| UT-05 | C, OK 20 left | 15 | 35 min left | 200 ok; status 35 | «أضف وقتاً فوق ما بقي…» |
| UT-06 | C, WARN 4 left | 15 | 19 min left, warning gone | 200 ok; status OK 19 | — |
| UT-07 | A without limits | 15 / null | nothing stored | 200 ok ««الكبار» لا يوقفه شيء الآن.» | hub shows «لا حد يومي ولا أوقات محددة…», no +N buttons |
| UT-08 | C, UP by quota at 21:58 (bedtime in 2 min) | 15 | 15 min to watch (quota AND bedtime granted) | 200 ok; status OK 15 | — |
| UT-09 | C, UP with its video locked (player released) | 15 | the locked video comes back, still locked | 200 ok; status OK, videoLocked true | — |
| UT-10 | A with a quota, UP | 15 | 15 min | 200 ok | — |

### UV: end the lock (`POST /api/parent/unlock-video`)
| ID | Preconditions | TV (expected) | HTTP / JSON | Phone UI |
|---|---|---|---|---|
| UV-01 | nothing locked | nothing | 200 ¬ok «لا يوجد فيديو في الوضع الجاد الآن.» | the hub shows no lock panel |
| UV-02 | C's video locked | lock gone, NO surrender; BACK now leaves the video; guard's lock reason gone | 200 ok «أُنهي الوضع الجاد لهذا الفيديو. يمكن الخروج منه الآن.»; status videoLocked false | lock panel «إنهاء قفل هذا الفيديو» → gone; STOP enabled |
| UV-03 | A's video locked | surrender stored FIRST, then the lock ends | 200 ok «…وسُجّل الخروج قبل نهايته استسلاماً في الإحصائيات.»; stats surrenders +1 | — |
| UV-04 | A's video locked, the surrender cannot be stored | lock stays | 200 ¬ok «تعذّر حفظ إنهاء الوضع الجاد…» | — |
| UV-05 | A's video locked, ESC off | lock ends (the phone is the only early way out; that is why D11 asks it first) | 200 ok | — |
| UV-06 | C's video locked + UP | lock ends; time-up screen stays | 200 ok; status TIME_UP, videoLocked false | — |
| UV-07 | (v1.2) C's video locked; SMa switched off, then SMk | SMa off: C's lock stays; SMk off: it ends, no surrender | videoLocked true, then false | — |
| UV-08 | (v1.2) A's video locked; SMk switched off | A's lock stays; the phone's unlock ends it (a surrender) | 200 ok «…وسُجّل الخروج قبل نهايته استسلاماً…» | — |

### AL: a child's allowlist (`/api/parent/allowlist*`)
| ID | Preconditions | Action | TV (expected) | HTTP / JSON | Phone UI |
|---|---|---|---|---|---|
| AL-01 | — | GET for C | — | 200 {channels, playlists} | lists |
| AL-02 | — | GET for A / unknown | — | 403 rejected «قائمة السماح لملفات الأطفال فقط.» / «هذا الملف غير موجود.» | — |
| AL-03 | C current | add `/channel/UC…` (new) | C's screens show it at once; its videos now play from a phone link | 200 ok «أُضيفت «name» إلى قائمة سماح «سارة».» | — |
| AL-04 | — | add `youtube.com/@handle` | resolved to UC…, added | 200 ok | — |
| AL-05 | — | add a channel already there | nothing | 200 ok «…في قائمة سماح «سارة» من قبل.» | — |
| AL-06 | — | add a playlist link / a video link with `list=` | that playlist added | 200 ok | — |
| AL-07 | — | add a plain video link / not YouTube | nothing | 200 ¬ok «أرسل رابط قناة … أو رابط قائمة تشغيل.» | — |
| AL-08 | — | add to A | nothing | 200 ¬ok «قائمة السماح لملفات الأطفال فقط.» | — |
| AL-09 | — | add a channel YouTube cannot resolve | nothing | 200 ¬ok with the Arabic source error | — |
| AL-10 | C current | remove the channel | C's screens drop it; a phone link to its video → refused | 200 ok «أُزيلت «name» من قائمة سماح «سارة».» | — |
| AL-11 | — | remove an id that is not there | nothing | 200 ¬ok «هذا ليس في قائمة السماح.» | — |
| AL-12 | C's video locked | remove that video's channel | the current video goes on, still locked, until it ends; the next link is refused | 200 ok | — |

### SU: subscriptions, TK: Takeout
| ID | Preconditions | Action | TV (expected) | HTTP / JSON |
|---|---|---|---|---|
| SU-01 | — | GET subscriptions | — | 200 [{channelId, name, avatarUrl}] |
| SU-02 | — | add a channel link | subscribed; feed sync asked; `rev` changes | 200 ok «اشتركت في «name»، وتصل فيديوهاتها…» |
| SU-03 | — | add it again | nothing | 200 ok «أنت مشترك في «name» من قبل.» |
| SU-04 | — | add a video / not-YouTube link | nothing | 200 ¬ok «هذا ليس رابط قناة من يوتيوب.» |
| SU-05 | — | remove a subscribed channel | unsubscribed, its feed items gone | 200 ok «أُلغي الاشتراك في «name».» |
| SU-06 | — | remove one not subscribed | nothing | 200 ¬ok «لست مشتركاً في هذه القناة.» |
| SU-07 | a video locked / C current / UP | add or remove | done (household data, opens nothing) | 200 ok |
| TK-01 | — | raw CSV body (+ X-File-Name) with 3 new channels | 3 subscribed, sync asked | 200 ok «أُضيفت 3 قنوات من Google Takeout…» |
| TK-02 | — | the same as multipart | same | 200 ok |
| TK-03 | — | the whole Takeout `.zip` | its subscriptions.csv imported | 200 ok |
| TK-04 | all present | CSV again | nothing new | 200 ok «كل قنوات الملف موجودة من قبل (…).» |
| TK-05 | — | CSV with some new, some present | new ones added | 200 ok «أُضيفت … و… موجودة من قبل.» |
| TK-06 | — | CSV without any channel row | nothing | 200 ¬ok (no channels found) |
| TK-07 | — | zip without a subscriptions CSV / a broken zip | nothing | 400 «لم أجد ملف subscriptions.csv…» / 400 «تعذّرت قراءة الملف المضغوط.» |
| TK-08 | — | `photo.png` | nothing | 415 «اختر ملف subscriptions.csv أو ملف Takeout المضغوط (zip).» |

### BK: backup
| ID | Preconditions | Action | TV (expected) | HTTP / JSON |
|---|---|---|---|---|
| BK-01 | — | `GET /api/parent/backup` | — | 200 JSON attachment `shasha-backup-<date>.json`, Cache-Control no-store; holds profiles, rules, lists, the PIN HASH (never the PIN), grants, subscriptions, history, settings sections (behavior, sponsorblock, playback, remote); no pairing token, no lock, no PIN counts |
| BK-02 | an export, then the kid renamed | `POST` the export | everything back as exported (the kid's name restored); `rev` changes | 200 ok «استُعيدت النسخة: …» |
| BK-03 | a video locked | `POST` a valid backup | NOTHING replaced | 200 ¬ok «فيديو في الوضع الجاد قيد المشاهدة…ثم استعد النسخة.» |
| BK-04 | — | `POST` a non-backup JSON | nothing | 200 ¬ok «هذا الملف ليس نسخة احتياطية من «شاشة».» |
| BK-05 | — | `POST` a backup of a newer format | nothing | 200 ¬ok «هذه النسخة من إصدار أحدث…» |
| BK-06 | a backup whose PIN hash differs | `POST` it | PIN replaced; every parent session revoked | 200 ok; the next parent call → 403 parent_session |
| BK-07 | the server stops in the middle of a restore | `POST` | the restore still finishes completely | the answer may be lost |
| BK-08 | a backup without the current child | `POST` it | the TV falls back to the owner profile | 200 ok; status ADULT |
| BK-09 | (v1.2) a v1.1 backup (format 2) whose single `serious_mode` is off | `POST` it | both switches off (the old switch sets both); a backup written now is format 3 | 200 ok |

### SET: every TV setting from the phone (`GET/POST /api/parent/settings`)
Common rows:

| ID | Preconditions | Action | TV (expected) | HTTP / JSON | Phone UI |
|---|---|---|---|---|---|
| SET-01 | — | GET | — | 200 page with groups serious, playback, pin, sponsorblock, quality, remote; each setting has value, default, choices/limits as the TV offers | grouped pages |
| SET-02 | — | any change | stored in the TV's store (read back by the TV at once); `rev` changes | 200 {ok:true, settings: the whole page as stored} | «حُفظ» mark |
| SET-03 | — | unknown key | nothing | 200 ¬ok «هذا الإعداد غير موجود على الشاشة. حدّث الصفحة.» + page | — |
| SET-04 | the TV (or phone B) changed the key meanwhile | change with the old `base` | nothing overwritten | 200 ¬ok «تغيّر «…» على الشاشة أو من جوال آخر قبل لحظات…» + page with the TV's value | the TV's value shown |
| SET-05 | — | wrong JSON type (string for a number, number for a switch) | nothing | 200 ¬ok «لم يُحفظ «…»: هذه القيمة غير متاحة على الشاشة.» (never 500) | — |

Per setting (each: valid → saved and the TV behaves accordingly; invalid → SET refused, nothing stored; default as on the TV):

| ID | Key | TV choices / validation | Default | Valid change → TV effect | Invalid (refused) |
|---|---|---|---|---|---|
| SET-10 | serious_mode_children | switch | on | off → a child's current lock ends at once (no surrender), no later child's video locks; the adult's still lock; status seriousMode false in C | `"yes"`, `1` |
| SET-10b | serious_mode_adult | switch | on | off → the adult's current lock ends at once (no surrender), no later adult video locks (nothing asks the phone's «تأكيد»); a child's still lock; the escape settings are hidden | `"no"`, `0` |
| SET-11 | adult_escape | switch (shown while SMa on); a warning: every adult video then needs the phone's «تأكيد» | on | off → no hold escape for the adult; the phone's unlock still works; every adult video asks the phone's «تأكيد» (D11). From the phone it is always allowed (a paired phone asks); on the TV it needs a paired phone (CF-10) | `1` |
| SET-12 | escape_hold_seconds | 3, 5, 10, 15, 20, 30 (shown while SM and ESC on) | 10 | 20 → the TV hold is 20 s | 7 |
| SET-13 | phrase_mode | RANDOM, CUSTOM (CUSTOM only once a custom phrase exists) | RANDOM | CUSTOM after a custom phrase → that phrase is asked | CUSTOM with no custom phrase → 200 ¬ok «اكتب عبارتك الخاصة أدناه…»; the choice shown disabled with that reason |
| SET-14 | phrases | 1–30 phrases of keyboard letters, ≤ 30 chars each | the 10 built-in | 2 new phrases → asked at random | `[]`, a Latin phrase → ¬ok «لم تُحفظ القائمة…» |
| SET-15 | custom_phrase | keyboard letters, ≤ 30 chars | empty | a phrase → stored AND chosen (mode CUSTOM) | Latin / too long |
| SET-16 | skip_allowance_seconds | 0, 10, 30, 60, 120 | 30 | 60 | 45 |
| SET-17 | played_percent | 30, 50, 75, 90, 100 | 50 | 75 | 60 |
| SET-18 | warning_minutes | 0, 1, 2, 3, 5, 10, 15 | 5 | 0 → no warning state at all (ST-17) | 4 |
| SET-19 | warning_banner_seconds | 5, 8, 12, 20, 30 | 12 | 20 | 15 |
| SET-20 | extra_minutes | 1–4 of 5, 10, 15, 20, 30, 45, 60, 90, 120 | 15, 30, 60 | [10, 20] → the TV's time-up choices and the phone hub's buttons are +10, +20 | `[]`, 5 items, `[7]` |
| SET-21 | default_speed | the TV's speeds (0.25…4) | 1 | 1.5 | 1.3 |
| SET-22 | seek_step_seconds | 5, 10, 15, 20, 30, 60 | 10 | 30 | 25 |
| SET-23 | controls_hide_seconds | 2, 3, 5, 8, 10 | 3 | 5 | 4 |
| SET-24 | up_next_seconds | 0, 3, 5, 10, 15 | 5 | 0 → no auto play of the next playlist video | 7 |
| SET-25 | kids_home_seconds | 5, 10, 15, 30, 60 | 10 | 30 | 20 |
| SET-26 | sponsor_undo_seconds | 3, 5, 8, 10, 15 | 5 | 8 | 6 |
| SET-27 | sponsor_notice_seconds | 2, 3, 5, 8 | 3 | 5 | 4 |
| SET-28 | shorts_max_seconds | 60, 90, 120, 180, 240, 300, 600 | 180 | 60 → a 2-min allowlisted video is no longer a short (a child's link plays) | 100 |
| SET-29 | voice_language | ar-SA, en-US | ar-SA | en-US | fr-FR |
| SET-30 | pin_max_failures | 3, 4, 5, 6, 8, 10 | 5 | 3 → both counts lock after 3 (LG-09) | 7 |
| SET-31 | pin_first_lockout_minutes | 1, 2, 5, 10, 15, 30, 60 | 5 | 1 → first lockout 1 min | 3 |
| SET-32 | parent_unlock_minutes | 1, 2, 5, 10, 15, 30 | 5 | 10 → TV settings stay open 10 min | 4 |
| SET-33 | relock_seconds | 5, 10, 15, 30, 60, 120, 300 | 15 | 60 | 45 |
| SET-34 | adult_entry_pin | switch | on | off → entering A needs no PIN (PR-03, PR-09); a warning shown while off | `"off"` |
| SET-35 | sponsor_categories | any subset of the 9 categories (empty = off) | sponsor, selfpromo, interaction, intro, outro | `[]` → SponsorBlock off; `["music_offtopic"]` | `["nope"]` |
| SET-36 | max_quality | 2160, 1440, 1080, 720, 480 | 1080 | 720 → the next video is capped at 720p | 360 |
| SET-37 | remote_port | 1024..65535 (the TV's range), free on the TV | 8765 | a free port → 200 ok with reconnectPort, the server moves after the answer, sessions kept (NW-05); the same port → saved, no move | out of range; a port another app holds → ¬ok «المنفذ … يستخدمه تطبيق آخر…» (nothing moves) |

### RS: reset (`POST /api/parent/settings/reset`)
| ID | Preconditions | group | TV (expected) | HTTP / JSON |
|---|---|---|---|---|
| RS-01 | serious keys changed, playback keys changed | `serious` | only the serious keys back to default | 200 ok «عادت إعدادات «الوضع الجاد» إلى وضعها الأصلي.» + page |
| RS-02 | — | `playback` / `pin` / `sponsorblock` / `quality` | only that group back | 200 ok |
| RS-03 | port 8800 | `remote` | port 8765; the server moves; pages follow | 200 ok + reconnectPort 8765 |
| RS-04 | — | `nope` | nothing | 200 ¬ok «هذا الإعداد غير موجود…» |
| RS-05 | many settings changed (both SM switches off), a quota, an allowlist entry, a subscription, a keyword block | null (global) | EVERY setting back to its default (behavior, SponsorBlock, quality, port), both Serious Mode switches on, adultEntryPin on; profiles, PIN, lists, subscriptions, history untouched | 200 ok «…» + page |
| RS-06 | both SM switches off, an unlocked video playing | global | both switches on; the playing video is NOT retro-locked; the next one locks | 200 ok |

### PF: profiles from the phone (`/api/parent/profiles`, `/profile/*`, `/schedule`)
| ID | Preconditions | Action | TV (expected) | HTTP / JSON |
|---|---|---|---|---|
| PF-01 | — | GET | — | 200 {profiles with quota, schedule, shorts/comments, used today, deleteBlockedReason; quotaChoices = the TV's; avatars; childAvatars; nameMaxLength 20} |
| PF-02 | — | create child «  ريم   الصغيرة » with a child avatar | a child «ريم الصغيرة» with the TV's defaults (60 min, 07:00–22:00, shorts and comments hidden, the household's blocks); shown on the TV's picker | 200 ok «أُنشئ ملف «ريم الصغيرة»…» |
| PF-03 | — | create with 21 letters / an adult-only avatar | nothing | 200 ¬ok «اكتب اسماً من حرف واحد إلى 20 حرفاً.» / «هذه الصورة غير متاحة لهذا الملف.» |
| PF-04 | — | edit C's name / avatar | renamed on the TV at once | 200 ok «حُفظ ملف «…».» |
| PF-05 | — | edit C's avatar to an adult-only one | nothing | 200 ¬ok avatar not available |
| PF-06 | C2 not current | delete C2 | C2 and its history/progress/stats gone | 200 ok «حُذف ملف «…» مع سجلّه وإحصائياته.» |
| PF-07 | — | delete the current profile | nothing | 200 ¬ok «هذا الملف على الشاشة الآن، فلا يُحذف…» |
| PF-08 | C current | delete the only adult | nothing | 200 ¬ok «هذا آخر ملف للكبار، فلا يُحذف.» |
| PF-09 | — | C hideShorts false / A hideComments true | stored; lists re-filter | 200 ok «حُفظ لـ«…».» |
| PF-10 | — | C hideComments false | nothing (children never get comments) | 200 ¬ok «التعليقات مخفية دائماً في ملفات الأطفال.» |
| PF-11 | — | schedule C 08:00–20:00 / same time twice / both null / until 00:00 | 08:00–20:00 / all day / all day / until midnight | 200 ok «أوقات «سارة»: من … إلى ….» / «…المشاهدة مسموحة طوال اليوم.» |
| PF-12 | C current at 12:00 | schedule C 13:00–14:00 | outside-schedule screen at once | 200 ok; status OUTSIDE_SCHEDULE |
| PF-13 | a video locked | any profile edit (not a delete of the current) | done; lock unchanged | 200 ok |

### BL: household blocklist, SS: stats
| ID | Preconditions | Action | TV (expected) | HTTP / JSON |
|---|---|---|---|---|
| BL-01 | — | GET | — | 200 {channels, keywords} |
| BL-02 | — | add keyword «رعب» | every profile hides titles with it; a phone link to such a video → refused BLOCKED KEYWORD | 200 ok «حُظرت الكلمة «رعب» في كل الملفات.» |
| BL-03 | — | add it again (another spelling that normalizes the same) | nothing | 200 ok «…محظورة من قبل.» |
| BL-04 | — | remove it / remove one not there | unblocked / nothing | 200 ok «أُزيلت الكلمة…» / 200 ¬ok «هذه الكلمة ليست في قائمة الحظر.» |
| BL-05 | — | block a channel link | every profile; its video link → refused BLOCKED CHANNEL | 200 ok «حُظرت القناة «…» في كل الملفات.» |
| BL-06 | — | block a non-channel link | nothing | 200 ¬ok «هذا ليس رابط قناة من يوتيوب.» |
| BL-07 | — | unblock it / one not blocked | unblocked / nothing | 200 ok / 200 ¬ok «هذه القناة ليست في قائمة الحظر.» |
| SS-01 | usage recorded; an adult lock ended from the phone | GET stats | — | 200 per profile: 7 days (today last), this/previous week, change %, top channels, surrenders (the phone's unlock counted) |

## 6. TV-side events that meet the phone

| ID | Preconditions | TV event | TV (expected) | Phone sees (HTTP) | Phone UI |
|---|---|---|---|---|---|
| TV-01 | C current, phone parent logged in | the parent types the PIN on the TV («خروج») and picks A | A current | next status ADULT; parent session still valid | who-chip changes; search unlocked |
| TV-02 | C current, a phone link checked for C in flight | the TV switches to another profile before it opens | the other profile opens nothing | 200 ¬ok «تغيّر الملف على الشاشة…» | — |
| TV-03 | C playing, quota runs out | time-up screen; player released; lock (if any) kept | status TIME_UP, nowPlaying null; links → TIME UP; controls → nothing playing | banner + «فتح الوقت» |
| TV-04 | C, 4 min left | warning starts | status WARNING | warning banner |
| TV-05 | C at 21:59 → 22:00 | bedtime | status OUTSIDE_SCHEDULE | schedule banner |
| TV-06 | C's single video locked | it ends by itself | lock released | status videoLocked false; STOP allowed | — |
| TV-07 | C's playlist video locked | it ends, the next follows | the lock moves to the next video | videoLocked stays true | — |
| TV-08 | A's video locked | the adult holds BACK 10 s and types the phrase | lock ends; surrender recorded | videoLocked false; stats +1 | — |
| TV-09 | A's video locked, ESC off | the adult holds BACK | nothing (no escape; the video had asked the phone's «تأكيد» before it started, D11) | videoLocked true | — |
| TV-10 | a video locked | that kind's Serious Mode switched off on the TV (the other kind's switch leaves it: UV-07/08) | lock ends, no surrender | status seriousMode false, videoLocked false; `rev` changes | — |
| TV-11 | — | the guard (D9) after phone actions: A→C switch / C→A switch with PIN / +15 on C's time-up / unlock of A's lock | enforce: on (child) / off (adult, no lock, no time limit) / still on (child) / off | — | — |
| TV-12 | the TV changes a setting | — | stored | `rev` changes; a phone change with the old base → refused (SET-04) | settings page re-read |
| TV-13 | — | the TV regenerates the pairing token | old QR dead | every phone 401 pairing at once; sessions revoked; PIN counts forgotten | fatal overlay |
| TV-14 | phone parent logged in | the parent PIN changed on the TV | — | parent route → 403 parent_session; login with the new PIN → 200 | PIN sheet |
| TV-15 | A current, children exist | the TV is out of sight ≥ relock time, then back | «من يشاهد الآن؟» (the adult asks the PIN) | a phone link → 200 ¬ok «اختر الملف على الشاشة أولاً…»; a phone switch to a child opens it (PR-18) | — |
| TV-16 | a child guessed 5 wrong PINs on the TV | — | the TV's PIN pads locked | phone login and phone switch with the right PIN work (LG-06, PR-14) | — |
| TV-17 | (v1.2 finding) C's video locked; then A's | the parent PIN on the TV: BACK's PIN pad, or the picker with the PIN | C's lock ends either way; A's never opens with the PIN (only the escape or the phone) | videoLocked false for C, true for A | — |

## 6b. «تأكيد» from the phone (v1.2, D11)

| ID | Preconditions | Event / action | TV (expected) | Phone sees (HTTP) | Phone UI |
|---|---|---|---|---|---|
| CF-01 | a video waits for «تأكيد» | `POST /api/parent/confirm {id, approve:true}` without, then with, a parent session | nothing, then the video starts | 401 parent_session; then 200 ok «أُكّد: يبدأ «title» على الشاشة الآن.»; status confirmation {id, title, secondsLeft ≤ 120} until then, null after | UI-22 |
| CF-02 | a video waits for «تأكيد» | approve false | nothing plays; the player leaves with «رُفض تشغيل الفيديو من الجوال.» | 200 ok «رُفض: لن يُشغَّل «title».» | — |
| CF-03 | answered by another phone / withdrawn on the TV / an unknown id | an answer | nothing changes | 200 ¬ok «لم يعد هذا الطلب ينتظر على الشاشة.» | — |
| CF-04 | A, SMa on, ESC off | a video starts on the TV | nothing plays: «لتشغيل هذا الفيديو في الوضع الجاد، أكّد من الجوال» with the title, a waiting indicator with the time left, «إلغاء» focused | status confirmation set | card + sheet |
| CF-05 | as CF-04, no phone paired or none polled in 60 s | — | «لا يوجد جوال متصل؛ اربط الجوال من الإعدادات ← التحكم من الجوال، أو فعّل طريقة الخروج من الشاشة», «إلغاء» and «ربط الجوال» (the PIN, then the pairing screen); it keeps waiting and turns into CF-04 when a page opens | — | — |
| CF-06 | CF-04 waiting | «إلغاء» on the TV | the player leaves; the question is withdrawn at once | confirmation null; an answer → CF-03 | card gone |
| CF-07 | CF-04 waiting | 2 minutes without an answer | nothing plays; «لم يُؤكَّد الفيديو من الجوال خلال دقيقتين، فلم يُشغَّل.» | confirmation null | card gone |
| CF-08 | C (any), or A with ESC on, or A with SMa off | a video starts | it starts at once, nothing asked (a child's lock ends with the PIN on the TV, TV-17) | confirmation null | — |
| CF-09 | the lock already holds the video (back from Home, a restart, a locked playlist's next video), or the phone sent it (PL-39) | the player opens | no «تأكيد» | — | — |
| CF-10 | no paired phone | the TV's Settings turn ESC off | refused: ESC stays on, «لا يوجد جوال مقترن، فبقي الخروج من الشاشة مفعّلاً…»; with a paired phone it turns off and its note says every adult video will ask the phone | — | — |
| CF-11 | ESC off, a video ended (its lock with it) | «إعادة التشغيل» on the end card | a new start: the player opens afresh and asks again (CF-04) | — | — |

## 7. Two phones (R7)

| ID | Preconditions | Action | TV (expected) | HTTP / JSON | Phone UI |
|---|---|---|---|---|---|
| P2-01 | A and B paired | both poll; A pauses | paused | both statuses isPlaying false | both show it within 2 s |
| P2-02 | A logged in, B not | B calls a parent route | nothing | 401 parent_session | B: login card |
| P2-03 | both logged in | A logs out | — | A's session 403, B's 200 | — |
| P2-04 | both on the settings page | A changes warning_minutes; B then changes it from the old value | A's value kept | B: 200 ¬ok changed-elsewhere + the TV's value | B shows A's value |
| P2-05 | — | B (a child's phone) guesses 5 wrong PINs | the phone count locks (shared) | A's login → 429 too (accepted limit: one count per token); after «إعادة توليد الرمز» both counts forgotten | explanation shown on the TV's remote screen |
| P2-06 | a video playing | A sends PAUSE, B sends PLAY right after | the last command wins (playing) | both 200 ok | each page reads the TV's state after its own command |
| P2-07 | C UP | A and B each send +15 | 30 min granted in total | both 200 ok | — |
| P2-08 | both open | the port moves | — | both statuses carry movingTo; both follow; sessions kept | "moving" overlay on both |
| P2-09 | A offline | B acts | — | B unaffected | — |
| P2-10 | both logged in | A restores a backup with another PIN hash | — | both sessions → 403 | both: PIN sheet |
| P2-11 | A changes a profile rule | — | — | B's next status `rev` differs | B re-reads |

## 8. Network, restarts, port

| ID | Preconditions | Event / action | TV (expected) | HTTP / JSON | Phone UI |
|---|---|---|---|---|---|
| NW-01 | the phone loses Wi-Fi | polls fail | — | no answer | after 2 failed polls the offline banner «تعذّر الوصول إلى الشاشة…» with «أعد»; polls back off to ≤ 10 s; actions toast the same text |
| NW-02 | the TV loses its network | — | server stopped (no address), parent sessions end | no answer | offline banner |
| NW-03 | the TV's network returns on the same address | — | server bound again (same port preferred, same token) | status 200 with the old token; parent route with the old session → 403 | page recovers; PIN sheet for the parent section |
| NW-04 | the TV gets a new address | — | server rebinds on the new address; a new QR | old URL: no answer | offline banner (needs the new QR) |
| NW-05 | — | the phone moves the port (SET-37) | answer first, then the server moves; old port closes | 200 ok + reconnectPort; the new port answers the same token and the same parent session | "moving" overlay, then the page on the new port, still in the parent section |
| NW-06 | — | the TV moves the port | status carries movingTo for ~2.5 s, then the move | old port closes | pages follow |
| NW-07 | a write in flight (restore) | the server stops | the write completes | answer lost | after reconnect the state is read again |
| NW-08 | — | app process restart | token kept (DataStore); sessions gone; PinGate forgotten; the phone's lockout kept | status 200; parent → 403; login during a lockout → 429 | — |
| NW-09 | PinGate blocked | server stop()/start() on the same object | PinGate still blocked; sessions gone | login → 429 | — |
| NW-10 | the preferred port is taken | start | falls back to 8765…8774 | the QR shows the bound port | — |

## 9. Phone page UI (Playwright, Chromium, 390 px wide)

| ID | Preconditions | Expected on the page |
|---|---|---|
| UI-01 | C current | «بحث» tab shows a lock; the search view shows the explanation card (#search-off), no input |
| UI-02 | a video locked | STOP looks disabled (aria-disabled="true") and a tap opens the «الوضع الجاد» explanation (no request); the lock line is visible |
| UI-03 | a video locked | the link box is disabled with the Serious Mode reason (#send-why); paste/send off |
| UI-04 | a video locked | other profile tiles aria-disabled; banner «لا يُبدَّل الملف أثناء فيديو مقفول»; a tap explains |
| UI-05 | C, UP | banner «انتهى وقت المشاهدة اليوم» + «فتح الوقت»; link box disabled with the time-up reason; idle text «انتهى وقت المشاهدة اليوم، والشاشة مقفولة.» |
| UI-06 | C, OUT | banner «خارج أوقات المشاهدة المسموحة»; link box disabled with the schedule reason |
| UI-07 | C, WARN | banner «الوقت يوشك على الانتهاء» with the minutes |
| UI-08 | a live video | ±10 buttons aria-disabled; a tap explains |
| UI-09 | idle | «لا شيء يُعرض الآن على الشاشة.»; no controls |
| UI-10 | phone count locked | login button disabled with a countdown «محاولات خاطئة كثيرة. حاول بعد …» |
| UI-11 | logged in, a video locked | hub card shows «فيديو مقفول بالوضع الجاد» + «إنهاء قفل هذا الفيديو»; tapping it ends the lock (the panel goes away) |
| UI-12 | logged in, C with limits | hub +N buttons = the extra-minutes setting (+15, +30, +60) + «بقية اليوم»; with extra_minutes [10, 20] → +10, +20; an unlimited profile → explanation, no buttons |
| UI-13 | logged in, a SM switch off | the adult's off: the escape settings are hidden; the serious summary says which switch is off |
| UI-14 | logged in, no custom phrase | «عبارتي الخاصة» choice shown disabled with «اكتب عبارتك الخاصة…» |
| UI-15 | logged in, extra_minutes has 4 values | other chips aria-disabled with «اخترت 4 خيارات، وهو الحد…» |
| UI-16 | logged in, a section at defaults | its reset button disabled «كلها على وضعها الأصلي» |
| UI-17 | logged in, profiles page | delete disabled with its reason for the current profile and the last adult; a child's comments switch disabled with its reason; the quota editor offers only the TV's choices |
| UI-18 | wrong token | fatal overlay asking to scan the QR |
| UI-19 | server unreachable | offline banner |
| UI-20 | logged in, AEP off | the PIN section summary warns «ملف الكبار يُفتح دون رمز» |
| UI-21 | every view above | no horizontal overflow at 390 px |
| UI-22 | (v1.2) a video waits for «تأكيد» (CF-01) | card «تأكيد تشغيل: <title>» on the screen tab with «تأكيد» / «رفض» and the time left; once as a sheet; without a parent session the PIN sheet comes first; gone once answered or withdrawn |
| UI-23 | (v1.2) a link sent | the sheet follows it live: «بانتظار القبول على الشاشة» with the seconds, then «قُبل» / «رُفض» / «انتهت المهلة دون رد» / «لم يُفتح على الشاشة»; a refused link «لم يُرسَل إلى الشاشة» at once; a closed sheet's outcome comes as a toast |
| UI-24 | (v1.2) a link waiting, a newer one sent from another phone | the first page's sheet: «حلّ محلّه طلب أحدث على الشاشة.» |

## 10. TV settings not mirrored on the phone (pruned on purpose; owner to confirm)

| TV setting | Why the phone does not offer it |
|---|---|
| «تشغيل الخادم» (remote on/off) | Switching it off from the phone would cut every phone off with no way back from the phone. |
| «إعادة توليد الرمز» | It unpairs the phone that asks; the new token is only on the TV's QR. |
| «تغيير رمز الوالدين» | The TV asks for the current PIN on the TV; a PIN change from a paired phone would let one phone lock the others out of the parent section. |
| Protection status, updates, diagnostics, about | Information and TV actions, not settings. |
