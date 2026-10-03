import React from 'react';
import {brand, rgba} from '../brand';
import {dir, flowSign, isEn, startSide} from '../lib/lang';
import {Icon, IconName} from './Icons';
import {PhoneFrame, Tap} from './Phone';
import {MH, MW} from './MobileUi';
import {Card, Chip, CrossedOut, Tone} from './ui';
import {clamp, mix, sp, tween} from '../lib/anim';

// The phone video's stage (1920x1080): the phone in the middle, big, and callouts on both sides in the TV video's
// language (cards, chips, ticks). The phone's screen is laid out in phone space (390 x 844).
// Arabic runs right-to-left (callouts, pills and the header on the right of the phone); the English version mirrors the
// whole stage: they sit on the left, and everything slides in from the other side (the shared header, chip, strike and
// list card of ./ui and ./ListCard do the same for the TV video).

export const PHONE = {scale: 1.02, x: 960 - ((MW + 26) * 1.02) / 2, y: 52};
/** Phone-space point -> stage px. */
export const toStageM = (x: number, y: number): [number, number] => [PHONE.x + (13 + x) * PHONE.scale, PHONE.y + (13 + y) * PHONE.scale];

export const PhoneStage: React.FC<{f: number; from?: number; children: React.ReactNode; opacity?: number; dy?: number; rotate?: number; glow?: string}> = ({f, from = 0, children, opacity = 1, dy = 0, rotate = 0, glow}) => {
  const t = sp(f, from, {damping: 22, stiffness: 90});
  return (
    <>
      {glow ? <div style={{position: 'absolute', left: 560, top: 90, width: 800, height: 820, background: `radial-gradient(50% 50% at 50% 50%, ${rgba(glow, 0.1)}, transparent 70%)`}} /> : null}
      <PhoneFrame x={PHONE.x} y={PHONE.y + (1 - t) * 700 + dy} scale={PHONE.scale} opacity={clamp(t * 2) * opacity} rotate={mix(6 * flowSign, 0, t) + rotate}>
        {children}
      </PhoneFrame>
    </>
  );
};

/** A finger tap at a phone-space point (inside a PhoneStage). */
export const TapAt: React.FC<{f: number; at: number; x: number; y: number}> = ({f, at, x, y}) => <Tap f={f} at={at} x={x} y={y} />;

/**
 * A titled callout card with a big icon, for the sides of the phone. side: 'right' or 'left' (default: where reading
 * starts, so the right in Arabic and the left in English). English titles are wider: a wider card (still clear of the phone) and a slightly smaller title.
 */
export const Callout: React.FC<{f: number; at: number; icon: IconName; title: string; sub?: string; side?: 'right' | 'left'; top: number; tone?: 'teal' | 'amber' | 'kids'; width?: number; out?: number; dim?: number}> = ({
  f,
  at,
  icon,
  title,
  sub,
  side = startSide,
  top,
  tone = 'teal',
  width = isEn ? 590 : 520,
  out,
  dim = 0,
}) => {
  const t = sp(f, at, {damping: 20});
  const gone = out !== undefined ? tween(f, out, 12) : 0;
  const c = tone === 'amber' ? brand.amber : tone === 'kids' ? brand.kids : brand.primary;
  return (
    <div style={{position: 'absolute', [side]: 100, top, width, opacity: clamp(t * 1.4) * (1 - gone) * (1 - dim * 0.6), transform: `translateX(${(1 - t) * (side === 'right' ? 50 : -50)}px)`}}>
      <Card style={{padding: '24px 28px', boxSizing: 'border-box'}} tone={tone}>
        <div style={{display: 'flex', alignItems: 'center', gap: 22}}>
          <div style={{width: 84, height: 84, borderRadius: 26, flex: 'none', background: rgba(c, 0.14), border: `2px solid ${rgba(c, 0.5)}`, display: 'grid', placeItems: 'center'}}>
            <Icon name={icon} size={46} color={c} stroke={2.1} />
          </div>
          <div>
            <div style={{fontSize: isEn ? 35 : 38, fontWeight: 700, lineHeight: 1.25, ...(isEn ? {textWrap: 'balance'} : null)}}>{title}</div>
            {sub ? <div style={{fontSize: isEn ? 24 : 26, color: brand.muted, marginTop: 6, lineHeight: 1.4}}>{sub}</div> : null}
          </div>
        </div>
      </Card>
    </div>
  );
};

/** A kicker + title at the top, where reading starts (the TV video's Header: right in Arabic, left in English). */
export {Header as MobileHeader} from './ui';

export {MW, MH};

// ------------------------------------------------------------------------------------------------ pills
/**
 * A pill beside the phone, on the side where reading starts (`right`: its offset from that edge): a positive chip, or
 * (with crossAt) something crossed out with the amber strike.
 */
export const SidePill: React.FC<{f: number; at: number; icon: IconName; label: string; top: number; crossAt?: number; tone?: Tone; right?: number; size?: number}> = ({
  f,
  at,
  icon,
  label,
  top,
  crossAt,
  tone = 'teal',
  right = 100,
  size = 40,
}) => (
  <div style={{position: 'absolute', [startSide]: right, top, direction: dir}}>
    {crossAt !== undefined ? (
      <CrossedOut from={at - 2} strikeAt={crossAt}>
        <div style={{display: 'flex', alignItems: 'center', gap: 16, padding: '16px 36px', borderRadius: 999, background: 'rgba(234,240,246,0.10)', border: '1.5px solid rgba(234,240,246,0.24)', fontSize: size, fontWeight: 600, color: brand.text}}>
          <Icon name={icon} size={size} color={brand.text} />
          {label}
        </div>
      </CrossedOut>
    ) : (
      <Chip icon={icon} label={label} tone={tone} size={size - 4} t={sp(f, at - 2, {damping: 20, stiffness: 120})} />
    )}
  </div>
);
