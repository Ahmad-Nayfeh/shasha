#!/usr/bin/env bash
# «شاشة» (Shasha): the installer for macOS (double-click this file) and Linux (install-linux.sh runs it).
# It installs the app on the TV over the network and grants the permissions that only ADB can grant
# (SPEC section 4, "the guard outside the app"), using the adb bundled in tools/ (Google's Platform-Tools).
# Step by step: the guide next to this file, «دليل شاشة.pdf» (source: docs/guide). Details: README.md in the
# Shasha-Setup folder of the repository.
#
# First time on a Mac: double-click this file. On macOS 15 (Sequoia) and later, right-click > Open no longer
# gets past the warning: click Done, then System Settings > Privacy & Security > scroll down > «Open Anyway»,
# and confirm. (Or run it from Terminal: bash, a space, drag this file in, Enter.)
#
#   double-click, or: bash "Mac - ثبّت شاشة.command"            asks for the TV's address
#   bash "Mac - ثبّت شاشة.command" <TV_IP[:port]> [path/to/shasha.apk]
#   bash "Mac - ثبّت شاشة.command" --undo [TV_IP[:port]]
#
# macOS's own bash 3.2 is enough. Started with sh or zsh, the script runs itself again with bash.
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail

readonly PKG=com.ahmadnayfeh.shasha
readonly ACTIVITY="$PKG/.MainActivity"
readonly YT_PKG=com.google.android.youtube.tv
# The app starts its guard itself (at its start and after a reboot); this script only reports whether it runs.
readonly GUARD="$PKG/.lock.GuardService"
# SYSTEM_ALERT_WINDOW: the guard draws over other apps. GET_USAGE_STATS: it sees which app is in
# front. REQUEST_INSTALL_PACKAGES: the in-app updater may open the system installer.
OPS=(SYSTEM_ALERT_WINDOW GET_USAGE_STATS REQUEST_INSTALL_PACKAGES)
# The guard's two permissions behind those app-ops. They are "development" permissions: an install with
# "-g" (older versions of this script) grants them for good, and an app-op reset to default then falls
# back to them, so --undo revokes them too.
GUARD_PERMS=(android.permission.SYSTEM_ALERT_WINDOW android.permission.PACKAGE_USAGE_STATS)
readonly CONNECT_TIMEOUT=60
# Wrong answers in a row (an address, a pairing code) before the script gives up.
readonly MAX_TRIES=5

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# What the user started: install-linux.sh passes its own path.
LAUNCHER=${SHASHA_LAUNCHER:-$SCRIPT_DIR/$(basename "${BASH_SOURCE[0]}")}
ADB=""
ADB_VERSION=""
BROKEN_ADB=""
SERIAL=""
ADDRESS=""
CODE=""
# Started without an address (double-click): wait for a key before the window closes, even after an error.
INTERACTIVE=false
STOP_ADB_SERVER=false

say()  { printf '\n==> %s\n    %s\n' "$1" "$2"; }
info() { printf '    %s\n' "$@"; }
warn() { printf '\n!!  %s\n    %s\n' "$1" "$2" >&2; }
die()  { printf '\nERROR: %s\n       %s\n' "$1" "$2" >&2; exit 1; }

finish() {
  local rc=$?
  trap - EXIT
  # The bundled adb's background server would otherwise keep running from this folder.
  if [ "$STOP_ADB_SERVER" = true ]; then "$ADB" kill-server < /dev/null > /dev/null 2>&1 || true; fi
  if [ "$INTERACTIVE" = true ] && [ -t 0 ]; then
    printf '\n    %s\n    %s\n' "Press any key to close." "اضغط أي زر للإغلاق."
    read -r -s -n 1 || true
    printf '\n'
  fi
  exit "$rc"
}
trap finish EXIT
trap 'INTERACTIVE=false; exit 130' INT

usage() {
  local me
  me=$(basename "$LAUNCHER")
  cat <<EOF
Usage:
  bash "$me"                          asks for the TV's address, then installs
  bash "$me" <TV_IP> [shasha.apk]     install Shasha and grant its permissions
  bash "$me" --undo [TV_IP]           re-enable YouTube, reset the permissions, stop the guard
TV_IP may carry a port (default 5555): 192.168.1.50 or 192.168.1.50:5555.
Without an APK path, the newest shasha-*.apk next to this file or in the current folder is used.

الاستخدام:
  bash "$me"                                   يسأل عن عنوان الشاشة ثم يثبّت
  bash "$me" <عنوان IP للشاشة> [ملف APK]         تثبيت شاشة ومنح الصلاحيات
  bash "$me" --undo [عنوان IP للشاشة]            التراجع: إعادة يوتيوب وإلغاء الصلاحيات وإيقاف الحارس
EOF
}

