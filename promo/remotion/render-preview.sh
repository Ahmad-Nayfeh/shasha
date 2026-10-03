#!/usr/bin/env bash
# Short preview with sample-accurate audio:  ./render-preview.sh <fromFrame> <toFrame> [out.mp4]
# (keep it <= 300 frames = 10 s on the shared machine). Scene frames: see ../timeline.json (TV) or
# ../timeline-mobile.json (phone, with COMP=Mobile).
#   COMP=Mobile ./render-preview.sh 1584 1884
set -euo pipefail
cd "$(dirname "$0")"
FF=../bin/ffmpeg
COMP=${COMP:-Main}
if [ "$COMP" = Mobile ]; then AUD=public/audio-mobile; else AUD=public/audio; fi
A=$1; B=$2; OUT=${3:-out/preview_${COMP}_${A}-${B}.mp4}
mkdir -p "$(dirname "$OUT")"
npx remotion render src/index.ts "$COMP" out/.pv.mp4 --muted --concurrency="${CONCURRENCY:-1}" --frames="$A-$B" --log=error
SS=$(awk -v a="$A" 'BEGIN{printf "%.6f", a/30}')
DUR=$(awk -v a="$A" -v b="$B" 'BEGIN{printf "%.6f", (b-a+1)/30}')
"$FF" -v error -y -ss "$SS" -t "$DUR" -i "$AUD/narration_full.wav" -ss "$SS" -t "$DUR" -i "$AUD/music_bed.wav" \
  -filter_complex "[0:a]aformat=sample_fmts=fltp:channel_layouts=stereo[v];[v][1:a]amix=inputs=2:normalize=0:duration=first[a]" \
  -map "[a]" -c:a pcm_s16le out/.pv.wav
"$FF" -v error -y -i out/.pv.mp4 -i out/.pv.wav -map 0:v:0 -map 1:a:0 -c:v copy -c:a aac -b:a 192k -shortest -movflags +faststart "$OUT"
rm -f out/.pv.mp4 out/.pv.wav
echo "-> $OUT"
