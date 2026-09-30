import {Easing, interpolate, spring} from 'remotion';
import {FPS} from './timeline';

// Calm motion language: cubic/quint ease-outs, springs with damping >= 18 (no bounce).
export const easeOut = Easing.bezier(0.16, 1, 0.3, 1); // expo-ish out, very smooth landing
export const easeOutCubic = Easing.out(Easing.cubic);
export const easeInOut = Easing.bezier(0.65, 0, 0.35, 1);
export const easeIn = Easing.in(Easing.cubic);

export const clamp = (v: number, a = 0, b = 1) => Math.min(b, Math.max(a, v));
export const mix = (a: number, b: number, t: number) => a + (b - a) * t;

/** 0→1 over [from, from+dur] with easing, clamped. */
export const tween = (f: number, from: number, dur: number, easing: (t: number) => number = easeOut) =>
  interpolate(f, [from, from + Math.max(1, dur)], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing,
  });

/** Spring 0→1 starting at `from` (frames). */
export const sp = (f: number, from: number, config: {damping?: number; stiffness?: number; mass?: number} = {}) =>
  spring({frame: f - from, fps: FPS, config: {damping: 20, stiffness: 110, mass: 1, ...config}});

/** Visible window: fades in at `a`, fades out ending at `b`. */
export const win = (f: number, a: number, b: number, fin = 12, fout = 12) =>
  Math.min(tween(f, a, fin), 1 - tween(f, b - fout, fout, easeInOut));

/** Interpolate through keyframes [{f, v}] with easing between each pair. */
export const keys = (f: number, ks: Array<[number, number]>, easing: (t: number) => number = easeInOut) => {
  if (f <= ks[0][0]) return ks[0][1];
  for (let i = 0; i < ks.length - 1; i++) {
    const [f0, v0] = ks[i];
    const [f1, v1] = ks[i + 1];
    if (f <= f1) return mix(v0, v1, easing(clamp((f - f0) / Math.max(1, f1 - f0))));
  }
  return ks[ks.length - 1][1];
};

/** Deterministic pseudo random in [0,1). */
export const rand = (seed: number) => {
  const x = Math.sin(seed * 127.1 + 311.7) * 43758.5453;
  return x - Math.floor(x);
};

/** Typewriter: number of characters visible at frame f (cps = chars per frame). */
export const typed = (f: number, from: number, len: number, perFrame = 2) => clamp(Math.floor((f - from) * perFrame), 0, len);
