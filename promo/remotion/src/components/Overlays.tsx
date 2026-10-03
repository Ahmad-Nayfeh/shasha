import React from 'react';
import {brand, rgba} from '../brand';
import {clamp, mix} from '../lib/anim';
import {dir, t} from '../lib/lang';
import {captureSize, focusRect, hasScreen, isEnglishScreen} from '../lib/screens';
import {Icon} from './Icons';

// Live overlays on real captures: they re-draw one small part of a screenshot in its app style so the
// screen can animate (PIN dots filling, the 10-second ring counting, the phrase being typed, a phone toast).
// Drawn in the capture's own pixel space (TV screen space = 1920x1080); only when the capture exists.
//
// English version: the overlays follow the capture that is drawn. The English captures (public/screens-en, left to
// right) have their own rects in screens-en.json, which `px()` reads like any other; what is laid out by hand here
// (the side a bar fills from, the side typed text starts at) follows `isEnglishScreen(name)`, so on a screen that
// still falls back to its Arabic capture the overlay keeps the Arabic geometry. The words follow the language (t()).

const px = (name: string, key: string) => {
  const r = focusRect(name, key);
  const [W, H] = captureSize(name);
  return {x: r[0] * W, y: r[1] * H, w: r[2] * W, h: r[3] * H};
};

/** The app's 4 PIN dots (36 px, 3 px ring #5B6573 / filled), `filled` 0..4 (fractional = the dot popping in). */
export const PinDotsOverlay: React.FC<{name: string; filled: number; color?: string; bg?: string; dotKey?: string}> = ({
  name,
  filled,
  color = brand.primary,
  bg = '#151B24',
  dotKey = 'dots',
}) => {
  if (!hasScreen(name)) return null;
  const d = px(name, dotKey);
  const D = 36;
  const gap = (d.w - D * 4) / 3;
  return (
    <>
      <div style={{position: 'absolute', left: d.x - 8, top: d.y - 8, width: d.w + 16, height: d.h + 16, background: bg}} />
      {[0, 1, 2, 3].map((i) => {
        const t = clamp(filled - i);
        return (
          <div
            key={i}
            style={{
              position: 'absolute',
              left: d.x + i * (D + gap),
              top: d.y,
              width: D,
              height: D,
              borderRadius: 99,
              boxSizing: 'border-box',
              border: `3px solid ${t > 0 ? color : '#5B6573'}`,
              background: t > 0 ? color : 'transparent',
              transform: `scale(${1 + Math.sin(t * Math.PI) * 0.22})`,
            }}
          />
        );
      })}
    </>
  );
};

/**
 * lock_hold: the app's hold ring (centre 960,506, r 196, 28 px) re-drawn at progress p (0..1) with the remaining
 * seconds in the middle, over the capture's static "4 seconds left" state.
 */
export const HoldRingOverlay: React.FC<{p: number; seconds: number}> = ({p, seconds}) => {
  if (!hasScreen('lock_hold')) return null;
  const cx = 960;
  const cy = 506;
  const R = 196;
  const a = -Math.PI / 2 + clamp(p) * Math.PI * 2 * 0.9999;
  const ex = cx + R * Math.cos(a);
  const ey = cy + R * Math.sin(a);
  const large = clamp(p) > 0.5 ? 1 : 0;
  const arc = `M ${cx} ${cy - R} A ${R} ${R} 0 ${large} 1 ${ex.toFixed(2)} ${ey.toFixed(2)}`;
  // the capture's knob (HoldToExitRing(progress = 0.62f) in LockScreenshotTest) and its halo sit partly outside
  // the band: covered with the local background
  const k0 = -Math.PI / 2 + 0.62 * Math.PI * 2;
  return (
    <>
      <svg width={1920} height={1080} style={{position: 'absolute', left: 0, top: 0, overflow: 'visible'}}>
        <defs>
          <linearGradient id="holdArc" x1="0" y1={cy - R} x2="0" y2={cy + R} gradientUnits="userSpaceOnUse">
            <stop offset="0" stopColor="#2BA097" />
            <stop offset="1" stopColor="#41D5C6" />
          </linearGradient>
          <radialGradient id="holdKnobCover" cx={cx + R * Math.cos(k0)} cy={cy + R * Math.sin(k0)} r={62} gradientUnits="userSpaceOnUse">
            <stop offset="0.72" stopColor="#04131A" />
            <stop offset="1" stopColor="#04131A" stopOpacity={0} />
          </radialGradient>
        </defs>
        <circle cx={cx + R * Math.cos(k0)} cy={cy + R * Math.sin(k0)} r={62} fill="url(#holdKnobCover)" />
        <circle cx={cx} cy={cy} r={R} fill="none" stroke="#213137" strokeWidth={32} />
        {p > 0.002 ? <path d={arc} fill="none" stroke="url(#holdArc)" strokeWidth={28} strokeLinecap="butt" /> : null}
        {p > 0.002 ? (
          <>
            <circle cx={ex} cy={ey} r={37} fill={rgba('#2EC4B6', 0.16)} />
            <circle cx={ex} cy={ey} r={24} fill={rgba('#2EC4B6', 0.32)} />
            <circle cx={ex} cy={ey} r={9} fill="#FFFFFF" />
          </>
        ) : null}
      </svg>
      <div style={{position: 'absolute', left: 860, top: 390, width: 200, height: 170, background: '#05171B'}} />
      <div
        style={{
          position: 'absolute',
          left: cx - 150,
          top: 400,
          width: 300,
          textAlign: 'center',
          fontFamily: brand.font,
          fontWeight: 700,
          fontSize: 176,
          lineHeight: '150px',
          color: '#FFFFFF',
          direction: 'ltr',
        }}
      >
        {Math.max(0, Math.ceil(seconds - 1e-6))}
      </div>
    </>
  );
};

