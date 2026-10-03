"""Soft ambient pad for the explainer, synthesized from scratch (no samples, no downloaded music).

Slow Dmaj9 → Bm9 → Gmaj9 → A6sus progression, additive "warm triangle" voices with gentle detune,
slow filter-ish brightness LFO, a convolution reverb made of decaying noise, fade in over the logo and out
at the end. Ducked ~7 dB under the narration (envelope follower on audio/narration_full.wav) and
normalised to about -28 LUFS integrated. Writes public/audio/music_bed.wav (48 kHz stereo).

  ../.venv/bin/python tools/make_music.py
  ../.venv/bin/python tools/make_music.py --mobile    (the phone video: timeline-mobile.json, audio-mobile/ -> public/audio-mobile/music_bed.wav)
  ... --en / --mobile --en                             (the English version: timeline*-en.json, audio*-en/ -> public/audio*-en/)
"""
import json
import subprocess
import sys
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy.signal import fftconvolve

HERE = Path(__file__).resolve().parent
RM = HERE.parent
V = RM.parent
FFMPEG = str(V / 'bin/ffmpeg')
SR = 48000
MOBILE = '--mobile' in sys.argv
EN = '--en' in sys.argv   # the English version: timeline-en.json, audio-en/ -> public/audio-en/music_bed.wav
SFX = '-en' if EN else ''
AUDIO = ('audio-mobile' if MOBILE else 'audio') + SFX
tl = json.loads((V / (f'timeline-mobile{SFX}.json' if MOBILE else f'timeline{SFX}.json')).read_text())
DUR = tl['total_duration_s']
N = int(DUR * SR)
t = np.arange(N) / SR
rng = np.random.default_rng(3)


def hz(midi):
    return 440.0 * 2 ** ((midi - 69) / 12)


# chords as MIDI notes (voiced low and open)
CHORDS = [
    [50, 57, 61, 64, 66, 69],   # Dmaj9: D A C# E F# A
    [47, 54, 59, 61, 62, 66],   # Bm9:   B F# B C# D F#
    [43, 50, 57, 59, 62, 66],   # Gmaj9: G D A B D F#
    [45, 52, 57, 59, 62, 64],   # A6sus2-ish: A E A B D E
]
CHORD_LEN = 9.0   # seconds per chord
XF = 3.5          # crossfade


def voice(tt, freq, detune_cents, phase, bright):
    """Warm additive tone (odd+even harmonics falling off fast) over the time slice tt."""
    f = freq * 2 ** (detune_cents / 1200)
    out = np.zeros(len(tt), dtype=np.float64)
    for k, a in [(1, 1.0), (2, 0.28), (3, 0.12), (4, 0.05), (5, 0.025)]:
        out += (a * (bright if k > 1 else 1.0)) * np.sin(2 * np.pi * f * k * tt + phase * k)
    return out


def chord_window(i):
    start = i * CHORD_LEN
    a = max(0, int((start - XF) * SR))
    b = min(N, int((start + CHORD_LEN + XF) * SR))
    return a, b


def chord_env_slice(i, tt):
    start = i * CHORD_LEN
    env = np.clip((tt - (start - XF)) / XF, 0, 1) * np.clip(((start + CHORD_LEN + XF) - tt) / XF, 0, 1)
    return env ** 1.5


# brightness LFO (very slow) for gentle movement
L = np.zeros(N)
R = np.zeros(N)
n_chords = int(np.ceil(DUR / CHORD_LEN)) + 1
for i in range(n_chords):
    a, b = chord_window(i)
    if b <= a:
        continue
    tt = t[a:b]
    env = chord_env_slice(i, tt)
    bright = 0.55 + 0.25 * np.sin(2 * np.pi * tt / 23.0) + 0.1 * np.sin(2 * np.pi * tt / 7.3)
    notes = CHORDS[i % len(CHORDS)]
    for j, m in enumerate(notes):
        amp = 0.16 if j == 0 else 0.1
        ph = rng.uniform(0, 2 * np.pi)
        trem = 0.85 + 0.15 * np.sin(2 * np.pi * tt / (5.0 + j * 0.9) + j)
        L[a:b] += amp * env * trem * voice(tt, hz(m), -4 + j, ph, bright)
        R[a:b] += amp * env * trem * voice(tt, hz(m), 4 - j, ph + 0.7, bright)
    # soft sub on the root, one octave down
    sub = 0.08 * env * np.sin(2 * np.pi * hz(notes[0] - 12) * tt)
    L[a:b] += sub
    R[a:b] += sub

