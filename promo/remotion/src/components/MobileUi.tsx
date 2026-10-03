import React from 'react';
import {brand, rgba} from '../brand';
import {LogoMark} from './Brand';
import {Icon, IconName} from './Icons';

// Building blocks of the phone app's mock screens (the phone video draws the app itself: Remotion components in the
// app's own palette and Arabic labels from mobile/src/main/res/values-ar/strings.xml, not screenshots). Everything
// is laid out in phone space: 390 x 844 CSS px, right-to-left, IBM Plex Sans Arabic.

export const MW = 390;
export const MH = 844;
export const C = {
  bg: '#0B0F14',
  low: '#10151C',
  surface: '#151B24',
  high: '#1E2632',
  higher: '#28323F',
  outline: '#2E3947',
  text: '#EAF0F6',
  muted: '#A2AEBD',
  faint: '#627286',
  primary: '#2EC4B6',
  primaryBright: '#48DCCD',
  onPrimary: '#04201D',
  amber: '#FFB547',
  error: '#FF6B6B',
};

/** The full-screen root of an app screen. */
export const Screen390: React.FC<{children?: React.ReactNode; bg?: string; style?: React.CSSProperties}> = ({children, bg = C.bg, style}) => (
  <div style={{position: 'absolute', left: 0, top: 0, width: MW, height: MH, background: bg, overflow: 'hidden', isolation: 'isolate', direction: 'rtl', fontFamily: brand.font, color: C.text, ...style}}>
    {children}
  </div>
);