# ─── adb ─────────────────────────────────────────────────────────────────────────────────────
# The adb bundled with this kit first, then one on PATH, then the usual SDK places.
adb_candidates() {
  case "$(uname -s)" in
    Darwin) printf '%s\n' "$SCRIPT_DIR/tools/mac/adb" ;;
    Linux) printf '%s\n' "$SCRIPT_DIR/tools/linux/adb" ;;
  esac
  command -v adb 2> /dev/null || true
  printf '%s\n' "$SCRIPT_DIR/platform-tools/adb" "$PWD/platform-tools/adb" \
    "${ANDROID_HOME:-/nonexistent}/platform-tools/adb" \
    "$HOME/Library/Android/sdk/platform-tools/adb" "$HOME/Android/Sdk/platform-tools/adb"
}

# Sets ADB to the first candidate that runs. BROKEN_ADB: the first one found that does not run
# (macOS blocked it, or it is built for another processor).
find_adb() {
  local c
  while IFS= read -r c; do
    [ -f "$c" ] || continue
    # A ZIP extracted without file modes loses the executable bit: give it back to the bundled adb.
    if [ ! -x "$c" ] && [[ "$c" == "$SCRIPT_DIR/tools/"* ]]; then chmod +x "$c" 2> /dev/null || true; fi
    [ -x "$c" ] || continue
    if ADB_VERSION=$("$c" version < /dev/null 2>&1); then
      ADB=$c
      return 0
    fi
    [ -n "$BROKEN_ADB" ] || BROKEN_ADB=$c
  done < <(adb_candidates)
  return 1
}

adb_missing_help() {
  local expected=$SCRIPT_DIR/tools/linux/adb
  [ "$(uname -s)" != Darwin ] || expected=$SCRIPT_DIR/tools/mac/adb
  warn "The installer's adb was not found." "لم يُعثر على أداة adb الخاصة بالمثبّت."
  info "Expected / المتوقع: $expected" \
    "- Extract the WHOLE Shasha-Setup.zip (double-click it), then run the installer" \
    "  from the extracted Shasha-Setup folder." \
    "  فك ضغط Shasha-Setup.zip كاملاً (انقره مرتين)، ثم شغّل المثبّت من مجلد Shasha-Setup الناتج." \
    "- Keep the installer next to its «tools» folder and the .apk file; do not move it alone." \
    "  أبقِ المثبّت بجانب مجلد «tools» وملف التطبيق ‎.apk، ولا تنقله وحده."
}

adb_broken_help() {
  warn "adb could not start: $BROKEN_ADB" "تعذّر تشغيل أداة adb."
  [ -z "$ADB_VERSION" ] || info "$ADB_VERSION"
  if [ "$(uname -s)" = Darwin ]; then
    info "macOS may have blocked it. / ربما منعها نظام ماك:" \
      "1. Open System Settings > Privacy & Security." \
      "   افتح إعدادات النظام (System Settings) ← الخصوصية والأمان (Privacy & Security)." \
      "2. Scroll down: next to the message about «adb», click «Allow Anyway» (or «Open Anyway»)." \
      "   انزل إلى الأسفل، وبجانب الرسالة عن «adb» اضغط «Allow Anyway» (أو «Open Anyway»)." \
      "3. Run the installer again; if macOS asks, click «Open»." \
      "   شغّل المثبّت من جديد، وإن سألك ماك فاضغط «Open»."
  else
    info "The bundled adb is for 64-bit Intel/AMD Linux. On other computers install adb" \
      "yourself (for example: sudo apt install adb), then run the installer again." \
      "أداة adb المرفقة للينكس 64 بت (Intel/AMD). على غيره ثبّت adb بنفسك ثم أعد تشغيل المثبّت."
  fi
}

# Newest shasha-*.apk next to the script or in the current folder (empty if none).
find_apk() {
  local newest="" f
  for f in "$SCRIPT_DIR"/shasha-*.apk "$PWD"/shasha-*.apk; do
    [ -f "$f" ] || continue
    if [ -z "$newest" ] || [ "$f" -nt "$newest" ]; then newest=$f; fi
  done
  if [ -n "$newest" ]; then printf '%s\n' "$newest"; fi
}

