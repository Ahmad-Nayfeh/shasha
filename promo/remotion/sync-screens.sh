#!/usr/bin/env bash
# Copy the real app screenshots (JVM screenshot tests / remote page captures) into public/screens/.
# The mapping (storyboard name <- repo file) is the "source" field of each entry in src/screens.json;
# after new screenshots land in the repo, this one command re-syncs everything:   ./sync-screens.sh [repo]
set -euo pipefail
cd "$(dirname "$0")"
node tools/sync-screens.mjs "${1:-/home/user/shasha}"
