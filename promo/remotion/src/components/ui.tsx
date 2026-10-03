import React from 'react';
import {useCurrentFrame} from 'remotion';
import {arDigits, brand, rgba} from '../brand';
import {clamp, easeInOut, easeOut, mix, sp, tween} from '../lib/anim';
import {FEATURE_TV} from '../lib/layout';
import {dir, endSide, flowSign, isEn, startSide} from '../lib/lang';
import {Icon, IconName} from './Icons';

export type Tone = 'teal' | 'amber' | 'kids' | 'error' | 'neutral';
export const toneColor = (t: Tone) =>
  t === 'teal' ? brand.primary : t === 'amber' ? brand.amber : t === 'kids' ? brand.kids : t === 'error' ? brand.error : brand.muted;

const abs = (s: React.CSSProperties): React.CSSProperties => ({position: 'absolute', ...s});

// --- the English layout --------------------------------------------------------------------------------------
// The scenes are laid out for Arabic (right to left: reading starts at the right edge of the stage). The English version
// is the same layout mirrored about the stage's vertical axis: a block that sits `x` px from the right edge in Arabic
// sits `x` px from the left edge in English, a slide that goes one way goes the other. Every helper returns what the
// Arabic scene used before, so the Arabic render does not change.
export const STAGE_W = 1920;
/** `right: x` in Arabic, `left: x` in English: a block `x` px from the edge where reading starts. */
export const atStart = (x: number): React.CSSProperties => ({[startSide]: x});
/** `left: x` in Arabic, `right: x` in English: a block `x` px from the edge where reading ends. */
export const atEnd = (x: number): React.CSSProperties => ({[endSide]: x});
/** Left edge of the block [x, x + w] of the Arabic layout, mirrored about the stage's vertical axis in English. */
export const mx = (x: number, w = 0): number => (isEn ? STAGE_W - x - w : x);
/** The feature tour's TV (screen rect, stage px), on the side where the layout puts it: the left in Arabic, the right in English. */
export const featureTv = (w: number = FEATURE_TV.w) => ({x: mx(FEATURE_TV.x, w), y: FEATURE_TV.y, w});
/** "1 / 5", the step counter of a section header (Arabic-Indic digits in Arabic, Western in English). */
export const stepOf = (n: number, total: number) => `${arDigits(n)} / ${arDigits(total)}`;

/** Icon + label pill. `t` = entrance progress (0..1); enters from the left in Arabic (RTL "forward"), from the right in English. */
export const Chip: React.FC<{
  icon?: IconName;
  label: React.ReactNode;
  tone?: Tone;
  t?: number;
  size?: number; // label font size
  dim?: number; // 0..1 fades to 30 %
  style?: React.CSSProperties;
  sub?: React.ReactNode;
  mono?: boolean;
}> = ({icon, label, tone = 'teal', t = 1, size = 32, dim = 0, style, sub, mono}) => {
  const c = toneColor(tone);
  const h = size * 2.25;
  return (
    <div
      style={{
        direction: dir,
        display: 'inline-flex',
        alignItems: 'center',
        gap: size * 0.42,
        height: sub ? h * 1.32 : h,
        padding: `0 ${size * 0.8}px 0 ${size * 0.8}px`,
        [isEn ? 'paddingLeft' : 'paddingRight']: icon ? size * 0.34 : size * 0.8,
        borderRadius: 999,
        background: `linear-gradient(180deg, ${rgba('#1A222D', 0.96)}, ${rgba('#131920', 0.96)})`,
        border: `1.5px solid ${rgba(c, 0.38)}`,
        boxShadow: `0 16px 40px rgba(0,0,0,0.35), inset 0 1px 0 rgba(255,255,255,0.04)`,
        fontFamily: brand.font,
        color: brand.text,
        opacity: clamp(t * 1.4) * (1 - dim * 0.7),
        transform: `translateX(${(1 - t) * -46 * flowSign}px) scale(${mix(0.94, 1, t)})`,
        whiteSpace: 'nowrap',
        ...style,
      }}
    >
      {icon ? (
        <div
          style={{
            width: h - size * 0.55,
            height: h - size * 0.55,
            borderRadius: 999,
            background: rgba(c, 0.14),
            display: 'grid',
            placeItems: 'center',
            flex: 'none',
          }}
        >
          <Icon name={icon} size={size * 1.02} color={c} stroke={2.1} />
        </div>
      ) : null}
      <div style={{display: 'flex', flexDirection: 'column', lineHeight: 1.2}}>
        <span style={{fontSize: size, fontWeight: 600, fontFamily: mono ? brand.mono : brand.font}}>{label}</span>
        {sub ? <span style={{fontSize: size * 0.62, color: brand.muted, fontFamily: brand.mono, direction: 'ltr', textAlign: isEn ? 'left' : 'right'}}>{sub}</span> : null}
      </div>
    </div>
  );
};

