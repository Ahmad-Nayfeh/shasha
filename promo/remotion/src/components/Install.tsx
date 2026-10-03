import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand, rgba} from '../brand';
import {clamp, easeInOut, easeOut, mix, sp, tween} from '../lib/anim';
import {TV_IP} from '../lib/demo';
import {digits, dir, flowSign, isEn, t} from '../lib/lang';
import {Icon, IconName} from './Icons';

// Shared parts of the installation section (eight steps): the step header, the side checklist,
// the TV's IP noted on a card, the section card.
// The layout is drawn for the reading direction: in the Arabic version the header, the checklist and the cards sit on
// the right and the devices on the left; the English version mirrors the stage (mirrorX) — the devices' own screens
// (the TV's settings, the desktop, the console) keep the direction of the real thing.

/** Stage x of a box [x, x + w] drawn for the Arabic (right-to-left) layout: mirrored across the 1920 px stage in English. */
export const mirrorX = (x: number, w: number) => (isEn ? 1920 - x - w : x);

export type Where = 'pc' | 'tv' | 'phone';
export const STEPS: Array<{title: string; where: Where}> = [
  {title: t('نزّل مجلد التثبيت'), where: 'pc'},
  {title: t('فعّل خيارات المطوّر'), where: 'tv'},
  {title: t('فعّل تصحيح الأخطاء'), where: 'tv'},
  {title: t('سجّل عنوان التلفاز'), where: 'tv'},
  {title: t('شغّل المثبّت'), where: 'pc'},
  {title: t('اسمح للكمبيوتر'), where: 'tv'},
  {title: t('التثبيت التلقائي'), where: 'pc'},
  {title: t('أول تشغيل لـ«شاشة»'), where: 'tv'},
];
const WHERE: Record<Where, {label: string; icon: IconName}> = {
  pc: {label: t('على الكمبيوتر'), icon: 'terminal'},
  tv: {label: t('على التلفاز'), icon: 'tv'},
  phone: {label: t('على الجوال'), icon: 'phone'},
};
const TV_IP_LABEL = t('عنوان IP للتلفاز');
const STEP_BY_STEP = t('خطوة بخطوة');

/** Top header (right in Arabic, left in English): «التثبيت · الخطوة ٣ من ٩», the step title, where it happens, one progress dot per step. */
export const StepHeader: React.FC<{step: number; from?: number; sub?: string}> = ({step, from = 0, sub}) => {
  const f = useCurrentFrame();
  const a = tween(f, from, 16);
  const b = tween(f, from + 4, 18);
  const s = STEPS[step - 1];
  const w = WHERE[s.where];
  return (
    <div style={{position: 'absolute', top: 50, ...(isEn ? {left: 90} : {right: 90}), direction: dir, fontFamily: brand.font, color: brand.text, textAlign: isEn ? 'left' : 'right'}}>
      <div style={{display: 'flex', alignItems: 'center', gap: 16, opacity: a, transform: `translateX(${flowSign * (1 - a) * -20}px)`}}>
        <span style={{fontSize: 26, fontWeight: 600, color: brand.primary}}>{t('التثبيت · الخطوة {n} من {total}', {n: digits(step), total: digits(STEPS.length)})}</span>
        <div style={{display: 'flex', gap: 7}}>
          {STEPS.map((_, i) => (
            <div
              key={i}
              style={{
                width: i === step - 1 ? 34 : 12,
                height: 12,
                borderRadius: 99,
                background: i === step - 1 ? brand.primary : i < step - 1 ? rgba(brand.primary, 0.45) : 'rgba(234,240,246,0.16)',
              }}
            />
          ))}
        </div>
      </div>
      <div style={{display: 'flex', alignItems: 'center', gap: 18, marginTop: 6, opacity: b, transform: `translateY(${(1 - b) * 14}px)`}}>
        <span style={{fontSize: 56, fontWeight: 700, lineHeight: 1.2}}>{sub ?? s.title}</span>
        <span
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 8,
            padding: '6px 16px 8px',
            borderRadius: 99,
            border: `1.5px solid ${rgba(brand.primary, 0.45)}`,
            background: rgba(brand.primary, 0.1),
            fontSize: 24,
            fontWeight: 600,
            color: brand.primaryBright,
          }}
        >
          <Icon name={w.icon} size={26} color={brand.primaryBright} stroke={2.2} />
          {w.label}
        </span>
      </div>
    </div>
  );
};

