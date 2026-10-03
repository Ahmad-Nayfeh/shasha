#!/usr/bin/env bash
# Copy the narration outputs produced by ../tts/scripts/eleven_narration.py (or synth_narration.py) into the
# Remotion project: the timelines and the two narration tracks (the TV video and, when present, the phone video).
set -euo pipefail
cd "$(dirname "$0")"
cp ../timeline.json src/data/timeline.json
mkdir -p public/audio
cp ../audio/narration_full.wav public/audio/narration_full.wav
if [ -f ../timeline-mobile.json ]; then
  cp ../timeline-mobile.json src/data/timeline-mobile.json
  mkdir -p public/audio-mobile
  cp ../audio-mobile/narration_full.wav public/audio-mobile/narration_full.wav
fi
# Real app screenshots (from the JVM screenshot tests) go to public/screens/<name>.png: ./sync-screens.sh
echo "synced timelines + narration"