/**
 * lock_phrase: the typed text in the input (starting at the right, cursor on its left, in the Arabic capture; at the
 * left, cursor on its right, in the English one: the app lays the field out left to right there, and the phrase and its
 * keyboard stay Arabic) and the phrase above with its typed prefix in teal, re-drawn as the phrase is typed on the
 * on-screen keyboard.
 */
export const PhraseOverlay: React.FC<{phrase: string; typed: number; blink: boolean}> = ({phrase, typed, blink}) => {
  if (!hasScreen('lock_phrase')) return null;
  const ltr = isEnglishScreen('lock_phrase');
  const input = px('lock_phrase', 'input');
  const done = phrase.slice(0, typed);
  const rest = phrase.slice(typed);
  return (
    <>
      {/* the phrase: typed prefix teal, the rest white (one shaped run: Arabic joining across the colour change) */}
      <div style={{position: 'absolute', left: 700, top: 160, width: 520, height: 100, background: '#1B242F'}} />
      <div style={{position: 'absolute', left: 388, width: 1144, top: 155, height: 100, textAlign: 'center', fontFamily: brand.font, fontWeight: 700, fontSize: 64, lineHeight: '100px', direction: 'rtl', whiteSpace: 'nowrap'}}>
        <span style={{color: '#2EC4B6'}}>{done}</span>
        <span style={{color: brand.text}}>{rest}</span>
      </div>
      {/* the input */}
      {ltr ? (
        <>
          <div style={{position: 'absolute', left: input.x + 20, top: 300, width: 506, height: 84, background: '#151B24'}} />
          <div style={{position: 'absolute', left: input.x + 44, top: 300, height: 84, display: 'flex', alignItems: 'center', gap: 8, direction: 'ltr'}}>
            <span style={{fontFamily: brand.font, fontWeight: 600, fontSize: 48, color: brand.text, whiteSpace: 'pre', lineHeight: '84px', direction: 'rtl', unicodeBidi: 'isolate'}}>{done}</span>
            <span style={{width: 5, height: 59, borderRadius: 2, background: '#2EC4B6', opacity: blink ? 1 : 0.25}} />
          </div>
        </>
      ) : (
        <>
          <div style={{position: 'absolute', left: 1000, top: 300, width: 506, height: 84, background: '#151B24'}} />
          <div style={{position: 'absolute', right: 1920 - 1486, top: 300, height: 84, display: 'flex', alignItems: 'center', gap: 8, direction: 'rtl'}}>
            <span style={{fontFamily: brand.font, fontWeight: 600, fontSize: 48, color: brand.text, whiteSpace: 'pre', lineHeight: '84px'}}>{done}</span>
            <span style={{width: 5, height: 59, borderRadius: 2, background: '#2EC4B6', opacity: blink ? 1 : 0.25}} />
          </div>
        </>
      )}
    </>
  );
};

/** The :remote web page's toast (index.html .toast.ok), phone CSS px. */
export const WebToast: React.FC<{text: string; t: number; bottom?: number}> = ({text, t, bottom = 94}) => (
  <div
    style={{
      position: 'absolute',
      left: 16,
      right: 16,
      bottom,
      display: 'flex',
      alignItems: 'center',
      gap: 10,
      padding: '12px 16px',
      borderRadius: 15,
      background: '#243245',
      border: '1px solid #2E3947',
      boxShadow: '0 16px 40px rgba(0,0,0,.5)',
      color: brand.text,
      fontFamily: brand.font,
      fontSize: 14.5,
      fontWeight: 700,
      lineHeight: 1.5,
      direction: 'rtl',
      opacity: clamp(t * 1.5),
      transform: `translateY(${(1 - t) * 18}px) scale(${mix(0.96, 1, t)})`,
    }}
  >
    <Icon name="check" size={20} color={brand.primary} stroke={2.4} />
    <span style={{flex: 1}}>{text}</span>
  </div>
);