/** Left edge of the checklist column (right of the devices in Arabic, left of them in English). */
export const COL_X = mirrorX(1330, 500);

export type CheckItem = {at: number; label: React.ReactNode; sub?: React.ReactNode; icon?: IconName; done?: number; tone?: 'teal' | 'amber'};
/** Column list (beside the device) of numbered actions that light up on their beat and get a check when done. */
export const SideSteps: React.FC<{items: CheckItem[]; x?: number; y?: number; w?: number; gap?: number; out?: number; size?: number}> = ({
  items,
  x = COL_X,
  y = 280,
  w = 500,
  gap = 16,
  out,
  size = 28,
}) => {
  const f = useCurrentFrame();
  const gone = out !== undefined ? tween(f, out, 12) : 0;
  let active = -1;
  items.forEach((it, i) => {
    if (f >= it.at) active = i;
  });
  return (
    <div style={{position: 'absolute', left: x, top: y, width: w, display: 'flex', flexDirection: 'column', gap, direction: dir, fontFamily: brand.font, opacity: 1 - gone}}>
      {items.map((it, i) => {
        const p = sp(f, it.at - 6, {damping: 22});
        const on = i === active;
        const done = it.done !== undefined ? f >= it.done : i < active;
        const c = it.tone === 'amber' ? brand.amber : brand.primary;
        return (
          <div
            key={i}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 16,
              padding: '14px 18px',
              borderRadius: 22,
              background: on ? 'linear-gradient(180deg, #17252B, #121A21)' : 'linear-gradient(180deg, #161D26, #121820)',
              border: `2px solid ${on ? rgba(c, 0.85) : 'rgba(234,240,246,0.07)'}`,
              boxShadow: on ? `0 0 30px ${rgba(c, 0.25)}` : '0 14px 30px rgba(0,0,0,0.3)',
              opacity: clamp(p * 1.4) * (on || done ? 1 : 0.55),
              transform: `translateX(${flowSign * (1 - p) * -30}px) scale(${on ? 1.02 : 1})`,
              transformOrigin: isEn ? 'left center' : 'right center',
            }}
          >
            <div
              style={{
                width: 48,
                height: 48,
                borderRadius: 99,
                flex: 'none',
                display: 'grid',
                placeItems: 'center',
                background: done ? c : on ? rgba(c, 0.18) : 'rgba(234,240,246,0.08)',
                color: done ? brand.onPrimary : brand.text,
                fontSize: 24,
                fontWeight: 700,
              }}
            >
              {done ? <Icon name="check" size={28} color={brand.onPrimary} stroke={3} /> : it.icon ? <Icon name={it.icon} size={26} color={on ? c : brand.muted} stroke={2.2} /> : digits(i + 1)}
            </div>
            <div style={{flex: 1, minWidth: 0}}>
              <div style={{fontSize: size, fontWeight: 600, lineHeight: 1.3, color: brand.text}}>{it.label}</div>
              {it.sub ? <div style={{fontSize: size * 0.7, color: brand.muted, marginTop: 2, direction: 'ltr', textAlign: isEn ? 'left' : 'right', fontFamily: brand.mono}}>{it.sub}</div> : null}
            </div>
          </div>
        );
      })}
    </div>
  );
};

/**
 * Where the TV's IP card is parked: the top-left corner in Arabic, the top-right one in English (the header is on the
 * other side). x is the card's left edge in Arabic and its right edge in English (it is drawn from that edge).
 */
export const IP_NOTE_HOME = {x: isEn ? 1920 - 90 : 90, y: 56};

