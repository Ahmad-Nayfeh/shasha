#!/usr/bin/env bash
# Builds docs/Shasha-Catalog.pdf («كتيّب شاشة») from docs/catalog/catalog.html.
#
#   bash docs/catalog/build.sh                # PDF only (uses the pictures already in img/)
#   bash docs/catalog/build.sh --images       # first remake img/ from the screenshots (needs Pillow)
#   bash docs/catalog/build.sh --png <dir>    # also one PNG per page, to check the layout
#
# Needs: Node 18+, the npm package playwright-core somewhere outside the repository (PLAYWRIGHT_DIR is the
# folder holding its node_modules), and a Chromium Playwright can launch (PLAYWRIGHT_BROWSERS_PATH, or
# CHROMIUM_PATH). The annotated pictures come from docs/manual/images (docs/manual/tools/annotate.py).
set -euo pipefail
cd "$(dirname "$0")"

if [ "${1:-}" = "--images" ]; then
  shift
  python3 prepare_images.py
elif [ ! -d img ]; then
  python3 prepare_images.py
fi

node build.mjs "$@"