# ─── what the user types ─────────────────────────────────────────────────────────────────────
# Drops spaces and quotes; turns Arabic-Indic digits (٠-٩, ۰-۹) and the Arabic decimal point (٫)
# into 0-9 and ".", as an Arabic keyboard may type them.
clean_input() {
  local s=$1 i
  local arabic=(٠ ١ ٢ ٣ ٤ ٥ ٦ ٧ ٨ ٩) persian=(۰ ۱ ۲ ۳ ۴ ۵ ۶ ۷ ۸ ۹)
  for i in 0 1 2 3 4 5 6 7 8 9; do
    s=${s//${arabic[i]}/$i}
    s=${s//${persian[i]}/$i}
  done
  s=${s//٫/.}
  s=${s//[[:space:]]/}
  s=${s//\"/}
  s=${s//\'/}
  printf '%s\n' "$s"
}

# $1 = the address, $2 = "port" when a port is required. True for a.b.c.d (each part 0-255)
# with an optional :port (1-65535).
valid_address() {
  local addr=$1 need=${2:-} ip port o
  local re_ip='^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$' re_port='^[0-9]{1,5}$'
  ip=${addr%%:*}
  if [ "$ip" != "$addr" ]; then
    port=${addr#*:}
    [[ "$port" =~ $re_port ]] || return 1
    ((10#$port >= 1 && 10#$port <= 65535)) || return 1
  elif [ "$need" = port ]; then
    return 1
  fi
  [[ "$ip" =~ $re_ip ]] || return 1
  local IFS=.
  for o in $ip; do
    ((10#$o <= 255)) || return 1
  done
  return 0
}

# Reads an address into ADDRESS. $1 = "port" when a port is required, $2 = the label before the
# answer. False at the end of the input or after MAX_TRIES wrong answers.
read_address() {
  local need=$1 label=$2 tries=0 raw
  while [ "$tries" -lt "$MAX_TRIES" ]; do
    tries=$((tries + 1))
    raw=""
    IFS= read -r -p "    $label" raw || [ -n "$raw" ] || return 1
    ADDRESS=$(clean_input "$raw")
    [ -n "$ADDRESS" ] || continue
    if valid_address "$ADDRESS" "$need"; then return 0; fi
    if [ "$need" = port ]; then
      warn "\"$ADDRESS\" is not an address with a port, like 192.168.1.50:37215." \
        "\"$ADDRESS\" ليس عنواناً بمنفذ مثل 192.168.1.50:37215. اكتبه كما يظهر في الشاشة."
    else
      warn "\"$ADDRESS\" is not an IP address like 192.168.1.50." \
        "\"$ADDRESS\" ليس عنوان IP مثل 192.168.1.50. اكتبه كما يظهر في الشاشة."
    fi
  done
  return 1
}

# Reads the 6-digit Wireless debugging pairing code into CODE (false as read_address).
read_code() {
  local tries=0 raw re='^[0-9]{6}$'
  while [ "$tries" -lt "$MAX_TRIES" ]; do
    tries=$((tries + 1))
    raw=""
    IFS= read -r -p "    Code / الرمز: " raw || [ -n "$raw" ] || return 1
    CODE=$(clean_input "$raw")
    [ -n "$CODE" ] || continue
    if [[ "$CODE" =~ $re ]]; then return 0; fi
    warn "The code has 6 digits, like 123456." "الرمز 6 أرقام، مثل 123456."
  done
  return 1
}

# A y/N question; $1 = the label before the answer. Only y, yes or نعم mean yes; Enter means no.
ask_yes_no() {
  local answer=""
  info "Type y (yes) or n (no), then press Enter. / اكتب y (نعم) أو n (لا) ثم اضغط Enter."
  read -r -p "    $1" answer || true
  answer=$(clean_input "$answer")
  case "$answer" in
    [yY] | [yY][eE][sS] | نعم) return 0 ;;
    *) return 1 ;;
  esac
}

ask_tv_address() {
  say "Type the TV's IP address (Settings > Network & Internet > your Wi-Fi, or Ethernet > under «Connected»)" \
    "اكتب عنوان IP للشاشة (الإعدادات ← الشبكة والإنترنت ← شبكتك، أو Ethernet ← تحت «Connected»)"
  info "Example / مثال: 192.168.1.50"
  read_address "" "IP: "
}

# ─── the TV ──────────────────────────────────────────────────────────────────────────────────
# adb reads stdin unless told not to; </dev/null keeps it away from the answers typed here.
tv_shell() { "$ADB" -s "$SERIAL" shell "$@" < /dev/null | tr -d '\r'; }

device_state() {
  "$ADB" devices < /dev/null 2> /dev/null | tr -d '\r' | awk -v s="$SERIAL" '$1 == s { print $2 }'
}

is_installed() {
  local out
  out=$(tv_shell pm list packages "$1" || true)
  grep -qx "package:$1" <<< "$out"
}

# "Connection refused" (Windows: error 10061): the TV answered, but nothing listens on that port.
is_refused() { grep -qiE 'refused|10061' <<< "$1"; }

allow_hint() {
  say "Look at the TV: it asks «Allow USB debugging?»" \
    "انظر إلى الشاشة: تظهر نافذة «Allow USB debugging?» (السماح بتصحيح الأخطاء)."
  info "With the remote, tick «Always allow from this computer», then press OK." \
    "بالريموت: فعّل «Always allow from this computer» (السماح دائماً من هذا الحاسوب) ثم اضغط OK." \
    "Waiting up to ${CONNECT_TIMEOUT} s ... / بانتظار الموافقة حتى ${CONNECT_TIMEOUT} ثانية ..."
}

# One attempt of up to CONNECT_TIMEOUT seconds. Returns 0 when connected, 1 when the TV refused
# (nothing listens on that port), 2 when nothing answered, 3 when the TV did not allow this computer.
try_connect() {
  say "Connecting to $SERIAL ..." "الاتصال بالشاشة $SERIAL ..."
  if [[ "$ADB" == "$SCRIPT_DIR/tools/"* ]]; then STOP_ADB_SERVER=true; fi
  "$ADB" start-server < /dev/null > /dev/null 2>&1 || true
  local state out refused=false start=$SECONDS last_try=$SECONDS hinted=false
  state=$(device_state)
  # A connection an earlier run left "unauthorized" or "offline" does not ask the TV again: start anew.
  if [ -n "$state" ] && [ "$state" != device ]; then
    "$ADB" disconnect "$SERIAL" < /dev/null > /dev/null 2>&1 || true
  fi
  out=$("$ADB" connect "$SERIAL" < /dev/null 2>&1 || true)
  if [ -n "$out" ]; then info "$out"; fi
  if is_refused "$out"; then refused=true; fi
  while :; do
    state=$(device_state)
    case "$state" in
      device)
        info "Connected." "تم الاتصال."
        return 0
        ;;
      unauthorized)
        if [ "$hinted" = false ]; then
          allow_hint
          hinted=true
        fi
        ;;
      *)
        if [ "$refused" = true ]; then return 1; fi
        # Not listed yet, or "offline": ask again every 6 s.
        if [ $((SECONDS - last_try)) -ge 6 ]; then
          if [ "$state" = offline ]; then "$ADB" disconnect "$SERIAL" < /dev/null > /dev/null 2>&1 || true; fi
          out=$("$ADB" connect "$SERIAL" < /dev/null 2>&1 || true)
          if is_refused "$out"; then refused=true; fi
          last_try=$SECONDS
        fi
        ;;
    esac
    if [ $((SECONDS - start)) -ge "$CONNECT_TIMEOUT" ]; then
      if [ "$state" = unauthorized ]; then return 3; fi
      return 2
    fi
    sleep 2
  done
}

unauthorized_help() {
  warn "The TV did not allow this computer (state: unauthorized)." "لم تسمح الشاشة لهذا الحاسوب بعد."
  info "- Run the installer again and watch the TV: when «Allow USB debugging?» appears," \
    "  tick «Always allow from this computer» and press OK (within ${CONNECT_TIMEOUT} seconds)." \
    "  شغّل المثبّت من جديد وراقب الشاشة: عند ظهور «Allow USB debugging?» فعّل" \
    "  «Always allow from this computer» واضغط OK (خلال ${CONNECT_TIMEOUT} ثانية)." \
    "- Still stuck: turn «USB debugging» off and on again on the TV, then run the installer again." \
    "  إن استمرت المشكلة: أطفئ «USB debugging» في الشاشة ثم شغّله، وأعد تشغيل المثبّت."
}

unreachable_help() {
  warn "Could not reach the TV at $SERIAL (state: ${1:-not found})." "تعذّر الوصول إلى الشاشة على $SERIAL."
  info "- Is the TV on, and on the SAME home network as this computer (Wi-Fi or cable)?" \
    "  هل الشاشة تعمل، وعلى شبكة البيت نفسها التي عليها الحاسوب (Wi-Fi أو سلك)؟" \
    "- Is the address right? TV Settings > Network & Internet > your network > IP address." \
    "  هل العنوان صحيح؟ الإعدادات ← الشبكة والإنترنت ← شبكتك ← IP address." \
    "  With a cable: Network & Internet > Ethernet > the number under «Connected»." \
    "  بالسلك: الشبكة والإنترنت ← Ethernet ← الرقم تحت «Connected»." \
    "- Developer options > «USB debugging» (and «Network debugging», if present) must be ON." \
    "  يجب تفعيل خيارات المطوّر ← «USB debugging» (و«Network debugging» إن وُجد)." \
    "- Still stuck: run   \"$ADB\" kill-server   then try again." \
    "  إن استمرت المشكلة: نفّذ الأمر السابق ثم أعد المحاولة."
}

refused_help() {
  warn "The TV refused the connection on $SERIAL." "رفضت الشاشة الاتصال على $SERIAL."
  info "- Most often «USB debugging» is off. On the TV: Settings > System > Developer options:" \
    "  turn on «USB debugging» (and «Network debugging» if it exists), then run the installer again." \
    "  غالباً «USB debugging» غير مفعّل. في الشاشة: الإعدادات ← النظام ← خيارات المطوّر:" \
    "  فعّل «USB debugging» (و«Network debugging» إن وُجد)، ثم شغّل المثبّت من جديد." \
    "- Some TVs only have «Wireless debugging»: the installer can pair with it using a 6-digit code." \
    "  بعض الشاشات ليس فيها إلا «Wireless debugging»: يستطيع المثبّت الإقران بها برمز من 6 أرقام."
}

# After "adb pair": the address Wireless debugging listens on, as found on the network (mDNS).
discover_connect_address() {
  local ip=$1 found="" i
  for i in 1 2 3 4 5; do
    found=$("$ADB" mdns services < /dev/null 2> /dev/null | tr -d '\r' |
      awk -v ip="$ip" '/adb-tls-connect/ { for (i = 1; i <= NF; i++) if (index($i, ip ":") == 1) { print $i; exit } }' || true)
    if [ -n "$found" ] && valid_address "$found" port; then
      printf '%s\n' "$found"
      return 0
    fi
    sleep 2
  done
  return 1
}

# Wireless debugging: pair with the 6-digit code, then connect to the address it listens on.
pair_and_connect() {
  local out pair_address pair_ip tries=0 rc
  say "Pairing with a code (Wireless debugging)" "الإقران برمز (Wireless debugging)"
  info "On the TV: Settings > System > Developer options > «Wireless debugging»: turn it on," \
    "then choose «Pair device with pairing code». The TV shows a 6-digit code and an address." \
    "في الشاشة: الإعدادات ← النظام ← خيارات المطوّر ← «Wireless debugging»: فعّله،" \
    "ثم اختر «Pair device with pairing code». تعرض الشاشة رمزاً من 6 أرقام وعنواناً."
  while :; do
    tries=$((tries + 1))
    [ "$tries" -le 3 ] || die "Pairing failed 3 times." "فشل الإقران 3 مرات. تأكد من الرمز ثم شغّل المثبّت من جديد."
    say "Type the address shown under the code, like 192.168.1.50:37215" \
      "اكتب العنوان الظاهر تحت الرمز، مثل 192.168.1.50:37215"
    read_address port "Pairing address / عنوان الإقران: " || exit 1
    pair_address=$ADDRESS
    info "Type the 6-digit code shown on the TV. / اكتب الرمز المكوّن من 6 أرقام الظاهر في الشاشة."
    read_code || exit 1
    out=$("$ADB" pair "$pair_address" "$CODE" < /dev/null 2>&1 || true)
    if [ -n "$out" ]; then info "$out"; fi
    if grep -qi 'successfully paired' <<< "$out"; then break; fi
    if grep -qi 'unknown command' <<< "$out"; then
      die "This adb is too old to pair (it needs Platform-Tools 30 or newer)." \
        "أداة adb هذه أقدم من أن تقرن (تحتاج Platform-Tools 30 أو أحدث). استخدم أداة المثبّت المرفقة."
    fi
    warn "Pairing failed: wrong code, or the pairing window on the TV was closed. Try again." \
      "فشل الإقران: الرمز خاطئ أو أُغلقت نافذة الإقران في الشاشة. حاول مرة أخرى."
  done
  info "Paired. / تم الإقران."
  pair_ip=${pair_address%%:*}
  if SERIAL=$(discover_connect_address "$pair_ip"); then
    if try_connect; then return 0; fi
  fi
  tries=0
  while :; do
    tries=$((tries + 1))
    [ "$tries" -le 3 ] || die "Could not connect to the Wireless debugging address." \
      "تعذّر الاتصال بعنوان «Wireless debugging». شغّل المثبّت من جديد."
    say "Now type the address shown on the «Wireless debugging» screen, under «IP address & Port»" \
      "اكتب الآن العنوان الظاهر في شاشة «Wireless debugging» تحت «IP address & Port»"
    info "Example / مثال: 192.168.1.50:41234"
    read_address port "Address / العنوان: " || exit 1
    SERIAL=$ADDRESS
    rc=0
    try_connect || rc=$?
    case "$rc" in
      0) return 0 ;;
      1) warn "The TV refused $SERIAL. Type the address of the «Wireless debugging» screen, not the pairing one." \
        "رفضت الشاشة $SERIAL. اكتب عنوان شاشة «Wireless debugging» نفسها، لا عنوان الإقران." ;;
      3) unauthorized_help; exit 1 ;;
      *) unreachable_help; exit 1 ;;
    esac
  done
}

connect_tv() {
  local rc=0
  try_connect || rc=$?
  case "$rc" in
    0) return 0 ;;
    1)
      refused_help
      say "Does your TV only have «Wireless debugging»? Pair with a code now?" \
        "هل في شاشتك «Wireless debugging» فقط؟ هل تريد الإقران برمز الآن؟"
      if ask_yes_no "Pair now? [y/N] "; then
        pair_and_connect
        return 0
      fi
      info "Turn on «USB debugging», then run the installer again. / فعّل «USB debugging» ثم شغّل المثبّت من جديد."
      exit 1
      ;;
    3) unauthorized_help; exit 1 ;;
    *) unreachable_help; exit 1 ;;
  esac
}