# airy shimmer: high sine partials fading in and out
for k, m in enumerate([81, 85, 88, 90]):
    lfo = np.clip(np.sin(2 * np.pi * t / (17 + 3 * k) + k), 0, 1) ** 2
    L += 0.012 * lfo * np.sin(2 * np.pi * hz(m) * t + k)
    R += 0.012 * lfo * np.sin(2 * np.pi * hz(m) * t + k + 1.3)

# reverb: exponentially decaying stereo noise IR (2.8 s), mixed wet
ir_len = int(2.8 * SR)
decay = np.exp(-np.arange(ir_len) / (0.9 * SR))
irl = rng.normal(0, 1, ir_len) * decay
irr = rng.normal(0, 1, ir_len) * decay
irl /= np.sqrt((irl ** 2).sum())
irr /= np.sqrt((irr ** 2).sum())
wetL = fftconvolve(L, irl)[:N]
wetR = fftconvolve(R, irr)[:N]
L = 0.55 * L + 0.8 * wetL
R = 0.55 * R + 0.8 * wetR

# master fades: in over the logo lead-in, out over the last 3.5 s
fade_in = np.clip(t / 2.5, 0, 1) ** 2
fade_out = np.clip((DUR - t) / 3.5, 0, 1) ** 1.5
L *= fade_in * fade_out
R *= fade_in * fade_out

# ducking under the narration
voice_audio, vsr = sf.read(str(V / AUDIO / 'narration_full.wav'), dtype='float64')
if voice_audio.ndim > 1:
    voice_audio = voice_audio.mean(axis=1)
assert vsr == SR, vsr
voice_audio = np.pad(voice_audio, (0, max(0, N - len(voice_audio))))[:N]
win = int(0.05 * SR)
rms = np.sqrt(np.convolve(voice_audio ** 2, np.ones(win) / win, mode='same'))
active = (rms > 10 ** (-42 / 20)).astype(np.float64)
# attack 80 ms / release 450 ms smoothing, computed per 10 ms block
blk = int(0.01 * SR)
nb = N // blk + 1
a_blocks = np.array([active[i * blk:(i + 1) * blk].max() if i * blk < N else 0 for i in range(nb)])
g = np.zeros(nb)
state = 0.0
att = 1 - np.exp(-1 / 8.0)
rel = 1 - np.exp(-1 / 45.0)
for i in range(nb):
    target = a_blocks[i]
    state += (target - state) * (att if target > state else rel)
    g[i] = state
duck_db = -7.0
gain_blocks = 10 ** (duck_db * g / 20)
gain = np.repeat(gain_blocks, blk)[:N]
L *= gain
R *= gain

mix = np.stack([L, R], axis=1)
mix /= np.abs(mix).max() + 1e-9
(RM / 'public' / AUDIO).mkdir(parents=True, exist_ok=True)
tmp = RM / 'public' / AUDIO / 'music_bed_raw.wav'
out = RM / 'public' / AUDIO / 'music_bed.wav'
sf.write(str(tmp), (mix * 0.5).astype(np.float32), SR, subtype='FLOAT')

# measure and normalise to -28 LUFS integrated
meas = subprocess.run([FFMPEG, '-hide_banner', '-nostats', '-i', str(tmp), '-af', 'ebur128=framelog=quiet', '-f', 'null', '-'],
                      capture_output=True, text=True).stderr
lufs = float([l for l in meas.splitlines() if l.strip().startswith('I:')][-1].split()[1])
gain_db = -28.0 - lufs
subprocess.run([FFMPEG, '-v', 'error', '-y', '-i', str(tmp), '-af', f'volume={gain_db:.2f}dB', '-c:a', 'pcm_s16le', str(out)], check=True)
tmp.unlink()
meas2 = subprocess.run([FFMPEG, '-hide_banner', '-nostats', '-i', str(out), '-af', 'ebur128=framelog=quiet', '-f', 'null', '-'],
                       capture_output=True, text=True).stderr
print('music bed:', [l.strip() for l in meas2.splitlines() if l.strip().startswith(('I:', 'LRA:'))][-2:], '->', out)