export const StatusBar: React.FC<{dark?: boolean; time?: string}> = ({dark = true, time = '٩:٤١'}) => {
  const c = dark ? '#EAF0F6' : '#14202B';
  return (
    <div style={{position: 'absolute', left: 0, top: 0, width: MW, height: 34, display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0 26px', direction: 'ltr', fontSize: 15, fontWeight: 600, color: c, zIndex: 20}}>
      <span>{time}</span>
      <span style={{display: 'flex', gap: 7, alignItems: 'center'}}>
        <svg width={18} height={14} viewBox="0 0 18 14"><path d="M1 11l8 -9 8 9" stroke={c} strokeWidth={2} fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>
        <svg width={22} height={12} viewBox="0 0 22 12"><rect x={1} y={1} width={17} height={10} rx={3} stroke={c} strokeWidth={1.6} fill="none" /><rect x={3} y={3} width={11} height={6} rx={1.5} fill={c} /><rect x={19} y={4} width={2} height={4} rx={1} fill={c} /></svg>
      </span>
    </div>
  );
};

/** Thumbnail placeholder: a soft coloured orb on a dark ground (like the TV captures' fictional thumbnails). */
export const thumbBg = (i: number) => {
  const h = [212, 22, 330, 150, 262, 38, 190, 4][i % 8];
  return `radial-gradient(70% 80% at ${28 + (i % 3) * 18}% 32%, hsl(${h} 46% 34%), hsl(${h + 24} 52% 11%) 85%)`;
};

export const Thumb: React.FC<{i: number; w: number; h: number; dur?: string; live?: boolean; progress?: number; radius?: number; style?: React.CSSProperties; children?: React.ReactNode}> = ({
  i,
  w,
  h,
  dur,
  live,
  progress,
  radius = 12,
  style,
  children,
}) => (
  <div style={{position: 'relative', width: w, height: h, borderRadius: radius, background: thumbBg(i), overflow: 'hidden', flex: 'none', ...style}}>
    {children}
    {dur ? (
      <div style={{position: 'absolute', left: 6, bottom: 6, padding: '1px 6px', borderRadius: 6, background: 'rgba(0,0,0,0.72)', fontSize: Math.max(11, h * 0.09), fontWeight: 600, direction: 'ltr'}}>{dur}</div>
    ) : null}
    {live ? (
      <div style={{position: 'absolute', right: 6, bottom: 6, padding: '1px 8px', borderRadius: 6, background: '#FF5C5C', fontSize: 11, fontWeight: 700, color: '#fff'}}>مباشر</div>
    ) : null}
    {progress !== undefined ? <div style={{position: 'absolute', right: 0, bottom: 0, height: 3, width: `${progress * 100}%`, background: '#FF5C5C'}} /> : null}
  </div>
);

export const Avatar: React.FC<{letter: string; hue?: number; size?: number}> = ({letter, hue = 200, size = 36}) => (
  <div style={{width: size, height: size, borderRadius: 999, flex: 'none', display: 'grid', placeItems: 'center', fontSize: size * 0.45, fontWeight: 700, color: C.text, background: `radial-gradient(70% 70% at 35% 30%, hsl(${hue} 42% 38%), hsl(${hue + 20} 46% 14%))`, boxShadow: 'inset 0 0 0 1.5px rgba(255,255,255,0.08)'}}>
    {letter}
  </div>
);

export const SAMPLE = [
  {title: 'رحلة إلى أعماق المحيط: كائنات لم ترها من قبل', channel: 'عالم الطبيعة', meta: '٢٫١ مليون مشاهدة · منذ ساعتين', dur: '12:34', hue: 212, letter: 'ع'},
  {title: 'أسرار الخط العربي: الثلث والديواني وكيف تبدأ', channel: 'فنون الخط', meta: '٨٥ ألف مشاهدة · منذ ٣ أيام', dur: '14:20', hue: 330, letter: 'ف'},
  {title: 'وصفة الكنافة النابلسية في البيت خطوة بخطوة', channel: 'مطبخ سلمى', meta: '٣٤٠ ألف مشاهدة · منذ يوم', dur: '9:47', hue: 22, letter: 'م'},
  {title: 'كيف تعمل الصواريخ؟ دليل مصوّر', channel: 'مختبر الفضاء', meta: '١٫٢ مليون مشاهدة · منذ ٥ ساعات', dur: '18:02', hue: 262, letter: 'ف'},
  {title: 'قنديل البحر الذي لا يموت', channel: 'عالم البحار', meta: '٩٢٠ ألف مشاهدة · منذ يومين', dur: '8:15', hue: 190, letter: 'ب'},
  {title: 'حقائق مذهلة عن الثقوب السوداء', channel: 'مختبر الفضاء', meta: '١٫٨ مليون مشاهدة · منذ أسبوع', dur: '22:20', hue: 38, letter: 'ف'},
];

export const NavItem: React.FC<{icon: IconName; label: string; on?: boolean}> = ({icon, label, on}) => (
  <div style={{display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4, width: 90}}>
    <div style={{width: 62, height: 32, borderRadius: 16, display: 'grid', placeItems: 'center', background: on ? rgba(C.primary, 0.2) : 'transparent'}}>
      <Icon name={icon} size={24} color={on ? C.primaryBright : C.muted} stroke={on ? 2.4 : 2} />
    </div>
    <div style={{fontSize: 12.5, fontWeight: on ? 700 : 500, color: on ? C.text : C.muted}}>{label}</div>
  </div>
);

export const BottomNav: React.FC<{active: 'home' | 'search' | 'library' | 'settings'}> = ({active}) => (
  <div style={{position: 'absolute', left: 0, bottom: 0, width: MW, height: 76, background: C.surface, borderTop: `1px solid ${C.outline}`, display: 'flex', justifyContent: 'space-around', alignItems: 'center', paddingTop: 2, zIndex: 10}}>
    <NavItem icon="home" label="الرئيسية" on={active === 'home'} />
    <NavItem icon="search" label="بحث" on={active === 'search'} />
    <NavItem icon="layers" label="مكتبتي" on={active === 'library'} />
    <NavItem icon="sliders" label="الإعدادات" on={active === 'settings'} />
  </div>
);

export const TopBar: React.FC<{title: React.ReactNode; back?: boolean; actions?: IconName[]; logo?: boolean}> = ({title, back, actions = [], logo}) => (
  <div style={{position: 'absolute', left: 0, top: 34, width: MW, height: 56, display: 'flex', alignItems: 'center', padding: '0 14px', gap: 10, zIndex: 8}}>
    {back ? <Icon name="arrowRight" size={26} color={C.text} stroke={2.2} /> : null}
    {logo ? <LogoMark size={44} /> : null}
    <div style={{flex: 1, fontSize: 22, fontWeight: 700}}>{title}</div>
    {actions.map((a) => (
      <Icon key={a} name={a} size={24} color={C.text} stroke={2.1} />
    ))}
  </div>
);

export const Chip390: React.FC<{label: string; on?: boolean; icon?: IconName}> = ({label, on, icon}) => (
  <div style={{display: 'inline-flex', alignItems: 'center', gap: 6, height: 34, padding: '0 14px', borderRadius: 10, fontSize: 14.5, fontWeight: 600, whiteSpace: 'nowrap', background: on ? rgba(C.primary, 0.2) : C.higher, border: `1.5px solid ${on ? rgba(C.primary, 0.65) : 'transparent'}`, color: on ? C.primaryBright : C.text}}>
    {icon ? <Icon name={icon} size={17} color={on ? C.primaryBright : C.text} stroke={2.3} /> : null}
    {label}
  </div>
);

export const Toggle390: React.FC<{p: number}> = ({p}) => (
  <div style={{position: 'relative', width: 52, height: 30, borderRadius: 15, flex: 'none', background: `rgba(46,196,182,${0.15 + 0.85 * p})`, boxShadow: `inset 0 0 0 2px rgba(${p > 0.5 ? '46,196,182' : '98,114,134'},1)`}}>
    <div style={{position: 'absolute', top: 5, left: 27 - 22 * p, width: 20, height: 20, borderRadius: 999, background: p > 0.5 ? C.onPrimary : C.faint}} />
  </div>
);

export const Row390: React.FC<{icon?: IconName; title: string; sub?: string; trailing?: React.ReactNode; hi?: number}> = ({icon, title, sub, trailing, hi = 0}) => (
  <div style={{display: 'flex', alignItems: 'center', gap: 16, padding: '12px 18px', minHeight: 60, background: hi ? rgba(C.primary, 0.12 * hi) : 'transparent'}}>
    {icon ? <Icon name={icon} size={26} color={C.primaryBright} stroke={2.1} /> : null}
    <div style={{flex: 1}}>
      <div style={{fontSize: 17, fontWeight: 600}}>{title}</div>
      {sub ? <div style={{fontSize: 13.5, color: C.muted, marginTop: 2, lineHeight: 1.4}}>{sub}</div> : null}
    </div>
    {trailing}
  </div>
);

/** A video row: thumbnail + title + channel · meta. */
export const VideoRow: React.FC<{i: number; w?: number; progress?: number; trailing?: React.ReactNode; opacity?: number; shift?: number; hi?: number}> = ({i, w = 134, progress, trailing, opacity = 1, shift = 0, hi = 0}) => {
  const v = SAMPLE[i % SAMPLE.length];
  return (
    <div style={{display: 'flex', gap: 12, padding: '8px 14px', opacity, transform: `translateY(${shift}px)`, background: hi ? rgba(C.primary, 0.1 * hi) : 'transparent'}}>
      <Thumb i={i} w={w} h={w * 0.5625} dur={v.dur} progress={progress} radius={10} />
      <div style={{flex: 1, minWidth: 0}}>
        <div style={{fontSize: 14.5, fontWeight: 600, lineHeight: 1.4, maxHeight: 41, overflow: 'hidden'}}>{v.title}</div>
        <div style={{fontSize: 12.5, color: C.muted, marginTop: 4}}>{v.channel}</div>
        <div style={{fontSize: 12, color: C.faint, marginTop: 1}}>{v.meta}</div>
      </div>
      {trailing}
    </div>
  );
};

/** A big feed card: full-width thumbnail, avatar, title, channel · meta. */
export const VideoCard: React.FC<{i: number; opacity?: number; shift?: number}> = ({i, opacity = 1, shift = 0}) => {
  const v = SAMPLE[i % SAMPLE.length];
  return (
    <div style={{marginBottom: 14, opacity, transform: `translateY(${shift}px)`}}>
      <Thumb i={i} w={MW} h={MW * 0.5625} dur={v.dur} radius={0} />
      <div style={{display: 'flex', gap: 12, padding: '10px 14px 0'}}>
        <Avatar letter={v.letter} hue={v.hue} size={38} />
        <div style={{flex: 1}}>
          <div style={{fontSize: 15.5, fontWeight: 600, lineHeight: 1.4}}>{v.title}</div>
          <div style={{fontSize: 12.5, color: C.muted, marginTop: 3}}>
            {v.channel} · {v.meta}
          </div>
        </div>
      </div>
    </div>
  );
};

export const Btn390: React.FC<{label: string; icon?: IconName; tone?: 'primary' | 'tonal' | 'text'; w?: number; glow?: number}> = ({label, icon, tone = 'primary', w, glow = 0}) => (
  <div style={{display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 8, height: 46, width: w, padding: '0 22px', borderRadius: 23, fontSize: 16, fontWeight: 700, whiteSpace: 'nowrap', background: tone === 'primary' ? C.primary : tone === 'tonal' ? C.higher : 'transparent', color: tone === 'primary' ? C.onPrimary : tone === 'tonal' ? C.text : C.primaryBright, boxShadow: glow ? `0 0 ${26 * glow}px ${rgba(C.primary, 0.6 * glow)}` : undefined}}>
      {icon ? <Icon name={icon} size={20} color={tone === 'primary' ? C.onPrimary : C.text} stroke={2.4} /> : null}
      {label}
    </div>
);