/** Amber strike drawn across its box (progress 0..1 over ~8 frames), from the reading start (right in Arabic, left in English). */
export const Strike: React.FC<{p: number; color?: string; width?: number}> = ({p, color = brand.amber, width = 7}) => (
  <svg
    style={abs({left: -6, top: -6, width: 'calc(100% + 12px)', height: 'calc(100% + 12px)', overflow: 'visible', pointerEvents: 'none'})}
    viewBox="0 0 100 100"
    preserveAspectRatio="none"
  >
    <line
      x1={isEn ? 0 : 100}
      y1={30}
      x2={mix(isEn ? 0 : 100, isEn ? 100 : 0, p)}
      y2={mix(30, 70, p)}
      stroke={color}
      strokeWidth={width}
      strokeLinecap="round"
      vectorEffect="non-scaling-stroke"
      opacity={p > 0 ? 1 : 0}
    />
  </svg>
);

/** A crossed-out item: appears at 60 %, amber strike draws in 8 frames at `strikeAt`, then fades to 25 %. */
export const CrossedOut: React.FC<{
  from: number;
  strikeAt: number;
  children: React.ReactNode;
  style?: React.CSSProperties;
  out?: number;
}> = ({from, strikeAt, children, style, out}) => {
  const f = useCurrentFrame();
  const inT = sp(f, from, {damping: 20});
  const s = tween(f, strikeAt, 8, easeInOut);
  const fade = tween(f, strikeAt + 8, 14);
  const gone = out !== undefined ? tween(f, out, 12) : 0;
  return (
    <div
      style={{
        position: 'relative',
        display: 'inline-block',
        opacity: clamp(inT * 1.3) * (1 - gone),
        transform: `translateX(${(1 - inT) * -30 * flowSign}px)`,
        ...style,
      }}
    >
      <div style={{opacity: s > 0 ? mix(0.62, 0.25, fade) : 0.62 + 0.38 * (1 - clamp(inT))}}>{children}</div>
      <Strike p={s} />
    </div>
  );
};

export type FocusKey = {f: number; rect: [number, number, number, number]; r?: number; press?: boolean};

/**
 * D-pad focus ring that glides between rectangles (spring). Rects in the parent's px space.
 * Put it inside the screen space (1920x1080) or on the stage.
 */
