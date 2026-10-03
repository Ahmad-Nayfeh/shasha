import React from 'react';
import {useCurrentFrame} from 'remotion';
import {arDigits, brand, rgba} from '../brand';
import {clamp, easeInOut, easeOut, mix, sp, tween} from '../lib/anim';
import {TV_IP} from '../lib/demo';
import {Icon, IconName} from './Icons';

// Shared parts of the installation section (eight steps): the step header, the side checklist,
// the TV's IP noted on a card, the section card.

export type Where = 'pc' | 'tv' | 'phone';
export const STEPS: Array<{title: string; where: Where}> = [
  {title: 'نزّل مجلد التثبيت', where: 'pc'},
  {title: 'فعّل خيارات المطوّر', where: 'tv'},
  {title: 'فعّل تصحيح الأخطاء', where: 'tv'},
  {title: 'سجّل عنوان التلفاز', where: 'tv'},
  {title: 'شغّل المثبّت', where: 'pc'},
  {title: 'اسمح للكمبيوتر', where: 'tv'},
  {title: 'التثبيت التلقائي', where: 'pc'},
  {title: 'أول تشغيل لـ«شاشة»', where: 'tv'},
];
const WHERE: Record<Where, {label: string; icon: IconName}> = {
  pc: {label: 'على الكمبيوتر', icon: 'terminal'},
  tv: {label: 'على التلفاز', icon: 'tv'},
  phone: {label: 'على الجوال', icon: 'phone'},
};

/** Top-right header: «التثبيت · الخطوة ٣ من ٩», the step title, where it happens, one progress dot per step. */
export const StepHeader: React.FC<{step: number; from?: number; sub?: string}> = ({step, from = 0, sub}) => {
  const f = useCurrentFrame();
  const a = tween(f, from, 16);
  const b = tween(f, from + 4, 18);
  const s = STEPS[step - 1];
  const w = WHERE[s.where];
  return (
    <div style={{position: 'absolute', top: 50, right: 90, direction: 'rtl', fontFamily: brand.font, color: brand.text, textAlign: 'right'}}>
      <div style={{display: 'flex', alignItems: 'center', gap: 16, opacity: a, transform: `translateX(${(1 - a) * -20}px)`}}>
        <span style={{fontSize: 26, fontWeight: 600, color: brand.primary}}>
          التثبيت · الخطوة {arDigits(step)} من {arDigits(STEPS.length)}
        </span>
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

export type CheckItem = {at: number; label: React.ReactNode; sub?: React.ReactNode; icon?: IconName; done?: number; tone?: 'teal' | 'amber'};
/** Right-column list of numbered actions that light up on their beat and get a check when done. */
export const SideSteps: React.FC<{items: CheckItem[]; x?: number; y?: number; w?: number; gap?: number; out?: number; size?: number}> = ({
  items,
  x = 1330,
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
    <div style={{position: 'absolute', left: x, top: y, width: w, display: 'flex', flexDirection: 'column', gap, direction: 'rtl', fontFamily: brand.font, opacity: 1 - gone}}>
      {items.map((it, i) => {
        const t = sp(f, it.at - 6, {damping: 22});
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
              opacity: clamp(t * 1.4) * (on || done ? 1 : 0.55),
              transform: `translateX(${(1 - t) * -30}px) scale(${on ? 1.02 : 1})`,
              transformOrigin: 'right center',
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
              {done ? <Icon name="check" size={28} color={brand.onPrimary} stroke={3} /> : it.icon ? <Icon name={it.icon} size={26} color={on ? c : brand.muted} stroke={2.2} /> : arDigits(i + 1)}
            </div>
            <div style={{flex: 1, minWidth: 0}}>
              <div style={{fontSize: size, fontWeight: 600, lineHeight: 1.3, color: brand.text}}>{it.label}</div>
              {it.sub ? <div style={{fontSize: size * 0.7, color: brand.muted, marginTop: 2, direction: 'ltr', textAlign: 'right', fontFamily: brand.mono}}>{it.sub}</div> : null}
            </div>
          </div>
        );
      })}
    </div>
  );
};

/** The TV's IP noted on a card, parked in the top-left corner (step 4 → step 5). */
export const IpNote: React.FC<{x?: number; y?: number; t?: number; glow?: number}> = ({x = 90, y = 56, t = 1, glow = 0}) => (
  <div
    style={{
      position: 'absolute',
      left: x,
      top: y,
      opacity: clamp(t * 1.5),
      transform: `rotate(${mix(-8, -2.5, t)}deg) scale(${mix(0.8, 1, t)})`,
      transformOrigin: 'left top',
      direction: 'rtl',
      fontFamily: brand.font,
    }}
  >
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 16,
        padding: '14px 24px 14px 20px',
        borderRadius: 18,
        background: 'linear-gradient(180deg, #1D2530, #151B24)',
        borderRight: `6px solid ${brand.amber}`,
        boxShadow: `0 18px 40px rgba(0,0,0,0.45)${glow ? `, 0 0 ${30 * glow}px ${rgba(brand.amber, 0.4 * glow)}` : ''}`,
      }}
    >
      <Icon name="tv" size={34} color={brand.amber} stroke={2.2} />
      <div>
        <div style={{fontSize: 20, color: brand.muted, fontWeight: 600}}>عنوان IP للتلفاز</div>
        <div style={{fontSize: 34, fontWeight: 600, color: brand.text, fontFamily: brand.mono, direction: 'ltr', textAlign: 'right'}}>{TV_IP}</div>
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
      direction: 'rtl',
      fontFamily: brand.font,
      color: brand.text,
    }}
  >
    <div style={{fontSize: 32, fontWeight: 600, color: brand.primary, marginBottom: 12}}>خطوة بخطوة</div>
    <div style={{fontSize: 130, fontWeight: 700, lineHeight: 1.1}}>{title}</div>
    <div style={{fontSize: 40, fontWeight: 500, color: brand.muted, marginTop: 18}}>{sub}</div>
  </div>
);

/** A small ✓/✕ caption chip (stage space). */
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
        padding: '10px 22px 12px 16px',
        borderRadius: 99,
        background: 'linear-gradient(180deg, rgba(26,34,45,0.97), rgba(19,25,32,0.97))',
        border: `1.5px solid ${rgba(c, 0.55)}`,
        boxShadow: '0 16px 40px rgba(0,0,0,0.4)',
        direction: 'rtl',
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
      {en ? (
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

export const INSTALL_TV = {x: 96, y: 214, w: 1200};
export const INSTALL_LAPTOP = {x: 96, y: 206, w: 1130};
export {easeInOut, easeOut};
