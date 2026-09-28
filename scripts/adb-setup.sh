#!/usr/bin/env bash
# Installs Shasha on the TV over the network and grants the permissions that only ADB can grant
# (SPEC section 4, "the guard outside the app"). See scripts/README.md.
#
#   ./adb-setup.sh <TV_IP[:port]> [path/to/shasha.apk]
#   ./adb-setup.sh --undo <TV_IP[:port]>
set -euo pipefail

readonly PKG=com.ahmadnayfeh.shasha
readonly ACTIVITY="$PKG/.MainActivity"
readonly YT_PKG=com.google.android.youtube.tv
# SYSTEM_ALERT_WINDOW: the guard draws over other apps. GET_USAGE_STATS: it sees which app is in
# front. REQUEST_INSTALL_PACKAGES: the in-app updater may open the system installer.
OPS=(SYSTEM_ALERT_WINDOW GET_USAGE_STATS REQUEST_INSTALL_PACKAGES)
readonly CONNECT_TIMEOUT=60

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ADB=""
SERIAL=""

say()  { printf '\n==> %s\n    %s\n' "$1" "$2"; }
info() { printf '    %s\n' "$@"; }
warn() { printf '\n!!  %s\n    %s\n' "$1" "$2" >&2; }
die()  { printf '\nERROR: %s\n       %s\n' "$1" "$2" >&2; exit 1; }

usage() {
  cat <<'EOF'
Usage:
  adb-setup.sh <TV_IP> [path/to/shasha.apk]   install Shasha and grant its permissions
  adb-setup.sh --undo <TV_IP>                 re-enable YouTube and reset the permissions
TV_IP may carry a port (default 5555): 192.168.1.50 or 192.168.1.50:5555.
Without an APK path, the newest shasha-*.apk next to this script or in the current folder is used.

الاستخدام:
  adb-setup.sh <عنوان IP للشاشة> [مسار ملف APK]   تثبيت شاشة ومنح الصلاحيات
  adb-setup.sh --undo <عنوان IP للشاشة>           التراجع: إعادة يوتيوب وإلغاء الصلاحيات
EOF
}

find_adb() {
  if command -v adb > /dev/null 2>&1; then
    command -v adb
    return 0
  fi
  local candidate
  for candidate in "$SCRIPT_DIR/platform-tools/adb" "$PWD/platform-tools/adb" \
    "${ANDROID_HOME:-/nonexistent}/platform-tools/adb" \
    "$HOME/Library/Android/sdk/platform-tools/adb" "$HOME/Android/Sdk/platform-tools/adb"; do
    if [ -x "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
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

# adb reads stdin unless told not to; </dev/null keeps it away from the y/N answer.
tv_shell() { "$ADB" -s "$SERIAL" shell "$@" < /dev/null | tr -d '\r'; }

device_state() {
  "$ADB" devices < /dev/null 2> /dev/null | tr -d '\r' | awk -v s="$SERIAL" '$1 == s { print $2 }'
}

is_installed() {
  local out
  out=$(tv_shell pm list packages "$1" || true)
  grep -qx "package:$1" <<< "$out"
}

connect_tv() {
  say "Connecting to $SERIAL ..." "الاتصال بالشاشة $SERIAL ..."
  "$ADB" start-server < /dev/null > /dev/null 2>&1 || true
  "$ADB" connect "$SERIAL" < /dev/null || true

  local start=$SECONDS last_try=$SECONDS hinted=false state
  while :; do
    state=$(device_state)
    case "$state" in
      device)
        info "Connected." "تم الاتصال."
        return 0
        ;;
      unauthorized)
        if [ "$hinted" = false ]; then
          say "Look at the TV: it asks \"Allow USB debugging?\"" \
            "انظر إلى الشاشة: تظهر نافذة \"Allow USB debugging?\" (السماح بتصحيح الأخطاء)."
          info "With the remote, tick \"Always allow from this computer\" and press OK." \
            "بالريموت: فعّل \"Always allow from this computer\" (السماح دائماً من هذا الحاسوب) ثم OK." \
            "Waiting up to ${CONNECT_TIMEOUT} s ..." "بانتظار الموافقة حتى ${CONNECT_TIMEOUT} ثانية ..."
          hinted=true
        fi
        ;;
      *)
        # Not listed yet, or "offline": ask again every 6 s.
        if [ $((SECONDS - last_try)) -ge 6 ]; then
          if [ "$state" = offline ]; then "$ADB" disconnect "$SERIAL" < /dev/null > /dev/null 2>&1 || true; fi
          "$ADB" connect "$SERIAL" < /dev/null > /dev/null 2>&1 || true
          last_try=$SECONDS
        fi
        ;;
    esac
    if [ $((SECONDS - start)) -ge "$CONNECT_TIMEOUT" ]; then
      warn "Could not connect to $SERIAL (state: ${state:-not found})." \
        "تعذّر الاتصال بالشاشة $SERIAL."
      info "- Is the TV on, on the same network, and is the IP right? (Settings > Network & Internet)" \
        "  هل الشاشة تعمل وعلى الشبكة نفسها، والعنوان صحيح؟ (الإعدادات > الشبكة والإنترنت)" \
        "- Developer options > USB debugging (and Network debugging, if present) must be ON." \
        "  يجب تفعيل خيارات المطوّر > تصحيح أخطاء USB (وتصحيح الأخطاء عبر الشبكة إن وُجد)." \
        "- Did you press OK on \"Allow USB debugging?\" Then run the script again." \
        "  هل ضغطت OK على نافذة السماح؟ ثم أعد تشغيل السكربت." \
        "- Still stuck: \"$ADB\" kill-server, then try again." \
        "  إن استمرت المشكلة: نفّذ الأمر السابق ثم أعد المحاولة."
      exit 1
    fi
    sleep 2
  done
}

