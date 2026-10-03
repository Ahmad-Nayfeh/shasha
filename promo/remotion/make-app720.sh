#!/usr/bin/env bash
# The small copy bundled with the app (Settings > Guide & video): 1280x720, 2-pass x264 (tune animation),
# AAC 64 kb/s mono (the narration), +faststart. The video bitrate is derived from the duration so the file lands on
# the target size (default 11.6 MB = 11,600,000 bytes, under the app's 12 MB budget): about 190-230 kb/s.
#
#   ./make-app720.sh out/shasha_explainer.mp4 out/app/shasha-explainer-ar-720p.mp4 [target_MB=11.6]
set -euo pipefail
cd "$(dirname "$0")"
FF=../bin/ffmpeg
FP=../bin/ffprobe
IN=$1
OUT=$2
TARGET_MB=${3:-11.6}
AK=64
D=$("$FP" -v error -show_entries format=duration -of csv=p=0 "$IN")
# total kb/s = bytes * 8 / 1000 / seconds; minus audio and ~1.5% container overhead; kept within 180..250 kb/s
VK=$(awk -v t="$TARGET_MB" -v d="$D" -v a="$AK" 'BEGIN{v = int((t * 8000 / d) * 0.985 - a); if (v > 250) v = 250; if (v < 180) v = 180; print v}')
echo "duration ${D}s -> video ${VK} kb/s + audio ${AK} kb/s (mono)"
mkdir -p out "$(dirname "$OUT")"
VF="scale=1280:720:flags=lanczos,format=yuv420p"
X264=(-c:v libx264 -preset slow -tune animation -profile:v high -b:v "${VK}k" -maxrate "$((VK * 3))k" -bufsize "$((VK * 6))k" -g 300 -keyint_min 30 -x264-params aq-mode=3)
P=out/.x264pass720
"$FF" -v error -nostats -y -i "$IN" -vf "$VF" "${X264[@]}" -pass 1 -passlogfile "$P" -an -f mp4 /dev/null
"$FF" -v error -nostats -y -i "$IN" -vf "$VF" "${X264[@]}" -pass 2 -passlogfile "$P" \
  -c:a aac -b:a "${AK}k" -ac 1 -ar 48000 -movflags +faststart "$OUT"
rm -f "$P"*
"$FP" -v error -show_entries format=duration,size,bit_rate -of default=nw=1 "$OUT"