# ─── install and grant ───────────────────────────────────────────────────────────────────────
install_apk() {
  say "Installing $(basename "$APK") ..." "تثبيت التطبيق على الشاشة (قد يستغرق دقيقة) ..."
  local out
  # No "-g": it would grant the guard's permissions for good, beyond the app-ops below and beyond --undo.
  # Old adb versions exit 0 even when the install fails: require the "Success" line too.
  if out=$("$ADB" -s "$SERIAL" install -r "$APK" < /dev/null 2>&1) && grep -q '^Success' <<< "$out"; then
    info "$(tail -n 1 <<< "$out")"
    return 0
  fi
  printf '%s\n' "$out" >&2
  case "$out" in
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE* | *"signatures do not match"*)
      warn "The Shasha already on the TV was signed with a different key (an old or test build)." \
        "النسخة المثبّتة على الشاشة موقّعة بمفتاح آخر (بناء قديم أو تجريبي)، فلا يمكن التحديث فوقها."
      info "Nothing was changed. To replace it, uninstall it first. This DELETES its data" \
        "(profiles, subscriptions, history): export a backup in Shasha's settings first." \
        "لم يتغيّر شيء. لاستبدالها يجب حذفها أولاً، وهذا يمسح بياناتها (الملفات والاشتراكات والسجل)." \
        "صدّر نسخة احتياطية من إعدادات التطبيق قبل الحذف إن احتجت إليها." \
        "To uninstall on the TV: Settings > Apps > شاشة > Uninstall. Or from this computer:" \
        "للحذف من الشاشة: الإعدادات ← التطبيقات ← شاشة ← Uninstall. أو من هذا الحاسوب:" \
        "" "    \"$ADB\" -s $SERIAL uninstall $PKG" "" \
        "Then run the installer again. / ثم شغّل المثبّت من جديد."
      ;;
    *INSTALL_FAILED_VERSION_DOWNGRADE*)
      warn "The TV already has a newer Shasha than this APK." \
        "النسخة على الشاشة أحدث من هذا الملف. حمّل أحدث ملف من صفحة GitHub Releases."
      ;;
    *NO_CERTIFICATES* | *"no signatures"*)
      warn "This APK is not signed (a CI build made without the signing key)." \
        "هذا الملف غير موقّع (بناء بلا مفتاح التوقيع). حمّل الملف من صفحة GitHub Releases."
      ;;
    *)
      warn "Installation failed (see the message above)." "فشل التثبيت (انظر الرسالة أعلاه)."
      ;;
  esac
  exit 1
}

