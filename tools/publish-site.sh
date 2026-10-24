#!/usr/bin/env bash
# Publishes a release of Shasha on the download site (https://ahmad-nayfeh.github.io/shasha/), which is also where both
# in-app updaters look (latest.json). Run it after CI has published the GitHub Release (docs/dev/MAINTENANCE.md,
# «النشر على موقع التنزيل»):
#
#   tools/publish-site.sh --tv shasha-1.8.0-1100.apk --mobile shasha-mobile-1.8.0-1100.apk --kit Shasha-Setup.zip \
#                         --version-name 1.8.0 --version-code 1100 --site ../ahmad-nayfeh.github.io
#
# Options: --mobile-version-name <name> (default: --version-name), --notes <text> (default: «شاشة <versionName>»),
#          --date <YYYY-MM-DD> (default: today, UTC).
#
# In <site>/shasha/ it:
# - copies the files under fixed names: shasha-tv.apk, shasha-mobile.apk, shasha-setup.zip;
# - writes latest.json (format in MAINTENANCE.md): one entry per app ("tv", "mobile") with versionCode, versionName,
#   url (with ?v=<versionCode>, so the site's cache never hands out an older file), sha256, size and notes, plus "kit";
# - rewrites the values between the markers <!--@name-->…<!--/@name--> in index.html (versions, sizes, date; the page
#   shows no hashes or build numbers, those live in latest.json only) and the
#   ?v=<code> of every link to the three files. Each marker must be there, or nothing is written.
# - when aapt2 is found ($ANDROID_HOME/build-tools/*/aapt2 or PATH), checks each APK's package and versionCode first.
# It never commits or pushes: review `git diff` in the site repository, then commit and push there.
# Needs bash, python3 and sha256 support (python's hashlib).
set -euo pipefail

usage() { sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2; }

tv="" mobile="" kit="" version_name="" version_code="" mobile_version_name="" site="" notes="" date=""
while [ $# -gt 0 ]; do
  case "$1" in
    --tv) tv=${2:?}; shift 2 ;;
    --mobile) mobile=${2:?}; shift 2 ;;
    --kit) kit=${2:?}; shift 2 ;;
    --version-name) version_name=${2:?}; shift 2 ;;
    --version-code) version_code=${2:?}; shift 2 ;;
    --mobile-version-name) mobile_version_name=${2:?}; shift 2 ;;
    --site) site=${2:?}; shift 2 ;;
    --notes) notes=${2:?}; shift 2 ;;
    --date) date=${2:?}; shift 2 ;;
    -h|--help) usage ;;
    *) echo "publish-site: unknown argument '$1' (see --help)" >&2; exit 2 ;;
  esac
done
for pair in "tv:$tv" "mobile:$mobile" "kit:$kit" "version-name:$version_name" "version-code:$version_code" "site:$site"; do
  [ -n "${pair#*:}" ] || { echo "publish-site: --${pair%%:*} is required (see --help)" >&2; exit 2; }
done
command -v python3 > /dev/null 2>&1 || { echo "publish-site: python3 is needed" >&2; exit 1; }

# The APK's own package and versionCode, when aapt2 is around (CI names the files, but a wrong file here would be
# offered to every TV or phone).
aapt2=$(command -v aapt2 || true)
if [ -z "$aapt2" ] && [ -n "${ANDROID_HOME:-}" ]; then
  aapt2=$(ls -1 "$ANDROID_HOME"/build-tools/*/aapt2 2> /dev/null | sort -V | tail -n1 || true)
fi
check_apk() { # file package code
  [ -n "$aapt2" ] || return 0
  local badging line
  badging=$("$aapt2" dump badging "$1" 2> /dev/null) || { echo "publish-site: aapt2 cannot read $1" >&2; exit 1; }
  line=$(grep -m1 '^package:' <<< "$badging") || { echo "publish-site: aapt2 found no package in $1" >&2; exit 1; }
  local package code
  package=$(sed -nE "s/^package: name='([^']+)'.*/\1/p" <<< "$line")
  code=$(sed -nE "s/.* versionCode='([0-9]+)'.*/\1/p" <<< "$line")
  if [ "$package" != "$2" ] || [ "$code" != "$3" ]; then
    echo "publish-site: $1 is $package versionCode $code, expected $2 versionCode $3" >&2
    exit 1
  fi
  echo "checked $1: $package $code"
}
check_apk "$tv" com.ahmadnayfeh.shasha "$version_code"
check_apk "$mobile" com.ahmadnayfeh.shasha.mobile "$version_code"
[ -n "$aapt2" ] || echo "publish-site: aapt2 not found, the APKs' package and versionCode are not checked" >&2

