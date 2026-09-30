"""Check a rendered clip's audio against the source mix (narration + music) at the clip's start frame.
  ../.venv/bin/python tools/check_sync.py <clip.mp4> <startFrame>
Prints the best lag (ms) and the residual; lag 0 = sample-accurate A/V sync."""
import subprocess, sys
from pathlib import Path
import numpy as np
RM = Path(__file__).resolve().parents[1]
FF = str(RM.parent / 'bin/ffmpeg')
SR = 48000
clip, start = sys.argv[1], int(sys.argv[2])
def load(args):
    raw = subprocess.run([FF, '-v', 'error', *args, '-ac', '1', '-ar', str(SR), '-f', 'f32le', '-'], capture_output=True).stdout
    return np.frombuffer(raw, np.float32).astype(np.float64)
a = load(['-i', clip])
ss = f'{start / 30:.6f}'
n = load(['-ss', ss, '-i', str(RM / 'public/audio/narration_full.wav')])
m = load(['-ss', ss, '-i', str(RM / 'public/audio/music_bed.wav')])
L = min(len(a), len(n), len(m)) - 4000
ref = n[:L] + m[:L]
best = None
for lag in range(-3000, 3001, 2):
    aa = a[max(0, lag):max(0, lag) + L - 3000]
    rr = ref[max(0, -lag):max(0, -lag) + L - 3000]
    k = min(len(aa), len(rr))
    r = float(np.sqrt(np.mean((aa[:k] - rr[:k]) ** 2)))
    if best is None or r < best[0]:
        best = (r, lag)
print(f'{Path(clip).name}: audio lag {best[1] / SR * 1000:+.2f} ms, residual {best[0]:.5f} (signal rms {np.sqrt(np.mean(a ** 2)):.4f})')