grant_ops() {
  say "Granting permissions (display over apps, usage access, install updates) ..." \
    "منح الصلاحيات: العرض فوق التطبيقات، الوصول إلى الاستخدام، تثبيت التحديثات ..."
  local op
  for op in "${OPS[@]}"; do
    tv_shell appops set "$PKG" "$op" allow ||
      die "appops set $op failed (see above)." "فشل منح الصلاحية $op (انظر الرسالة أعلاه)."
    info "$op -> allow"
  done
}

YOUTUBE_STATUS="not installed / غير مثبّت"

maybe_disable_youtube() {
  if ! is_installed "$YT_PKG"; then
    info "Official YouTube app not found on the TV; skipping." "تطبيق يوتيوب الرسمي غير موجود؛ تخطٍّ."
    return 0
  fi
  local disabled
  disabled=$(tv_shell pm list packages -d "$YT_PKG" || true)
  if grep -qx "package:$YT_PKG" <<< "$disabled"; then
    YOUTUBE_STATUS="disabled / معطّل"
    info "The official YouTube app is already disabled." "تطبيق يوتيوب الرسمي معطّل أصلاً."
    return 0
  fi
  say "Disable the official YouTube app on the TV? (it can be re-enabled any time)" \
    "هل تريد تعطيل تطبيق يوتيوب الرسمي على الشاشة؟ (يمكن إعادته في أي وقت)"
  if ask_yes_no "Disable YouTube? [y/N] "; then
    tv_shell pm disable-user --user 0 "$YT_PKG" ||
      die "Could not disable YouTube (see above)." "تعذّر تعطيل يوتيوب (انظر الرسالة أعلاه)."
    YOUTUBE_STATUS="disabled / معطّل"
    info "Disabled. To bring it back: / للإعادة:" "    \"$ADB\" -s $SERIAL shell pm enable $YT_PKG"
  else
    YOUTUBE_STATUS="enabled / مفعّل"
    info "Left enabled. / تُرك مفعّلاً."
  fi
}

