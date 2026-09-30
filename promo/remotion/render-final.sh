#!/usr/bin/env bash
# Full-length render of the explainer (1920x1080, 30 fps, H.264 + AAC 48 kHz).
#
#   ./render-final.sh [out/shasha_explainer.mp4]
#   CAPTIONS=0 ./render-final.sh out/shasha_explainer_nocc.mp4     # without burned-in captions
#   MUSIC=0 ./render-final.sh                                       # narration only
#   GRAIN=0 ./render-final.sh                                       # no film grain (much smaller file)
#
# Video is rendered by Remotion (muted, one frame at a time: the machine is shared). The audio (narration +
# the synthesized pad) is mixed and muxed with ffmpeg: Remotion's own AAC mux shifts the audio ~43 ms late
# (2048 priming samples without an edit list); the ffmpeg mux is sample-accurate.
set -euo pipefail
cd "$(dirname "$0")"
FF=../bin/ffmpeg
OUT=${1:-out/shasha_explainer.mp4}
CAP=$([ "${CAPTIONS:-1}" = 0 ] && echo false || echo true)
GR=$([ "${GRAIN:-1}" = 0 ] && echo false || echo true)
mkdir -p out "$(dirname "$OUT")"

./sync-screens.sh || true
cmp -s src/data/timeline.json ../timeline.json || { echo "src/data/timeline.json differs from ../timeline.json: run ./sync-assets.sh and tools/align_words.py first" >&2; exit 1; }

t0=$(date +%s)
npx remotion render src/index.ts Main out/.video.mp4 --muted --concurrency=1 \
  --props="{\"captions\":$CAP,\"grain\":$GR,\"music\":false}"

if [ "${MUSIC:-1}" = 1 ] && [ -f public/audio/music_bed.wav ]; then
  "$FF" -v error -y -i public/audio/narration_full.wav -i public/audio/music_bed.wav -filter_complex \
    "[0:a]aformat=sample_fmts=fltp:channel_layouts=stereo[v];[v][1:a]amix=inputs=2:normalize=0:duration=first[a]" \
    -map "[a]" -c:a pcm_s24le out/.mix.wav
else
  "$FF" -v error -y -i public/audio/narration_full.wav -ac 2 -c:a pcm_s24le out/.mix.wav
fi
"$FF" -v error -y -i out/.video.mp4 -i out/.mix.wav -map 0:v:0 -map 1:a:0 -c:v copy -c:a aac -b:a 256k \
  -shortest -movflags +faststart "$OUT"
rm -f out/.video.mp4 out/.mix.wav

echo "done in $(( ($(date +%s) - t0) / 60 )) min -> $OUT"
../bin/ffprobe -v error -show_entries format=duration,size -of default=nw=1 "$OUT"
"$FF" -hide_banner -nostats -i "$OUT" -map 0:a -af ebur128=framelog=quiet -f null - 2>&1 | grep -E "^\s+(I|LRA):" || true