export const FocusRing: React.FC<{
  track: FocusKey[];
  until?: number;
  color?: string;
  width?: number;
  radius?: number;
  pad?: number;
  presses?: number[];
  /** glide: calm spring by default; `frames` = a fixed-length ease instead (key-by-key typing lands on time) */
  glide?: {damping?: number; stiffness?: number; frames?: number};
}> = ({track, until, color = brand.primary, width = 5, radius = 20, pad = 8, presses = [], glide}) => {
  const f = useCurrentFrame();
  if (!track.length || f < track[0].f) return null;
  let i = 0;
  for (let k = 0; k < track.length; k++) if (track[k].f <= f) i = k;
  const cur = track[i].rect;
  let rect = cur;
  if (i > 0) {
    const t = glide?.frames ? tween(f, track[i].f, glide.frames, easeInOut) : sp(f, track[i].f, {damping: 24, stiffness: 150, ...glide});
    const prev = track[i - 1].rect;
    rect = prev.map((v, k) => mix(v, cur[k], t)) as typeof cur;
  }
  const appear = sp(f, track[0].f, {damping: 22});
  const gone = until !== undefined ? tween(f, until, 10) : 0;
  const breathe = 0.5 + 0.5 * Math.sin(f / 9);
  // press feedback: contract then release
  let press = 0;
  for (const p of presses) {
    if (f >= p && f < p + 14) press = Math.max(press, Math.sin(((f - p) / 14) * Math.PI));
  }
  const [x, y, w, h] = rect;
  const s = mix(1.08, 1, appear) * (1 - press * 0.035);
  const r = track[i].r ?? radius;
  // arrival pulse: a soft ring expanding out of the focus ring when it lands on a new element
  const landed = f - track[i].f - (i > 0 ? 8 : 4);
  const pulse = landed >= 0 && landed < 20 ? landed / 20 : -1;
  return (
    <>
      {pulse >= 0 ? (
        <div
          style={abs({
            left: x - pad - 18 * pulse,
            top: y - pad - 18 * pulse,
            width: w + (pad + 18 * pulse) * 2,
            height: h + (pad + 18 * pulse) * 2,
            borderRadius: r + pad * 0.6 + 18 * pulse,
            border: `${Math.max(1, width * 0.6)}px solid ${color}`,
            opacity: (1 - pulse) * 0.55 * (1 - gone),
            pointerEvents: 'none',
          })}
        />
      ) : null}
    <div
      style={abs({
        left: x - pad,
        top: y - pad,
        width: w + pad * 2,
        height: h + pad * 2,
        borderRadius: r + pad * 0.6,
        border: `${width}px solid ${color}`,
        boxShadow: `0 0 ${24 + breathe * 14}px ${rgba(color, 0.45 + press * 0.3)}, inset 0 0 ${14 + press * 20}px ${rgba(color, 0.18 + press * 0.25)}`,
        opacity: appear * (1 - gone),
        transform: `scale(${s})`,
        pointerEvents: 'none',
      })}
    />
    </>
  );
};

/** Word-by-word kinetic line. `starts[i]` = frame each word appears (e.g. from beats). */
export const WordReveal: React.FC<{
  words: string[];
  starts: number[];
  style?: React.CSSProperties;
  wordStyle?: (i: number) => React.CSSProperties;
  dur?: number;
  blur?: boolean;
}> = ({words, starts, style, wordStyle, dur = 16, blur = true}) => {
  const f = useCurrentFrame();
  return (
    <div style={{direction: dir, fontFamily: brand.font, ...style}}>
      {words.map((w, i) => {
        const t = tween(f, starts[i] ?? starts[starts.length - 1], dur, easeOut);
        return (
          <React.Fragment key={i}>
            <span
              style={{
                display: 'inline-block',
                opacity: t,
                transform: `translateY(${(1 - t) * 0.32}em)`,
                filter: blur && t < 1 ? `blur(${(1 - t) * 8}px)` : undefined,
                ...(wordStyle ? wordStyle(i) : {}),
              }}
            >
              {w}
            </span>
            {i < words.length - 1 ? ' ' : null}
          </React.Fragment>
        );
      })}
    </div>
  );
};