start_app() {
  say "Starting Shasha ..." "تشغيل التطبيق ..."
  local out
  out=$(tv_shell am start -n "$ACTIVITY" 2>&1 || true)
  if grep -q '^Error' <<< "$out"; then
    warn "Could not start $ACTIVITY: $out" "تعذّر تشغيل التطبيق؛ افتحه يدوياً من الشاشة الرئيسية."
  else
    sleep 3 # let the app start its guard before it is checked
  fi
}

guard_status() {
  local out
  out=$(tv_shell dumpsys activity services "$GUARD" 2>&1 || true)
  if grep -q 'ServiceRecord{' <<< "$out"; then
    echo "running / تعمل"
  else
    echo "not running now / لا تعمل الآن"
  fi
}

# Prints one line per permission in GUARD_PERMS; returns non-zero if any is still granted.
check_perms_revoked() {
  local all_ok=0 out perm
  out=$(tv_shell dumpsys package "$PKG" 2>&1 || true)
  for perm in "${GUARD_PERMS[@]}"; do
    if grep -Fq "$perm: granted=true" <<< "$out"; then
      info "[!!] $perm: granted=true"
      all_ok=1
    else
      info "[OK] $perm: not granted"
    fi
  done
  return "$all_ok"
}

# Prints one line per op; returns non-zero if any op is not in the expected mode.
check_ops() {
  local expected=$1 all_ok=0 op out
  for op in "${OPS[@]}"; do
    out=$(tv_shell appops get "$PKG" "$op" 2>&1 || true)
    if [ "$expected" = allow ] && grep -Eq "(^|[[:space:]])$op: allow" <<< "$out"; then
      info "[OK] $op: allow"
    elif [ "$expected" = default ] && ! grep -Eq "(^|[[:space:]])$op: (allow|ignore|deny)" <<< "$out"; then
      info "[OK] $op: default"
    else
      info "[!!] $op: $(tr '\n' ' ' <<< "$out")"
      all_ok=1
    fi
  done
  return "$all_ok"
}

