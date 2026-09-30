#!/usr/bin/env bash
# Builds Shasha-Setup.zip at the root of the repository from the Shasha-Setup/ folder: the one file people
# download («دليل شاشة», step 1). Rebuild the PDF guide first when the guide changed:
#   bash docs/guide/build.sh             (see its header; it also copies the PDF into Shasha-Setup/)
#   bash tools/make-setup-zip.sh
#
# - The files sit at the top of the ZIP, with no folder around them: Windows' «Extract All» and a double-click
#   on a Mac then both give one «Shasha-Setup» folder (a folder inside would give Shasha-Setup\Shasha-Setup).
# - Unix modes are stored, so the Mac and Linux installers and both adb stay executable after unzipping;
#   names are stored as UTF-8 (flag bit 11), which the unzip of Windows and of macOS read.
# - The same content gives the same ZIP: entries are sorted and dated SOURCE_DATE_EPOCH (default 2026-01-01).
# - README.md (it is for GitHub) and hidden files such as .DS_Store stay out.
# - It stops when a file of the kit is missing, when the .bat is not CRLF or a shell script is not LF.
# Needs bash and python3.
set -euo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
command -v python3 > /dev/null 2>&1 || { echo "make-setup-zip: python3 is needed" >&2; exit 1; }

python3 - "$repo/Shasha-Setup" "$repo/Shasha-Setup.zip" "$repo/docs/guide/guide.html" <<'PY'
import glob
import os
import sys
import time
import zipfile

kit, out, guide = sys.argv[1:4]
BAT, MAC, LINUX, PDF = "Windows - ثبّت شاشة.bat", "Mac - ثبّت شاشة.command", "install-linux.sh", "دليل شاشة.pdf"
required = [BAT, MAC, LINUX, PDF, "tools/VERSION.txt",
            "tools/windows/adb.exe", "tools/windows/AdbWinApi.dll", "tools/windows/AdbWinUsbApi.dll", "tools/windows/NOTICE.txt",
            "tools/mac/adb", "tools/mac/NOTICE.txt", "tools/linux/adb", "tools/linux/NOTICE.txt"]
executable = {MAC, LINUX, "tools/mac/adb", "tools/linux/adb"}

def data(rel):
    with open(os.path.join(kit, rel), "rb") as f:
        return f.read()

problems = [f"missing: Shasha-Setup/{rel}" for rel in required if not os.path.isfile(os.path.join(kit, rel))]
apks = glob.glob(os.path.join(kit, "shasha-*.apk"))
if len(apks) != 1:
    problems.append(f"Shasha-Setup/ must hold exactly one shasha-*.apk (found {len(apks)})")
if not problems:
    bat = data(BAT)
    if bat.count(b"\n") != bat.count(b"\r\n"):
        problems.append(f"{BAT} must use CRLF line endings")
    if bat.startswith(b"\xef\xbb\xbf"):
        problems.append(f"{BAT} must be UTF-8 without a BOM")
    for rel in (MAC, LINUX):
        if b"\r" in data(rel):
            problems.append(f"{rel} must use LF line endings")
if problems:
    sys.exit("make-setup-zip: " + "\n  ".join(["the kit is not ready:"] + problems))
if os.path.getmtime(os.path.join(kit, PDF)) < os.path.getmtime(guide):
    print("make-setup-zip: note: the PDF guide is older than docs/guide/guide.html; rebuild it if the guide changed "
          "(bash docs/guide/build.sh)", file=sys.stderr)

entries = []
for root, dirs, files in os.walk(kit):
    dirs[:] = sorted(d for d in dirs if not d.startswith("."))
    rel_root = os.path.relpath(root, kit).replace(os.sep, "/")
    if rel_root != ".":
        entries.append((rel_root + "/", None))
    for name in files:
        if name.startswith(".") or (rel_root == "." and name == "README.md"):
            continue
        rel = name if rel_root == "." else f"{rel_root}/{name}"
        entries.append((rel, os.path.join(root, name)))
entries.sort()

stamp = time.gmtime(int(os.environ.get("SOURCE_DATE_EPOCH", "1767225600")))[:6]
tmp = out + ".tmp"
with zipfile.ZipFile(tmp, "w") as zf:
    for rel, path in entries:
        info = zipfile.ZipInfo(rel, date_time=stamp)
        info.create_system = 3  # Unix: the modes below are honoured by unzip and by macOS
        if path is None:
            info.external_attr = (0o040755 << 16) | 0x10
            zf.writestr(info, b"")
            continue
        mode = 0o755 if rel in executable or os.stat(path).st_mode & 0o111 else 0o644
        info.external_attr = (0o100000 | mode) << 16
        info.compress_type = zipfile.ZIP_DEFLATED
        with open(path, "rb") as f:
            zf.writestr(info, f.read(), compresslevel=9)
os.replace(tmp, out)

with zipfile.ZipFile(out) as zf:
    bad = zf.testzip()
    if bad:
        sys.exit(f"make-setup-zip: CRC error in {bad}")
    infos = zf.infolist()
    for i in infos:
        if i.is_dir():
            continue
        print(f"  {i.external_attr >> 16 & 0o777:o}  {i.file_size:>10,}  {i.filename}")
    size = os.path.getsize(out)
    print(f"{os.path.relpath(out)}: {len([i for i in infos if not i.is_dir()])} files, {size / 1048576:.1f} MB")
PY