/** Scene header: small kicker + title at the top right in Arabic (the reading start), at the top left in English. */
export const Header: React.FC<{kicker?: string; title: string; from?: number; index?: string; out?: number}> = ({
  kicker,
  title,
  from = 0,
  index,
  out,
}) => {
  const f = useCurrentFrame();
  const a = tween(f, from, 18);
  const b = tween(f, from + 5, 20);
  const gone = out !== undefined ? tween(f, out, 12) : 0;
  return (
    <div
      style={abs({
        top: 58,
        ...atStart(96),
        direction: dir,
        fontFamily: brand.font,
        color: brand.text,
        opacity: 1 - gone,
        textAlign: isEn ? 'left' : 'right',
      })}
    >
      {kicker ? (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 14,
            fontSize: 25,
            fontWeight: 600,
            color: brand.primary,
            opacity: a,
            transform: `translateX(${(1 - a) * -20 * flowSign}px)`,
            letterSpacing: 0,
          }}
        >
          <span style={{width: 26, height: 3, borderRadius: 2, background: brand.primary, display: 'inline-block'}} />
          <span>{kicker}</span>
          {index ? <span style={{color: brand.muted, fontWeight: 500}}>{index}</span> : null}
        </div>
      ) : null}
      <div
        style={{
          fontSize: 54,
          fontWeight: 700,
          lineHeight: 1.25,
          marginTop: 4,
          opacity: b,
          transform: `translateY(${(1 - b) * 16}px)`,
        }}
      >
        {title}
      </div>
    </div>
  );
};

/** Soft card surface. */
export const Card: React.FC<{style?: React.CSSProperties; children?: React.ReactNode; tone?: Tone; glow?: number}> = ({
  style,
  children,
  tone,
  glow = 0,
}) => {
  const c = tone ? toneColor(tone) : undefined;
  return (
    <div
      style={{
        background: 'linear-gradient(180deg, #19212C 0%, #131920 100%)',
        border: `1.5px solid ${c ? rgba(c, 0.45) : 'rgba(234,240,246,0.07)'}`,
        borderRadius: 28,
        boxShadow: `0 30px 70px rgba(0,0,0,0.42), inset 0 1px 0 rgba(255,255,255,0.04)${
          c && glow ? `, 0 0 ${40 * glow}px ${rgba(c, 0.25 * glow)}` : ''
        }`,
        direction: dir,
        fontFamily: brand.font,
        color: brand.text,
        ...style,
      }}
    >
      {children}
    </div>
  );
};

/** Round check badge that pops in. */
export const CheckBadge: React.FC<{t: number; size?: number; color?: string; icon?: IconName}> = ({
  t,
  size = 56,
  color = brand.primary,
  icon = 'check',
}) => (
  <div
    style={{
      width: size,
      height: size,
      borderRadius: 999,
      background: color,
      display: 'grid',
      placeItems: 'center',
      transform: `scale(${t})`,
      opacity: clamp(t * 2),
      boxShadow: `0 0 ${size * 0.5}px ${rgba(color, 0.45)}`,
      flex: 'none',
    }}
  >
    <Icon name={icon} size={size * 0.62} color={color === brand.primary ? brand.onPrimary : brand.onAmber} stroke={3} />
  </div>
);

/** Absolutely positioned helper. */
export const At: React.FC<{x: number; y: number; w?: number; h?: number; style?: React.CSSProperties; children?: React.ReactNode}> = ({
  x,
  y,
  w,
  h,
  style,
  children,
}) => <div style={abs({left: x, top: y, width: w, height: h, ...style})}>{children}</div>;

/** Ripple ring expanding from a point (for presses / scans). */
export const Ripple: React.FC<{at: number; x: number; y: number; color?: string; size?: number}> = ({
  at,
  x,
  y,
  color = brand.primary,
  size = 120,
}) => {
  const f = useCurrentFrame();
  if (f < at || f > at + 24) return null;
  const t = tween(f, at, 24, easeOut);
  return (
    <div
      style={abs({
        left: x - (size * t) / 2,
        top: y - (size * t) / 2,
        width: size * t,
        height: size * t,
        borderRadius: 999,
        border: `3px solid ${color}`,
        opacity: 1 - t,
        pointerEvents: 'none',
      })}
    />
  );
};
