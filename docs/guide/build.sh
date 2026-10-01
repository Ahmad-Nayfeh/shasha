#!/usr/bin/env bash
# Builds both guides and copies them into the kit (then: bash tools/make-setup-zip.sh):
#   «دليل شاشة»   guide.html    -> docs/دليل-شاشة.pdf   and «Shasha-Setup/دليل شاشة.pdf»
#   Shasha Guide  en/guide.html -> docs/Shasha-Guide.pdf and «Shasha-Setup/Shasha Guide (English).pdf»
#
#   bash docs/guide/build.sh                  # both PDFs (uses the pictures already in img/)
#   bash docs/guide/build.sh --lang en        # one of them (ar or en)
#   bash docs/guide/build.sh --images         # first remake img/ from the screenshots (needs Pillow)
#   bash docs/guide/build.sh --png <dir>      # also one PNG per page in <dir>/ar and <dir>/en, to check the layout
#   bash docs/guide/build.sh --pages <dir>    # the pages for the TV's in-app guide: <dir>/{ar,en}/page-NN.webp,
#                                             # 1280 px wide, WebP quality 80 (needs Pillow); not kept in the repository
#
# Needs: Node 18+, the npm package playwright-core somewhere outside the repository (PLAYWRIGHT_DIR is the
# folder holding its node_modules), and a Chromium Playwright can launch (PLAYWRIGHT_BROWSERS_PATH, or
# CHROMIUM_PATH). The install pictures come from docs/images/install; the QR codes in qr/ from make_qr.py.
set -euo pipefail
cd "$(dirname "$0")"

if [ "${1:-}" = "--images" ]; then
  shift
  python3 prepare_images.py
elif [ ! -d img ]; then
  python3 prepare_images.py
fi

if [ "${1:-}" = "--pages" ]; then
  out=$(mkdir -p "${2:?--pages needs a folder}" && cd "$2" && pwd)
  shift 2
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  node build.mjs --png "$tmp" --width 1280 "$@"
  python3 - "$tmp" "$out" <<'PY'
import glob, os, sys
from PIL import Image
src, out = sys.argv[1:3]
for lang in sorted(os.listdir(src)):
    os.makedirs(os.path.join(out, lang), exist_ok=True)
    for old in glob.glob(os.path.join(out, lang, "page-*.webp")):
        os.remove(old)
    total = 0
    for png in sorted(glob.glob(os.path.join(src, lang, "page-*.png"))):
        im = Image.open(png).convert("RGB")
        if im.width != 1280:
            im = im.resize((1280, round(im.height * 1280 / im.width)), Image.LANCZOS)
        dst = os.path.join(out, lang, os.path.basename(png)[:-4] + ".webp")
        im.save(dst, "WEBP", quality=80, method=6)
        total += os.path.getsize(dst)
    print(f"{out}/{lang}: {len(glob.glob(os.path.join(out, lang, '*.webp')))} pages, {total / 1048576:.2f} MB")
PY
  exit 0
fi

node build.mjs "$@"
