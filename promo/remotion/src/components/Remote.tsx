import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand, mixHex, rgba} from '../brand';
import {clamp, mix} from '../lib/anim';
import {useSvgId} from './Brand';

// Generic Google-TV-style remote (no brand marks): power, mic, D-pad + OK, Back, Home, two blank shortcut
// buttons, volume/channel rockers. Presses glow (teal, or amber for Back/Home in lock scenes) with a
// 6-frame 0.94 → 1 scale; holds draw a radial ring; denied presses pulse amber with a small ✕.

export type RemoteBtn = 'power' | 'mic' | 'ok' | 'up' | 'down' | 'left' | 'right' | 'back' | 'home' | 'settings' | 'volUp' | 'volDown';
export type Press = {btn: RemoteBtn; f: number; color?: string};
export type Hold = {btn: RemoteBtn; from: number; to: number; color?: string};
export type Deny = {btn: RemoteBtn; f: number};

const W = 150;
const H = 580;
const DPAD = {cx: 75, cy: 205, r: 62};
const B: Record<string, {cx: number; cy: number; r: number}> = {
  power: {cx: 75, cy: 58, r: 14},
  mic: {cx: 75, cy: 112, r: 17},
  ok: {cx: DPAD.cx, cy: DPAD.cy, r: 29},
  back: {cx: 38, cy: 318, r: 21},
  home: {cx: 112, cy: 318, r: 21},
  settings: {cx: 75, cy: 318, r: 17},
};

const Glyph: React.FC<{btn: string; c: string}> = ({btn, c}) => {
  const b = B[btn];
  const s = {stroke: c, strokeWidth: 2.4, fill: 'none', strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const};
  switch (btn) {
    case 'power':
      return <path d={`M${b.cx} ${b.cy - 7}v6 M${b.cx - 5} ${b.cy - 4}a7 7 0 1 0 10 0`} {...s} strokeWidth={2} />;
    case 'mic':
      return <path d={`M${b.cx} ${b.cy - 8}a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0v-5a3 3 0 0 1 3-3z M${b.cx - 6} ${b.cy + 1}a6 6 0 0 0 12 0 M${b.cx} ${b.cy + 7}v3`} {...s} strokeWidth={1.8} />;
    case 'back':
      return <path d={`M${b.cx - 2} ${b.cy - 6}l-6 5 6 5 M${b.cx - 8} ${b.cy - 1}h9a5 5 0 0 1 0 10h-3`} {...s} strokeWidth={2.2} />;
    case 'home':
      return <path d={`M${b.cx - 8} ${b.cy - 1}l8-7 8 7v8h-5v-5h-6v5h-5z`} {...s} strokeWidth={2} />;
    case 'settings':
      return <circle cx={b.cx} cy={b.cy} r={5} {...s} strokeWidth={2} />;
    default:
      return null;
  }
};

