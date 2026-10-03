#!/usr/bin/env bash
# Full-length render (1920x1080, 30 fps, H.264 + AAC 48 kHz) of the TV video or the phone video.
#
#   ./render-final.sh [out/shasha_explainer.mp4]                      the TV video (composition Main)
#   COMP=Mobile ./render-final.sh [out/shasha_mobile.mp4]             the phone video (composition Mobile)
#   CONCURRENCY=2 ./render-final.sh                                    frames in parallel (default 1: the machine is shared)
#   CAPTIONS=0 ./render-final.sh out/shasha_explainer_nocc.mp4         without burned-in captions
#   MUSIC=0 ./render-final.sh                                          narration only
#   GRAIN=0 ./render-final.sh                                          no film grain (much smaller file)
#   VIDEO_LANG=en ./render-final.sh [out/shasha_explainer_en.mp4]       the English version (COMP=Mobile too)
#
# Video is rendered by Remotion (muted). The audio (narration + the synthesized pad) is mixed and muxed with ffmpeg:
# Remotion's own AAC mux shifts the audio ~43 ms late (2048 priming samples without an edit list); the ffmpeg mux
# is sample-accurate.
set -euo pipefail
cd "$(dirname "$0")"
FF=../bin/ffmpeg
COMP=${COMP:-Main}
SFX=$([ "${VIDEO_LANG:-ar}" = en ] && printf %s -en || true)
if [ "$COMP" = Mobile ]; then
  AUD=public/audio-mobile$SFX; TL=../timeline-mobile$SFX.json; DATA=src/data/timeline-mobile$SFX.json; DEF=out/shasha_mobile${SFX//-/_}.mp4
else
  AUD=public/audio$SFX; TL=../timeline$SFX.json; DATA=src/data/timeline$SFX.json; DEF=out/shasha_explainer${SFX//-/_}.mp4
fi
LANGPROP=$([ -n "$SFX" ] && echo ',"lang":"en"' || true)
OUT=${1:-$DEF}
CONC=${CONCURRENCY:-1}
CAP=$([ "${CAPTIONS:-1}" = 0 ] && echo false || echo true)
GR=$([ "${GRAIN:-1}" = 0 ] && echo false || echo true)
mkdir -p out "$(dirname "$OUT")"

./sync-screens.sh ../.. || true
cmp -s "$DATA" "$TL" || { echo "$DATA differs from $TL: run ./sync-assets.sh and tools/align_words.py first" >&2; exit 1; }

t0=$(date +%s)
V=out/.video_$COMP$SFX.mp4
M=out/.mix_$COMP$SFX.wav
npx remotion render src/index.ts "$COMP" "$V" --muted --concurrency="$CONC" \
  --props="{\"captions\":$CAP,\"grain\":$GR,\"music\":false$LANGPROP}"

if [ "${MUSIC:-1}" = 1 ] && [ -f "$AUD/music_bed.wav" ]; then
  "$FF" -v error -y -i "$AUD/narration_full.wav" -i "$AUD/music_bed.wav" -filter_complex \
    "[0:a]aformat=sample_fmts=fltp:channel_layouts=stereo[v];[v][1:a]amix=inputs=2:normalize=0:duration=first[a]" \
    -map "[a]" -c:a pcm_s24le "$M"
else
  "$FF" -v error -y -i "$AUD/narration_full.wav" -ac 2 -c:a pcm_s24le "$M"
fi
"$FF" -v error -y -i "$V" -i "$M" -map 0:v:0 -map 1:a:0 -c:v copy -c:a aac -b:a 256k \
  -shortest -movflags +faststart "$OUT"
rm -f "$V" "$M"

echo "done in $(( ($(date +%s) - t0) / 60 )) min -> $OUT"
../bin/ffprobe -v error -show_entries format=duration,size -of default=nw=1 "$OUT"
"$FF" -hide_banner -nostats -i "$OUT" -map 0:a -af ebur128=framelog=quiet -f null - 2>&1 | grep -E "^\s+(I|LRA):" || true
