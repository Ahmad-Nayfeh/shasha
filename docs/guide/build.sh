#!/usr/bin/env bash
# Builds docs/دليل-شاشة.pdf («دليل شاشة») from docs/guide/guide.html, and copies it into the kit as
# «Shasha-Setup/دليل شاشة.pdf». Then: bash tools/make-setup-zip.sh
#
#   bash docs/guide/build.sh                # PDF only (uses the pictures already in img/)
#   bash docs/guide/build.sh --images       # first remake img/ from the screenshots (needs Pillow)
#   bash docs/guide/build.sh --png <dir>    # also one PNG per page, to check the layout
#
# Needs: Node 18+, the npm package playwright-core somewhere outside the repository (PLAYWRIGHT_DIR is the
# folder holding its node_modules), and a Chromium Playwright can launch (PLAYWRIGHT_BROWSERS_PATH, or
# CHROMIUM_PATH). The install pictures come from docs/images/install.
set -euo pipefail
cd "$(dirname "$0")"

if [ "${1:-}" = "--images" ]; then
  shift
  python3 prepare_images.py
elif [ ! -d img ]; then
  python3 prepare_images.py
fi

node build.mjs "$@"