install_apk() {
  say "Installing $(basename "$APK") ..." "تثبيت التطبيق على الشاشة (قد يستغرق دقيقة) ..."
  local out
  # Old adb versions exit 0 even when the install fails: require the "Success" line too.
  if out=$("$ADB" -s "$SERIAL" install -r -g "$APK" < /dev/null 2>&1) && grep -q '^Success' <<< "$out"; then
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
        "صدّر نسخة احتياطية من إعدادات التطبيق قبل الحذف إن احتجت إليها. أمر الحذف:" \
        "" "    \"$ADB\" -s $SERIAL uninstall $PKG" "" \
        "Then run this script again. / ثم أعد تشغيل هذا السكربت."
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
  local answer=""
  read -r -p "    Disable YouTube? [y/N] " answer || true
  case "$answer" in
    [yY] | [yY][eE][sS])
      tv_shell pm disable-user --user 0 "$YT_PKG" ||
        die "Could not disable YouTube (see above)." "تعذّر تعطيل يوتيوب (انظر الرسالة أعلاه)."
      YOUTUBE_STATUS="disabled / معطّل"
      info "Disabled. To bring it back: / للإعادة:" "    \"$ADB\" -s $SERIAL shell pm enable $YT_PKG"
      ;;
    *)
      YOUTUBE_STATUS="enabled / مفعّل"
      info "Left enabled. / تُرك مفعّلاً."
      ;;
  esac
}

