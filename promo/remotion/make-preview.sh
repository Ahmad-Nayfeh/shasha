#!/usr/bin/env bash
# The animated preview in the repository README (docs/video/preview.webp): clips of 3 s taken from the video,
# 12 fps, 720 px wide, libwebp_anim (lossy, quality 55), looping.
#
#   ./make-preview.sh ../../docs/video/shasha-explainer.mp4 ../../docs/video/preview.webp 34 66 120 142 160
#                     <video>                                <output>                       <start seconds ...>
set -euo pipefail
cd "$(dirname "$0")"
FF=../bin/ffmpeg
IN=$1
OUT=$2
shift 2
[ "$#" -ge 1 ] || { echo "usage: $0 <video> <out.webp> <start_s> [<start_s> ...]" >&2; exit 2; }
ARGS=(); GRAPH=""; LABELS=""; i=0
for t in "$@"; do
  ARGS+=(-ss "$t" -t 3 -i "$IN")
  GRAPH+="[$i:v]fps=12,scale=720:-2:flags=lanczos,setpts=PTS-STARTPTS[c$i];"
  LABELS+="[c$i]"
  i=$((i + 1))
done
"$FF" -v error -y "${ARGS[@]}" -filter_complex "${GRAPH}${LABELS}concat=n=$i:v=1:a=0[v]" -map "[v]" \
  -c:v libwebp_anim -lossless 0 -quality 55 -compression_level 6 -loop 0 -an "$OUT"
ls -l "$OUT"