/** The TV's IP noted on a card, parked in a top corner (step 4 → step 5). */
export const IpNote: React.FC<{x?: number; y?: number; t?: number; glow?: number}> = ({x = IP_NOTE_HOME.x, y = IP_NOTE_HOME.y, t = 1, glow = 0}) => (
  <div
    style={{
      position: 'absolute',
      ...(isEn ? {right: 1920 - x} : {left: x}),
      top: y,
      opacity: clamp(t * 1.5),
      transform: `rotate(${flowSign * mix(-8, -2.5, t)}deg) scale(${mix(0.8, 1, t)})`,
      transformOrigin: isEn ? 'right top' : 'left top',
      direction: dir,
      fontFamily: brand.font,
    }}
  >
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 16,
        padding: isEn ? '14px 20px 14px 24px' : '14px 24px 14px 20px',
        borderRadius: 18,
        background: 'linear-gradient(180deg, #1D2530, #151B24)',
        ...(isEn ? {borderLeft: `6px solid ${brand.amber}`} : {borderRight: `6px solid ${brand.amber}`}),
        boxShadow: `0 18px 40px rgba(0,0,0,0.45)${glow ? `, 0 0 ${30 * glow}px ${rgba(brand.amber, 0.4 * glow)}` : ''}`,
      }}
    >
      <Icon name="tv" size={34} color={brand.amber} stroke={2.2} />
      <div>
        <div style={{fontSize: 20, color: brand.muted, fontWeight: 600}}>{TV_IP_LABEL}</div>
        <div style={{fontSize: 34, fontWeight: 600, color: brand.text, fontFamily: brand.mono, direction: 'ltr', textAlign: isEn ? 'left' : 'right'}}>{TV_IP}</div>
      </div>
    </div>
  </div>
);

/** Section title card (s11 lead-in). */
export const SectionCard: React.FC<{title: string; sub: string; t: number; out: number}> = ({title, sub, t, out}) => (
  <div
    style={{
      position: 'absolute',
      inset: 0,
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'center',
      justifyContent: 'center',
      opacity: t * (1 - out),
      transform: `scale(${mix(0.97, 1, t) * mix(1, 1.04, out)})`,
      direction: dir,
      fontFamily: brand.font,
      color: brand.text,
    }}
  >
    <div style={{fontSize: 32, fontWeight: 600, color: brand.primary, marginBottom: 12}}>{STEP_BY_STEP}</div>
    <div style={{fontSize: 130, fontWeight: 700, lineHeight: 1.1}}>{title}</div>
    <div style={{fontSize: 40, fontWeight: 500, color: brand.muted, marginTop: 18}}>{sub}</div>
  </div>
);

/**
 * A small ✓/✕ caption chip (stage space). In the Arabic version `en` is the device's own English label shown under
 * the Arabic text; the English version draws the text alone (it is that label, or the narrator's words).
 */
export const NoteChip: React.FC<{x: number; y: number; t: number; icon?: IconName; tone?: 'teal' | 'amber'; children: React.ReactNode; size?: number; en?: string}> = ({
  x,
  y,
  t,
  icon = 'check',
  tone = 'teal',
  children,
  size = 26,
  en,
}) => {
  const c = tone === 'amber' ? brand.amber : brand.primary;
  return (
    <div
      style={{
        position: 'absolute',
        left: x,
        top: y,
        display: 'inline-flex',
        alignItems: 'center',
        gap: 12,
        padding: isEn ? '10px 16px 12px 22px' : '10px 22px 12px 16px',
        borderRadius: 99,
        background: 'linear-gradient(180deg, rgba(26,34,45,0.97), rgba(19,25,32,0.97))',
        border: `1.5px solid ${rgba(c, 0.55)}`,
        boxShadow: '0 16px 40px rgba(0,0,0,0.4)',
        direction: dir,
        fontFamily: brand.font,
        fontSize: size,
        fontWeight: 600,
        color: brand.text,
        opacity: clamp(t * 1.4),
        transform: `translateY(${(1 - clamp(t)) * 12}px) scale(${mix(0.95, 1, clamp(t))})`,
        whiteSpace: 'nowrap',
        zIndex: 30,
      }}
    >
      <Icon name={icon} size={size * 1.05} color={c} stroke={2.4} />
      {en && !isEn ? (
        <div style={{display: 'flex', flexDirection: 'column', lineHeight: 1.25}}>
          <span>{children}</span>
          <span style={{fontSize: size * 0.72, fontWeight: 500, color: brand.muted, direction: 'ltr', textAlign: 'right', unicodeBidi: 'isolate'}}>{en}</span>
        </div>
      ) : (
        children
      )}
    </div>
  );
};

export const INSTALL_TV = {x: mirrorX(96, 1200), y: 214, w: 1200};
export const INSTALL_LAPTOP = {x: mirrorX(96, 1130), y: 206, w: 1130};
export {easeInOut, easeOut};