start_app() {
  say "Starting Shasha ..." "تشغيل التطبيق ..."
  local out
  out=$(tv_shell am start -n "$ACTIVITY" 2>&1 || true)
  if grep -q '^Error' <<< "$out"; then
    warn "Could not start $ACTIVITY: $out" "تعذّر تشغيل التطبيق؛ افتحه يدوياً من الشاشة الرئيسية."
  fi
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
    [ -n "$APK" ] || die "No APK given and no shasha-*.apk next to this script or in the current folder." \
      "لم يُحدَّد ملف APK ولا يوجد ملف shasha-*.apk بجانب السكربت أو في المجلد الحالي. حمّله من GitHub Releases."
  fi
  [ -f "$APK" ] || die "APK not found: $APK" "ملف APK غير موجود: $APK"
  info "APK: $APK"

  connect_tv
  install_apk
  grant_ops
  maybe_disable_youtube
  start_app

  say "Verification" "التحقق"
  local ops_ok=true
  check_ops allow || ops_ok=false

  say "Summary" "الملخص"
  info "TV / الشاشة:              $SERIAL" \
    "APK / الملف:               $(basename "$APK")" \
    "Permissions / الصلاحيات:   $([ "$ops_ok" = true ] && echo "granted / ممنوحة" || echo "INCOMPLETE / ناقصة")" \
    "YouTube / يوتيوب الرسمي:  $YOUTUBE_STATUS" \
    "Undo / للتراجع:            $0 --undo $SERIAL"
  info "" "To double-check, open Shasha > Settings > Protection status on the TV." \
    "للتأكد: افتح تطبيق شاشة > الإعدادات > حالة الحماية."
  [ "$ops_ok" = true ] || exit 1
}

undo() {
  connect_tv
  if is_installed "$YT_PKG"; then
    say "Re-enabling the official YouTube app ..." "إعادة تفعيل تطبيق يوتيوب الرسمي ..."
    tv_shell pm enable "$YT_PKG" ||
      die "Could not re-enable YouTube (see above)." "تعذّرت إعادة تفعيل يوتيوب (انظر الرسالة أعلاه)."
    YOUTUBE_STATUS="enabled / مفعّل"
  fi
  local ops_ok=true
  if is_installed "$PKG"; then
    say "Resetting Shasha's permissions to default ..." "إرجاع صلاحيات شاشة إلى الوضع الافتراضي ..."
    local op
    for op in "${OPS[@]}"; do
      tv_shell appops set "$PKG" "$op" default ||
        die "appops set $op default failed (see above)." "فشل إرجاع الصلاحية $op (انظر الرسالة أعلاه)."
    done
    say "Verification" "التحقق"
    check_ops default || ops_ok=false
  else
    info "Shasha is not installed; no permissions to reset." "تطبيق شاشة غير مثبّت؛ لا صلاحيات لإرجاعها."
  fi
  say "Summary" "الملخص"
  info "TV / الشاشة:              $SERIAL" \
    "Permissions / الصلاحيات:   $([ "$ops_ok" = true ] && echo "default / افتراضية" || echo "CHECK ABOVE / راجع أعلاه")" \
    "YouTube / يوتيوب الرسمي:  $YOUTUBE_STATUS" \
    "Shasha stays installed. To remove it: / التطبيق باقٍ. لحذفه:" \
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
[ -n "$TV" ] || { usage >&2; exit 2; }
case "$TV" in
  *:*) SERIAL=$TV ;;
  *) SERIAL="$TV:5555" ;;
esac

say "Checking adb ..." "التحقق من وجود adb ..."
if ! ADB=$(find_adb); then
  warn "adb was not found." "لم يُعثر على adb."
  info "Download Android SDK Platform-Tools and unzip it next to this script (a \"platform-tools\" folder):" \
    "حمّل Platform-Tools وفك ضغطه بجانب هذا السكربت (مجلد باسم platform-tools):" \
    "  Windows: https://dl.google.com/android/repository/platform-tools-latest-windows.zip" \
    "  macOS:   https://dl.google.com/android/repository/platform-tools-latest-darwin.zip" \
    "           (or / أو: brew install --cask android-platform-tools)" \
    "  Linux:   https://dl.google.com/android/repository/platform-tools-latest-linux.zip" \
    "           (or / أو: sudo apt install adb)" \
    "  https://developer.android.com/tools/releases/platform-tools"
  exit 1
fi
info "$ADB ($("$ADB" version < /dev/null 2>&1 | sed -n 1p))"

if [ "$UNDO" = true ]; then
  if [ -n "$APK" ]; then warn "--undo ignores the APK argument." "خيار --undo يتجاهل ملف APK."; fi
  undo
else
  setup
fi