export const Remote: React.FC<{
  x: number;
  y: number;
  scale?: number;
  rotate?: number;
  presses?: Press[];
  holds?: Hold[];
  denies?: Deny[];
  opacity?: number;
}> = ({x, y, scale = 1, rotate = -6, presses = [], holds = [], denies = [], opacity = 1}) => {
  const f = useCurrentFrame();
  const gBody = useSvgId('rb');
  const gRing = useSvgId('rr');
  // press intensity per button (0..1), and its colour
  const state: Record<string, {p: number; c: string; hold: number; deny: number}> = {};
  const get = (b: string) => (state[b] ??= {p: 0, c: brand.primary, hold: 0, deny: 0});
  for (const pr of presses) {
    const dt = f - pr.f;
    if (dt >= 0 && dt < 22) {
      const v = dt < 3 ? dt / 3 : 1 - (dt - 3) / 19;
      const s = get(pr.btn);
      if (v > s.p) {
        s.p = v;
        s.c = pr.color ?? brand.primary;
      }
    }
  }
  for (const h of holds) {
    if (f >= h.from && f <= h.to + 12) {
      const s = get(h.btn);
      s.hold = clamp((f - h.from) / Math.max(1, h.to - h.from));
      s.c = h.color ?? brand.primary;
      if (f <= h.to) s.p = Math.max(s.p, 0.75);
    }
  }
  for (const d of denies) {
    const dt = f - d.f;
    if (dt >= 0 && dt < 30) {
      const s = get(d.btn);
      s.deny = 1 - dt / 30;
      s.c = brand.amber;
      s.p = Math.max(s.p, dt < 3 ? dt / 3 : 1 - (dt - 3) / 27);
    }
  }
  const btnScale = (b: string) => {
    let s = 1;
    for (const pr of presses) {
      const dt = f - pr.f;
      if (pr.btn === b && dt >= 0 && dt < 6) s = Math.min(s, mix(0.94, 1, dt / 6));
    }
    return s;
  };
  const dir = (d: 'up' | 'down' | 'left' | 'right') => state[d]?.p ?? 0;

  return (
    <div
      style={{
        position: 'absolute',
        left: x,
        top: y,
        width: W * scale,
        height: H * scale,
        transform: `rotate(${rotate}deg)`,
        opacity,
        filter: 'drop-shadow(0 30px 40px rgba(0,0,0,0.55))',
      }}
    >
      <svg width={W * scale} height={H * scale} viewBox={`0 0 ${W} ${H}`} style={{overflow: 'visible'}}>
        <defs>
          <linearGradient id={gBody} x1="0" y1="0" x2="1" y2="0">
            <stop offset="0" stopColor="#12171D" />
            <stop offset="0.45" stopColor="#222A33" />
            <stop offset="1" stopColor="#10151A" />
          </linearGradient>
          <radialGradient id={gRing} cx="0.5" cy="0.35" r="0.7">
            <stop offset="0" stopColor="#2B3540" />
            <stop offset="1" stopColor="#161C23" />
          </radialGradient>
        </defs>
        {/* body */}
        <rect x={2} y={2} width={W - 4} height={H - 4} rx={(W - 4) / 2} fill={`url(#${gBody})`} stroke="rgba(255,255,255,0.10)" strokeWidth={1.5} />
        <rect x={10} y={8} width={W - 20} height={H - 16} rx={(W - 20) / 2} fill="none" stroke="rgba(255,255,255,0.035)" strokeWidth={1} />
        {/* D-pad ring */}
        <circle cx={DPAD.cx} cy={DPAD.cy} r={DPAD.r} fill={`url(#${gRing})`} stroke="rgba(255,255,255,0.08)" strokeWidth={1.5} />
        {(['up', 'right', 'down', 'left'] as const).map((d, i) => {
          const a = (i * Math.PI) / 2 - Math.PI / 2;
          const p = dir(d);
          const rr = DPAD.r - 14;
          const x1 = DPAD.cx + Math.cos(a - 0.42) * rr;
          const y1 = DPAD.cy + Math.sin(a - 0.42) * rr;
          const x2 = DPAD.cx + Math.cos(a + 0.42) * rr;
          const y2 = DPAD.cy + Math.sin(a + 0.42) * rr;
          const col = state[d]?.c ?? brand.primary;
          return (
            <g key={d}>
              <path
                d={`M${x1} ${y1}A${rr} ${rr} 0 0 1 ${x2} ${y2}`}
                stroke={p > 0 ? col : 'rgba(234,240,246,0.16)'}
                strokeWidth={p > 0 ? 7 : 3}
                strokeLinecap="round"
                fill="none"
                opacity={p > 0 ? 0.4 + p * 0.6 : 1}
                style={{filter: p > 0 ? `drop-shadow(0 0 ${6 * p}px ${col})` : undefined}}
              />
            </g>
          );
        })}
        {/* buttons */}
        {Object.entries(B).map(([id, b]) => {
          const s = state[id];
          const p = s?.p ?? 0;
          const c = s?.c ?? brand.primary;
          const sc = btnScale(id);
          const isOk = id === 'ok';
          return (
            <g key={id} transform={`translate(${b.cx} ${b.cy}) scale(${sc}) translate(${-b.cx} ${-b.cy})`}>
              {p > 0 ? <circle cx={b.cx} cy={b.cy} r={b.r + 10 * p} fill={rgba(c, 0.18 * p)} /> : null}
              <circle
                cx={b.cx}
                cy={b.cy}
                r={b.r}
                fill={p > 0 ? mixHex('#1C232B', c, p * 0.8) : isOk ? '#1F262F' : '#171D24'}
                stroke={p > 0 ? c : 'rgba(255,255,255,0.10)'}
                strokeWidth={p > 0 ? 2.5 : 1.2}
                style={{filter: p > 0 ? `drop-shadow(0 0 ${10 * p}px ${c})` : undefined}}
              />
              {isOk ? (
                <text x={b.cx} y={b.cy + 6} textAnchor="middle" fontSize={15} fontWeight={700} fill={p > 0.4 ? brand.onPrimary : 'rgba(234,240,246,0.55)'} fontFamily="IBM Plex Sans Arabic">
                  OK
                </text>
              ) : (
                <Glyph btn={id} c={p > 0.4 ? '#0B0F14' : 'rgba(234,240,246,0.6)'} />
              )}
              {s?.hold ? (
                <circle
                  cx={b.cx}
                  cy={b.cy}
                  r={b.r + 14}
                  fill="none"
                  stroke={c}
                  strokeWidth={5}
                  strokeLinecap="round"
                  strokeDasharray={`${2 * Math.PI * (b.r + 14) * s.hold} ${2 * Math.PI * (b.r + 14)}`}
                  transform={`rotate(-90 ${b.cx} ${b.cy})`}
                  style={{filter: `drop-shadow(0 0 6px ${c})`}}
                />
              ) : null}
              {s?.deny ? (
                <g opacity={clamp(s.deny * 1.5)}>
                  <circle cx={b.cx + b.r * 0.9} cy={b.cy - b.r * 0.9} r={11} fill={brand.amber} />
                  <path
                    d={`M${b.cx + b.r * 0.9 - 4.5} ${b.cy - b.r * 0.9 - 4.5}l9 9 M${b.cx + b.r * 0.9 + 4.5} ${b.cy - b.r * 0.9 - 4.5}l-9 9`}
                    stroke="#2B1A00"
                    strokeWidth={2.6}
                    strokeLinecap="round"
                  />
                </g>
              ) : null}
            </g>
          );
        })}
        {/* blank shortcut buttons + rockers */}
        <rect x={32} y={380} width={86} height={30} rx={15} fill="#171D24" stroke="rgba(255,255,255,0.08)" />
        <rect x={32} y={422} width={86} height={30} rx={15} fill="#171D24" stroke="rgba(255,255,255,0.08)" />
        <rect x={32} y={470} width={36} height={78} rx={18} fill="#161B21" stroke="rgba(255,255,255,0.08)" />
        <rect x={82} y={470} width={36} height={78} rx={18} fill="#161B21" stroke="rgba(255,255,255,0.08)" />
        <path d="M44 490h12 M50 484v12 M44 530h12" stroke="rgba(234,240,246,0.35)" strokeWidth={2} strokeLinecap="round" />
        <path d="M94 494l6-6 6 6 M94 524l6 6 6-6" stroke="rgba(234,240,246,0.35)" strokeWidth={2} fill="none" strokeLinecap="round" />
        {/* tiny status light */}
        <circle cx={75} cy={30} r={2.5} fill={Object.values(state).some((s) => s.p > 0.2) ? brand.primary : 'rgba(234,240,246,0.15)'} />
      </svg>
    </div>
  );
};

/** Helper: press list for a series of D-pad moves / OK presses. */
export const pressesOf = (list: Array<[RemoteBtn, number]>, color?: string): Press[] => list.map(([btn, f]) => ({btn, f, color}));

export const remoteSize = {w: W, h: H};