/** phone_parent_pin: the PIN field's placeholder dots replaced by `n` typed bullets (phone CSS px). */
export const PhonePinField: React.FC<{n: number}> = ({n}) => {
  if (!hasScreen('phone_parent_pin')) return null;
  const k = 390 / 780;
  const f = px('phone_parent_pin', 'field');
  return (
    <>
      <div style={{position: 'absolute', left: (f.x + 12) * k, top: (f.y + 12) * k, width: (f.w - 24) * k, height: (f.h - 24) * k, borderRadius: 10, background: '#1E2632'}} />
      <div style={{position: 'absolute', left: f.x * k, top: f.y * k, width: f.w * k, height: f.h * k, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 12}}>
        {[0, 1, 2, 3].map((i) => {
          const t = clamp(n - i);
          return <div key={i} style={{width: 11, height: 11, borderRadius: 99, background: t > 0 ? brand.text : 'rgba(138,151,168,0.55)', transform: `scale(${t > 0 ? 1 + Math.sin(t * Math.PI) * 0.4 : 0.6})`}} />;
        })}
      </div>
    </>
  );
};

/**
 * update_downloading: the bar fill (teal, growing from the right in the Arabic capture, from the left in the English one)
 * and the «x of 18.0 MB» line re-drawn at progress p (0..1), over the capture's static 7.0 / 18.0 state.
 */
export const UpdateProgressOverlay: React.FC<{p: number; totalMb?: number}> = ({p, totalMb = 18}) => {
  if (!hasScreen('update_downloading')) return null;
  const ltr = isEnglishScreen('update_downloading');
  const bar = px('update_downloading', 'bar');
  const k = clamp(p);
  const fillW = Math.max(bar.h, bar.w * k);
  return (
    <>
      <div style={{position: 'absolute', left: bar.x, top: bar.y, width: bar.w, height: bar.h, borderRadius: bar.h / 2, background: '#1E2632'}} />
      {k > 0.001 ? <div style={{position: 'absolute', left: ltr ? bar.x : bar.x + bar.w - fillW, top: bar.y, width: fillW, height: bar.h, borderRadius: bar.h / 2, background: '#2EC4B6'}} /> : null}
      <div style={{position: 'absolute', left: 700, top: 520, width: 520, height: 60, background: '#0B0F14'}} />
      <div
        style={{
          position: 'absolute',
          left: 660,
          width: 600,
          top: 550,
          transform: 'translateY(-50%)',
          textAlign: 'center',
          fontFamily: brand.font,
          fontWeight: 700,
          fontSize: 35,
          lineHeight: 1,
          color: brand.text,
          direction: dir,
          whiteSpace: 'nowrap',
        }}
      >
        {t('{done} من {total} ميغابايت', {done: (totalMb * k).toFixed(1), total: totalMb.toFixed(1)})}
      </div>
    </>
  );
};

/** phone_parent_quotas: Sara's daily quota value re-lettered after a «+» tap (phone CSS px). */
export const PhoneQuotaValue: React.FC<{minutes: number; t: number}> = ({minutes, t: p}) => {
  if (!hasScreen('phone_parent_quotas') || p <= 0) return null;
  const k = 390 / 780;
  const v = px('phone_parent_quotas', 'childValue');
  return (
    <>
      <div style={{position: 'absolute', left: v.x * k, top: v.y * k, width: v.w * k, height: v.h * k, background: '#1E2632', opacity: clamp(p * 3)}} />
      <div
        style={{
          position: 'absolute',
          left: v.x * k,
          top: v.y * k,
          width: v.w * k,
          height: v.h * k,
          display: 'grid',
          placeItems: 'center',
          fontFamily: brand.font,
          fontWeight: 700,
          fontSize: 16,
          color: brand.text,
          direction: 'rtl',
          opacity: clamp(p * 3),
          transform: `scale(${1 + Math.sin(clamp(p) * Math.PI) * 0.12})`,
        }}
      >
        {/* the phone page has no English: its capture stays Arabic, and so does the number drawn over it */}
        {t('{minutes} دقيقة', {minutes})}
      </div>
    </>
  );
};
