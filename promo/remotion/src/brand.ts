// Brand tokens — same values as /home/user/shasha/branding/README.md and the app's ShashaColors.
import {isEn} from './lib/lang';

export const brand = {
  bg: '#0B0F14',
  surface: '#151B24',
  surfaceVariant: '#1E2632',
  surfaceHigh: '#28323F',
  outline: '#2E3947',
  primary: '#2EC4B6',
  primaryBright: '#48DCCD',
  primaryDeep: '#1FAE9F',
  onPrimary: '#04201D',
  amber: '#FFB547',
  amberBright: '#FFC56B',
  onAmber: '#2B1A00',
  kids: '#FFD166',
  error: '#FF6B6B',
  text: '#EAF0F6',
  muted: '#8A97A8',
  faint: '#5E6B7D',
  bezel: '#05080B',
  font: "'IBM Plex Sans Arabic', sans-serif",
  mono: "'IBM Plex Mono', 'IBM Plex Sans Arabic', monospace",
};

export const rgba = (hex: string, a: number) => {
  const h = hex.replace('#', '');
  const n = parseInt(h.length === 3 ? h.split('').map((c) => c + c).join('') : h, 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
};

// Digits for on-screen counters: Arabic-Indic in the Arabic version (١ ٢ ٣ …, U+0660..U+0669), Western in the English one
// (so every scene that counts with arDigits() follows the language of the render).
export const arDigits = (n: number | string) =>
  isEn ? String(n) : String(n).replace(/[0-9]/g, (d) => String.fromCharCode(0x660 + +d));

/** Mix two hex colours (t = 0 → a, 1 → b). */
export const mixHex = (a: string, b: string, t: number) => {
  const p = (h: string) => {
    const n = parseInt(h.replace('#', ''), 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  };
  const A = p(a);
  const B = p(b);
  const c = A.map((v, i) => Math.round(v + (B[i] - v) * Math.min(1, Math.max(0, t))));
  return `rgb(${c[0]},${c[1]},${c[2]})`;
};
