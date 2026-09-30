#!/usr/bin/env bash
# Compressed copy of the final render for the repository (GitHub's 29 MB comfort limit):
# 2-pass x264 at 1080p (tune animation, aq-mode 3 for the dark theme), AAC 96 kb/s. The video bitrate is derived
# from the duration so the file lands on the target size. A master rendered WITH film grain should be denoised
# first (the grain is only noise to the encoder): DENOISE=1. The delivered copy is made from a GRAIN=0 master.
#
#   ./compress.sh out/shasha_explainer.mp4 ../../docs/video/shasha-explainer.mp4 [target_MB=27.8]
#   DENOISE=1 ./compress.sh out/with_grain.mp4 out/small.mp4
set -euo pipefail
cd "$(dirname "$0")"
FF=../bin/ffmpeg
FP=../bin/ffprobe
IN=$1
OUT=$2
TARGET_MB=${3:-27.8}
AK=96
D=$("$FP" -v error -show_entries format=duration -of csv=p=0 "$IN")
# total kb/s = MB * 8388.608 / seconds; minus audio and ~1.5% container overhead
VK=$(awk -v t="$TARGET_MB" -v d="$D" -v a="$AK" 'BEGIN{printf "%d", (t * 8388.608 / d) * 0.985 - a}')
echo "duration ${D}s -> video ${VK} kb/s + audio ${AK} kb/s"
mkdir -p out
VF=$([ "${DENOISE:-0}" = 1 ] && echo "hqdn3d=3:2:8:6,format=yuv420p" || echo "format=yuv420p")
X264=(-c:v libx264 -preset slow -tune animation -profile:v high -b:v "${VK}k" -maxrate "$((VK * 2))k" -bufsize "$((VK * 4))k" -g 300 -keyint_min 30 -x264-params aq-mode=3)
"$FF" -v error -stats -y -i "$IN" -vf "$VF" "${X264[@]}" -pass 1 -passlogfile out/.x264pass -an -f mp4 /dev/null
"$FF" -v error -stats -y -i "$IN" -vf "$VF" "${X264[@]}" -pass 2 -passlogfile out/.x264pass \
  -c:a aac -b:a "${AK}k" -ar 48000 -movflags +faststart "$OUT"
rm -f out/.x264pass*
"$FP" -v error -show_entries format=duration,size,bit_rate -of default=nw=1 "$OUT"