setup() {
  if [ -z "$APK" ]; then
    APK=$(find_apk)
    [ -n "$APK" ] || die "No APK given and no shasha-*.apk next to the installer or in the current folder." \
      "لا يوجد ملف التطبيق shasha-*.apk بجانب المثبّت. فك ضغط Shasha-Setup.zip كاملاً وشغّل المثبّت من المجلد الناتج."
  fi
  [ -f "$APK" ] || die "APK not found: $APK" "ملف APK غير موجود: $APK"
  info "APK: $APK"

  connect_tv
  install_apk
  grant_ops
  maybe_disable_youtube
  start_app

  say "Verification" "التحقق"
  local ops_ok=true guard
  check_ops allow || ops_ok=false
  guard=$(guard_status)
  info "Guard service / خدمة الحارس: $guard"

  say "Summary" "الملخص"
  info "TV / الشاشة:              $SERIAL" \
    "APK / الملف:               $(basename "$APK")" \
    "Permissions / الصلاحيات:   $([ "$ops_ok" = true ] && echo "granted / ممنوحة" || echo "INCOMPLETE / ناقصة")" \
    "Guard / الحارس:            $guard" \
    "YouTube / يوتيوب الرسمي:  $YOUTUBE_STATUS" \
    "Undo / للتراجع:            bash \"$LAUNCHER\" --undo $SERIAL"
  info "" "The app runs its guard from its first start and after every reboot; it acts while a child" \
    "profile is on screen, a video is locked, time is up or it is outside the schedule. To double-check, open" \
    "Shasha > Settings > Protection status on the TV (it also shows how fast the app came back)." \
    "التطبيق يشغّل الحارس من أول تشغيل وبعد كل إعادة تشغيل للشاشة، ويتدخل طوال بقاء ملف طفل على الشاشة، وأثناء قفل فيديو، وبعد انتهاء الوقت، وخارج الجدول." \
    "للتأكد: افتح تطبيق شاشة > الإعدادات > حالة الحماية (وفيها سرعة العودة المقيسة)."
  [ "$ops_ok" = true ] || exit 1
  say "Done! Shasha is installed on the TV." "تمّ! «شاشة» مثبّت على الشاشة."
  info "Now continue on the TV: create the parent PIN and the profiles." \
    "تابع الآن على الشاشة: أنشئ رمز الوالدين والملفات."
}

