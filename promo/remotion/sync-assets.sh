#!/usr/bin/env bash
# Copy the narration outputs produced by ../tts/scripts/synth_narration.py into the Remotion project.
set -euo pipefail
cd "$(dirname "$0")"
cp ../timeline.json src/data/timeline.json
mkdir -p public/audio/scenes
rm -f public/audio/scenes/*.wav
cp ../audio/narration_full.wav public/audio/narration_full.wav
cp ../audio/scenes/*.wav public/audio/scenes/
# Real app screenshots (from the JVM screenshot tests) go to public/screens/<name>.png — see ../storyboard.md
echo "synced timeline + audio"
