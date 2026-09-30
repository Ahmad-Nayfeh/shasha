#!/bin/sh
# «شاشة» (Shasha) installer for Linux: runs the same script as the Mac installer, with the adb in
# tools/linux. From this folder:  bash install-linux.sh   (it asks for the TV's address)
#   bash install-linux.sh <TV_IP[:port]> [shasha.apk]      bash install-linux.sh --undo [TV_IP]
# See README.md next to this file.
here=$(cd "$(dirname "$0")" && pwd) || exit 1
SHASHA_LAUNCHER="$here/install-linux.sh" exec bash "$here/Mac - ثبّت شاشة.command" "$@"
