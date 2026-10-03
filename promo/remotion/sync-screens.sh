#!/usr/bin/env bash
# Copy the real app screenshots (JVM screenshot tests / remote page captures) into public/screens/, and the English
# version's (promo/screens-en/, recorded with -PshashaPromoEnglish) into public/screens-en/.
# The mapping (storyboard name <- repo file) is the "source" field of each entry in src/screens.json (Arabic) and
# src/screens-en.json (English); after new screenshots land in the repo, this one command re-syncs everything:
#   ./sync-screens.sh [repo]
set -euo pipefail
cd "$(dirname "$0")"
node tools/sync-screens.mjs "${1:-/home/user/shasha}"