undo() {
  connect_tv
  if is_installed "$YT_PKG"; then
    say "Re-enabling the official YouTube app ..." "إعادة تفعيل تطبيق يوتيوب الرسمي ..."
    tv_shell pm enable "$YT_PKG" ||
      die "Could not re-enable YouTube (see above)." "تعذّرت إعادة تفعيل يوتيوب (انظر الرسالة أعلاه)."
    YOUTUBE_STATUS="enabled / مفعّل"
  fi
  local ops_ok=true guard="-"
  if is_installed "$PKG"; then
    say "Resetting Shasha's permissions to default ..." "إرجاع صلاحيات شاشة إلى الوضع الافتراضي ..."
    local op perm
    # Revoked first: a guard that sees its app-ops change then finds nothing left and stops by itself.
    # "Not granted" is not an error here, and a real failure shows in the verification below.
    for perm in "${GUARD_PERMS[@]}"; do
      tv_shell pm revoke "$PKG" "$perm" > /dev/null 2>&1 || true
    done
    for op in "${OPS[@]}"; do
      tv_shell appops set "$PKG" "$op" default ||
        die "appops set $op default failed (see above)." "فشل إرجاع الصلاحية $op (انظر الرسالة أعلاه)."
    done
    say "Stopping Shasha so its guard stops now ..." "إيقاف تطبيق شاشة ليتوقف الحارس الآن ..."
    info "It starts again only once its permissions are granted again (run the installer without --undo)." \
      "لا يعود الحارس حتى تُمنح صلاحيتاه من جديد (بتشغيل المثبّت دون --undo)."
    tv_shell am force-stop "$PKG" || true
    say "Verification" "التحقق"
    check_ops default || ops_ok=false
    check_perms_revoked || ops_ok=false
    guard=$(guard_status)
    info "Guard service / خدمة الحارس: $guard"
  else
    info "Shasha is not installed; no permissions to reset." "تطبيق شاشة غير مثبّت؛ لا صلاحيات لإرجاعها."
  fi
  say "Summary" "الملخص"
  info "TV / الشاشة:              $SERIAL" \
    "Permissions / الصلاحيات:   $([ "$ops_ok" = true ] && echo "default / افتراضية" || echo "CHECK ABOVE / راجع أعلاه")" \
    "Guard / الحارس:            $guard" \
    "YouTube / يوتيوب الرسمي:  $YOUTUBE_STATUS" \
    "Shasha stays installed. To remove it: TV Settings > Apps > شاشة > Uninstall, or:" \
    "التطبيق باقٍ. لحذفه: الإعدادات ← التطبيقات ← شاشة ← Uninstall، أو:" \
    "    \"$ADB\" -s $SERIAL uninstall $PKG"
  [ "$ops_ok" = true ] || exit 1
}

# ─── main ────────────────────────────────────────────────────────────────────────────────────
UNDO=false
TV=""
APK=""
for arg in "$@"; do
  case "$arg" in
    --undo) UNDO=true ;;
    -h | --help) usage; exit 0 ;;
    -*) usage >&2; exit 2 ;;
    *.apk | *.APK)
      if [ -z "$APK" ]; then APK=$arg; else usage >&2; exit 2; fi
      ;;
    *)
      if [ -z "$TV" ]; then TV=$arg; else usage >&2; exit 2; fi
      ;;
  esac
done
if [ -n "$TV" ]; then
  valid_address "$TV" || die "\"$TV\" is not an IP address like 192.168.1.50 (or 192.168.1.50:5555)." \
    "\"$TV\" ليس عنوان IP مثل 192.168.1.50. تجده في إعدادات الشاشة > الشبكة والإنترنت."
else
  INTERACTIVE=true
fi

if [ "$UNDO" = true ]; then
  printf '\n  %s\n' "Shasha: undo / شاشة: التراجع"
else
  printf '\n  %s\n' "Shasha installer / مثبّت «شاشة»"
fi

say "Checking adb ..." "التحقق من وجود adb ..."
if ! find_adb; then
  if [ -n "$BROKEN_ADB" ]; then adb_broken_help; else adb_missing_help; fi
  # Wait for a key even when started with an address, so the explanation stays on screen.
  INTERACTIVE=true
  exit 1
fi
info "$ADB" "$(sed -n 1p <<< "$ADB_VERSION")"

if [ -z "$TV" ]; then
  if ! ask_tv_address; then
    warn "No valid address was typed." "لم يُكتب عنوان صحيح. شغّل المثبّت من جديد."
    exit 2
  fi
  TV=$ADDRESS
fi
case "$TV" in
  *:*) SERIAL=$TV ;;
  *) SERIAL="$TV:5555" ;;
esac

if [ "$UNDO" = true ]; then
  if [ -n "$APK" ]; then warn "--undo ignores the APK argument." "خيار --undo يتجاهل ملف APK."; fi
  undo
else
  setup
fi
