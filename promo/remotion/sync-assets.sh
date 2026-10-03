#!/usr/bin/env bash
# Copy the narration outputs produced by ../tts/scripts/eleven_narration.py (or synth_narration.py) into the
# Remotion project: the timelines and the narration tracks (the TV video and, when present, the phone video and the
# English version of each).
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
# English version (eleven_narration.py assemble --script tv-en,mobile-en)
for v in "" -mobile; do
  if [ -f "../timeline$v-en.json" ]; then
    cp "../timeline$v-en.json" "src/data/timeline$v-en.json"
    mkdir -p "public/audio$v-en"
    cp "../audio$v-en/narration_full.wav" "public/audio$v-en/narration_full.wav"
  fi
done
# Real app screenshots (from the JVM screenshot tests) go to public/screens/<name>.png: ./sync-screens.sh
echo "synced timelines + narration"