python3 - "$tv" "$mobile" "$kit" "$version_name" "$version_code" "${mobile_version_name:-$version_name}" "$site" "$notes" "$date" <<'PY'
import datetime
import hashlib
import json
import os
import re
import shutil
import sys

tv, mobile, kit, name, code, mobile_name, site, notes, date = sys.argv[1:10]
BASE = "https://ahmad-nayfeh.github.io/shasha/"


def fail(message):
    print(f"publish-site: {message}", file=sys.stderr)
    sys.exit(1)


if not re.fullmatch(r"[1-9][0-9]{0,9}", code) or int(code) > 2**31 - 1:
    fail(f"--version-code must be a positive integer, got '{code}'")
for label, value in (("--version-name", name), ("--mobile-version-name", mobile_name)):
    if not re.fullmatch(r"[0-9A-Za-z._-]{1,64}", value):
        fail(f"{label} may only hold letters, digits and . _ - (got '{value}')")
for path in (tv, mobile, kit):
    if not os.path.isfile(path):
        fail(f"no such file: {path}")
if date and not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date):
    fail(f"--date must be YYYY-MM-DD, got '{date}'")
date = date or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")
folder = os.path.join(site, "shasha")
page_path = os.path.join(folder, "index.html")
if not os.path.isfile(page_path):
    fail(f"{page_path} is missing: is --site the site repository?")


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def megabytes(size):
    return f"{size / (1024 * 1024):.1f}"


files = {"tv": (tv, "shasha-tv.apk"), "mobile": (mobile, "shasha-mobile.apk"), "kit": (kit, "shasha-setup.zip")}
facts = {}
for key, (source, target) in files.items():
    facts[key] = {"size": os.path.getsize(source), "sha256": sha256(source), "file": target}

values = {
    "tv-version": name, "tv-size": megabytes(facts["tv"]["size"]),
    "mobile-version": mobile_name, "mobile-size": megabytes(facts["mobile"]["size"]),
    "kit-size": megabytes(facts["kit"]["size"]),
    "published": date,
}

# The page first: if a marker is missing, nothing has been copied or written yet.
with open(page_path, encoding="utf-8") as f:
    page = f.read()
for marker, value in values.items():
    pattern = re.compile(r"(<!--@" + re.escape(marker) + r"-->)(.*?)(<!--/@" + re.escape(marker) + r"-->)", re.S)
    if not pattern.search(page):
        fail(f"marker <!--@{marker}--> … <!--/@{marker}--> not found in {page_path}")
    page = pattern.sub(lambda m: m.group(1) + value + m.group(3), page)
links = re.compile(r"(shasha-tv\.apk|shasha-mobile\.apk|shasha-setup\.zip)\?v=\d+")
if not links.search(page):
    fail(f"no link to shasha-tv.apk?v=…, shasha-mobile.apk?v=… or shasha-setup.zip?v=… in {page_path}")
page = links.sub(lambda m: f"{m.group(1)}?v={code}", page)

for key, (source, target) in files.items():
    shutil.copyfile(source, os.path.join(folder, target))


def entry(key, version_name):
    return {
        "versionCode": int(code),
        "versionName": version_name,
        "url": f"{BASE}{facts[key]['file']}?v={code}",
        "sha256": facts[key]["sha256"],
        "size": facts[key]["size"],
        "notes": notes or f"شاشة {version_name}",
    }


manifest = {
    "tv": entry("tv", name),
    "mobile": entry("mobile", mobile_name),
    "kit": {"url": f"{BASE}shasha-setup.zip?v={code}", "sha256": facts["kit"]["sha256"], "size": facts["kit"]["size"]},
    "published": date,
}
with open(os.path.join(folder, "latest.json"), "w", encoding="utf-8") as f:
    json.dump(manifest, f, ensure_ascii=False, indent=2)
    f.write("\n")
with open(page_path, "w", encoding="utf-8") as f:
    f.write(page)

for key in files:
    print(f"{files[key][1]}: {facts[key]['size']} bytes, sha256 {facts[key]['sha256']}")
print(f"wrote {os.path.join(folder, 'latest.json')} and {page_path} (version {name}, code {code}, {date})")
PY
echo "Now review and publish:  git -C \"$site\" diff --stat && git -C \"$site\" add shasha && git -C \"$site\" commit && git -C \"$site\" push"
