import React from 'react';
import {brand, rgba} from '../brand';
import {t} from '../lib/lang';
import {focusRect, Rect, screenInfo} from '../lib/screens';
import {LogoMark, Wordmark} from './Brand';
import {Icon, IconName} from './Icons';
import {QrCode} from './QrCode';
import {APK_NAME, APK_SIZE, PAIR_PORT, RELEASE_TAG, TV_IP} from '../lib/demo';

// Labelled placeholder for a missing app capture: a stylised skeleton of the screen (so focus rings and
// zooms from screens.json land on something meaningful) plus a discreet tag with the Arabic name + file.

const W = 1920;
const H = 1080;
const R = (name: string, key: string, i = 0) => {
  const r = focusRect(name, key, i);
  return {x: r[0] * W, y: r[1] * H, w: r[2] * W, h: r[3] * H};
};
const RP = (name: string, key: string, i = 0) => {
  const r = focusRect(name, key, i) as Rect;
  return {x: r[0] * 390, y: r[1] * 844, w: r[2] * 390, h: r[3] * 844};
};

const abs = (s: React.CSSProperties): React.CSSProperties => ({position: 'absolute', ...s});

const Bar: React.FC<{x: number; y: number; w: number; h: number; r?: number; c?: string; style?: React.CSSProperties}> = ({
  x,
  y,
  w,
  h,
  r = 12,
  c = '#1C2530',
  style,
}) => <div style={abs({left: x, top: y, width: w, height: h, borderRadius: r, background: c, ...style})} />;

/** Right-anchored Arabic text. xr = right edge. */
const T: React.FC<{xr: number; y: number; size: number; w?: number; c?: string; weight?: number; children: React.ReactNode; align?: 'right' | 'center' | 'left'; x?: number}> = ({
  xr,
  y,
  size,
  c = brand.text,
  weight = 600,
  children,
  align = 'right',
  w,
}) => (
  <div
    style={abs({
      right: W - xr,
      top: y,
      width: w,
      fontSize: size,
      fontWeight: weight,
      color: c,
      direction: 'rtl',
      fontFamily: brand.font,
      textAlign: align,
      whiteSpace: 'nowrap',
      lineHeight: 1.3,
    })}
  >
    {children}
  </div>
);

const THUMBS: Array<[string, string]> = [
  ['#1E5A63', '#0C2229'],
  ['#3C3163', '#15112A'],
  ['#6B4721', '#261708'],
  ['#1F3F66', '#0B1829'],
  ['#2F5433', '#101E11'],
  ['#5E2748', '#220B19'],
  ['#28515E', '#0D1D23'],
  ['#4A4A22', '#1A1A0A'],
];

const Thumb: React.FC<{x: number; y: number; w: number; h: number; i: number; r?: number; dur?: string}> = ({x, y, w, h, i, r = 16, dur}) => {
  const [a, b] = THUMBS[i % THUMBS.length];
  return (
    <div
      style={abs({
        left: x,
        top: y,
        width: w,
        height: h,
        borderRadius: r,
        overflow: 'hidden',
        background: `radial-gradient(60% 80% at ${30 + ((i * 23) % 40)}% ${35 + ((i * 17) % 30)}%, ${a}, ${b} 75%)`,
        boxShadow: 'inset 0 0 0 1px rgba(255,255,255,0.05)',
      })}
    >
      <div
        style={abs({
          left: w * (0.55 + ((i * 7) % 20) / 100),
          top: h * 0.18,
          width: w * 0.28,
          height: w * 0.28,
          borderRadius: 999,
          background: 'radial-gradient(closest-side, rgba(255,255,255,0.10), transparent)',
        })}
      />
      {dur ? (
        <div
          style={abs({
            left: 12,
            bottom: 12,
            padding: '2px 10px',
            borderRadius: 8,
            background: 'rgba(0,0,0,0.6)',
            color: '#EAF0F6',
            fontSize: 22,
            fontWeight: 600,
            fontFamily: brand.font,
          })}
        >
          {dur}
        </div>
      ) : null}
    </div>
  );
};

const Btn: React.FC<{x: number; y: number; w: number; h: number; label: string; primary?: boolean; icon?: IconName; c?: string; size?: number}> = ({
  x,
  y,
  w,
  h,
  label,
  primary,
  icon,
  c = brand.primary,
  size = 34,
}) => (
  <div
    style={abs({
      left: x,
      top: y,
      width: w,
      height: h,
      borderRadius: 999,
      background: primary ? c : '#1E2632',
      color: primary ? brand.onPrimary : brand.text,
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      gap: 14,
      fontSize: size,
      fontWeight: 600,
      fontFamily: brand.font,
      direction: 'rtl',
      boxShadow: primary ? `0 10px 30px ${rgba(c, 0.3)}` : 'inset 0 0 0 1.5px rgba(234,240,246,0.08)',
    })}
  >
    {icon ? <Icon name={icon} size={size * 1.1} color={primary ? brand.onPrimary : c} stroke={2.2} /> : null}
    <span>{label}</span>
  </div>
);

const Dots: React.FC<{x: number; y: number; w: number; h: number; filled: number; n?: number; c?: string}> = ({x, y, w, h, filled, n = 4, c = brand.primary}) => {
  const d = Math.min(h * 0.5, 40);
  return (
    <div style={abs({left: x, top: y, width: w, height: h, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: d * 0.9, direction: 'ltr'})}>
      {Array.from({length: n}).map((_, i) => (
        <div
          key={i}
          style={{
            width: d,
            height: d,
            borderRadius: 999,
            background: i < filled ? c : 'transparent',
            border: `3px solid ${i < filled ? c : 'rgba(234,240,246,0.35)'}`,
          }}
        />
      ))}
    </div>
  );
};

const Keypad: React.FC<{x: number; y: number; w: number; h: number}> = ({x, y, w, h}) => {
  const keys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '⌫', '0', '✓'];
  const cw = w / 3;
  const ch = h / 4;
  return (
    <div style={abs({left: x, top: y, width: w, height: h, direction: 'ltr'})}>
      {keys.map((k, i) => (
        <div
          key={k}
          style={abs({
            left: (i % 3) * cw + 8,
            top: Math.floor(i / 3) * ch + 8,
            width: cw - 16,
            height: ch - 16,
            borderRadius: 22,
            background: '#1A222C',
            color: k === '✓' ? brand.primary : brand.text,
            display: 'grid',
            placeItems: 'center',
            fontSize: 44,
            fontWeight: 600,
            fontFamily: brand.font,
          })}
        >
          {k}
        </div>
      ))}
    </div>
  );
};

const TopBar: React.FC<{kids?: boolean; search?: {x: number; y: number; w: number; h: number} | null; active?: number}> = ({kids, search, active = 0}) => {
  const tabs = kids ? [t('قنواتي'), t('قوائم التشغيل')] : [t('الرئيسية'), t('الاشتراكات'), t('السجل'), t('لاحقاً')];
  return (
    <>
      <div style={abs({right: 90, top: 50, display: 'flex', alignItems: 'center', gap: 18, direction: 'rtl'})}>
        <div style={{width: 64, height: 64, position: 'relative'}}>
          <LogoMark size={82} style={{position: 'absolute', left: -11, top: -12}} />
        </div>
        <Wordmark width={128} />
      </div>
      <div style={abs({right: 420, top: 58, display: 'flex', gap: 14, direction: 'rtl'})}>
        {tabs.map((t, i) => (
          <div
            key={t}
            style={{
              padding: '10px 28px',
              borderRadius: 999,
              fontSize: 30,
              fontWeight: 600,
              fontFamily: brand.font,
              color: i === active ? (kids ? '#382800' : brand.onPrimary) : brand.muted,
              background: i === active ? (kids ? brand.kids : brand.primary) : 'transparent',
            }}
          >
            {t}
          </div>
        ))}
      </div>
      {kids ? (
        <div
          style={abs({
            left: 90,
            top: 52,
            display: 'flex',
            alignItems: 'center',
            gap: 12,
            padding: '8px 22px 8px 12px',
            borderRadius: 999,
            background: rgba(brand.kids, 0.14),
            color: brand.kids,
            fontSize: 28,
            fontWeight: 700,
            fontFamily: brand.font,
            direction: 'rtl',
          })}
        >
          <Icon name="star" size={34} color={brand.kids} stroke={2.4} />
          {t('ملف الطفل')}
        </div>
      ) : null}
      {search ? (
        <div
          style={abs({
            left: search.x,
            top: search.y,
            width: search.w,
            height: search.h,
            borderRadius: 999,
            background: '#1A222C',
            display: 'grid',
            placeItems: 'center',
          })}
        >
          <Icon name="search" size={search.w * 0.5} color={brand.text} stroke={2.4} />
        </div>
      ) : null}
    </>
  );
};

const PlayerBg: React.FC<{warm?: boolean; dim?: number}> = ({warm, dim = 0}) => (
  <>
    <div
      style={abs({
        inset: 0,
        background: warm
          ? 'radial-gradient(60% 70% at 62% 40%, #7A5A1F 0%, #3A2A10 45%, #140E06 100%)'
          : 'radial-gradient(60% 70% at 62% 38%, #1A6A6E 0%, #0D3446 45%, #050B12 100%)',
      })}
    />
    {[0, 1, 2, 3, 4].map((i) => (
      <div
        key={i}
        style={abs({
          left: 180 + i * 330,
          top: 160 + ((i * 97) % 380),
          width: 120 + ((i * 53) % 140),
          height: 120 + ((i * 53) % 140),
          borderRadius: 999,
          background: warm ? 'radial-gradient(closest-side, rgba(255,209,102,0.22), transparent)' : 'radial-gradient(closest-side, rgba(120,230,220,0.16), transparent)',
        })}
      />
    ))}
    {dim ? <div style={abs({inset: 0, background: `rgba(5,8,11,${dim})`})} /> : null}
  </>
);

const PlayerControls: React.FC<{p?: number; c?: string}> = ({p = 0.35, c = brand.primary}) => (
  <>
    <div style={abs({left: 0, right: 0, bottom: 0, height: 420, background: 'linear-gradient(180deg, transparent, rgba(0,0,0,0.75))'})} />
    <Bar x={112} y={815} w={1696} h={12} r={6} c="rgba(234,240,246,0.18)" />
    <Bar x={112} y={815} w={1696 * p} h={12} r={6} c={c} />
    <div style={abs({left: 112 + 1696 * p - 20, top: 801, width: 40, height: 40, borderRadius: 999, background: '#fff'})} />
    <div style={abs({left: 112, top: 872, width: 120, height: 120, borderRadius: 999, background: 'rgba(234,240,246,0.12)', display: 'grid', placeItems: 'center'})}>
      <Icon name="pause" size={52} color="#fff" />
    </div>
  </>
);

const Heading: React.FC<{title: string; sub?: string; y?: number; icon?: IconName; c?: string}> = ({title, sub, y = 120, icon, c = brand.primary}) => (
  <div style={abs({left: 0, right: 0, top: y, textAlign: 'center', direction: 'rtl', fontFamily: brand.font})}>
    {icon ? (
      <div style={{display: 'flex', justifyContent: 'center', marginBottom: 26}}>
        <div style={{width: 130, height: 130, borderRadius: 38, background: rgba(c, 0.14), display: 'grid', placeItems: 'center'}}>
          <Icon name={icon} size={80} color={c} stroke={2} />
        </div>
      </div>
    ) : null}
    <div style={{fontSize: 64, fontWeight: 700, color: brand.text}}>{title}</div>
    {sub ? <div style={{fontSize: 34, fontWeight: 500, color: brand.muted, marginTop: 12}}>{sub}</div> : null}
  </div>
);

const Toast: React.FC<{r: {x: number; y: number; w: number; h: number}; icon: IconName; text: string; c?: string; action?: string}> = ({r, icon, text, c = brand.primary, action}) => (
  <div
    style={abs({
      left: r.x,
      top: r.y,
      width: r.w,
      height: r.h,
      borderRadius: 26,
      background: 'rgba(17,23,31,0.94)',
      border: `2px solid ${rgba(c, 0.45)}`,
      display: 'flex',
      alignItems: 'center',
      gap: 20,
      padding: '0 26px',
      direction: 'rtl',
      fontFamily: brand.font,
      boxShadow: '0 20px 50px rgba(0,0,0,0.5)',
    })}
  >
    <Icon name={icon} size={44} color={c} stroke={2.2} />
    <div style={{fontSize: 32, fontWeight: 600, color: brand.text, flex: 1}}>{text}</div>
    {action ? (
      <div style={{fontSize: 28, fontWeight: 700, color: c, padding: '6px 18px', borderRadius: 999, background: rgba(c, 0.14)}}>{action}</div>
    ) : null}
  </div>
);

// ---------------------------------------------------------------------------------------------------------

const renderTv = (name: string, kind: string, title: string) => {
  switch (kind) {
    case 'home': {
      const s = R(name, 'search');
      const times = [t('منذ ساعتين'), t('منذ 5 ساعات'), t('أمس'), t('منذ يومين')];
      const chans = [t('عالم الطبيعة'), t('مطبخ سلمى'), t('تقنية ببساطة'), t('رحلات هادئة')];
      const fc = R(name, 'firstCard');
      const rows = [fc.y, R(name, 'rows', 1).y];
      return (
        <>
          <TopBar search={s} />
          {rows.map((ry, ri) => (
            <React.Fragment key={ri}>
              <T xr={1830} y={ry - 64} size={34}>
                {ri === 0 ? t('أحدث الفيديوهات') : t('من قنواتك')}
              </T>
              {[0, 1, 2, 3].map((ci) => {
                const x = fc.x - ci * (fc.w + 36);
                return (
                  <React.Fragment key={ci}>
                    <Thumb x={x} y={ry} w={fc.w} h={fc.h} i={ri * 4 + ci} dur={['12:34', '8:05', '21:40', '5:12'][ci]} />
                    <Bar x={x + 40} y={ry + fc.h + 18} w={fc.w - 40} h={22} r={8} c="#222C38" />
                    <T xr={x + fc.w} y={ry + fc.h + 50} size={24} c={brand.muted} weight={500}>
                      {chans[(ci + ri) % 4]} · {times[(ci + ri * 2) % 4]}
                    </T>
                  </React.Fragment>
                );
              })}
            </React.Fragment>
          ))}
        </>
      );
    }
    case 'search': {
      const q = R(name, 'query');
      const fr_ = R(name, 'firstResult');
      return (
        <>
          <div
            style={abs({
              left: q.x,
              top: q.y,
              width: q.w,
              height: q.h,
              borderRadius: 999,
              background: '#1A222C',
              boxShadow: `inset 0 0 0 2px ${rgba(brand.primary, 0.5)}`,
              display: 'flex',
              alignItems: 'center',
              gap: 20,
              padding: '0 32px',
              direction: 'rtl',
              fontFamily: brand.font,
            })}
          >
            <Icon name="search" size={44} color={brand.primary} stroke={2.4} />
            <span style={{fontSize: 38, fontWeight: 600, color: brand.text}}>{t('وثائقي أعماق المحيط')}</span>
            <span style={{width: 3, height: 44, background: brand.primary}} />
          </div>
          <div style={abs({left: 90, top: 70, fontSize: 28, color: brand.muted, fontFamily: brand.font})}>{t('ملف الكبار')}</div>
          {[0, 1].map((ri) =>
            [0, 1, 2, 3].map((ci) => {
              const x = fr_.x - ci * (fr_.w + 36);
              const y = fr_.y + ri * 360;
              return (
                <React.Fragment key={`${ri}${ci}`}>
                  <Thumb x={x} y={y} w={fr_.w} h={fr_.h} i={ri * 4 + ci + 2} dur={['18:02', '9:41', '44:10', '3:27'][ci]} />
                  <Bar x={x + 30} y={y + fr_.h + 18} w={fr_.w - 30} h={22} r={8} c="#222C38" />
                  <Bar x={x + 150} y={y + fr_.h + 54} w={fr_.w - 150} h={18} r={8} c="#1B232D" />
                </React.Fragment>
              );
            }),
          )}
        </>
      );
    }
    case 'player':
      return (
        <>
          <PlayerBg />
          <PlayerControls />
        </>
      );
    case 'resume': {
      const d = R(name, 'dialog');
      const r = R(name, 'resume');
      return (
        <>
          <PlayerBg dim={0.55} />
          <div style={abs({left: d.x, top: d.y, width: d.w, height: d.h, borderRadius: 34, background: '#151B24', border: '1.5px solid rgba(234,240,246,0.08)', boxShadow: '0 40px 90px rgba(0,0,0,0.6)'})} />
          <T xr={d.x + d.w - 60} y={d.y + 56} size={44}>
            {t('متابعة المشاهدة؟')}
          </T>
          <T xr={d.x + d.w - 60} y={d.y + 130} size={30} c={brand.muted} weight={500}>
            {t('توقفت عند 12:34 من 21:40')}
          </T>
          <Bar x={d.x + 60} y={d.y + 200} w={d.w - 120} h={10} r={5} c="rgba(234,240,246,0.14)" />
          <Bar x={d.x + 60 + (d.w - 120) * 0.43} y={d.y + 200} w={(d.w - 120) * 0.57} h={10} r={5} c={brand.primary} />
          <Btn x={r.x} y={r.y} w={r.w} h={r.h} label={t('استكمال من 12:34')} primary size={28} />
          <Btn x={d.x + 60} y={r.y} w={r.x - d.x - 90} h={r.h} label={t('من البداية')} size={28} />
        </>
      );
    }
    case 'toast': {
      return (
        <>
          <PlayerBg />
          <PlayerControls p={0.52} />
          <Toast r={R(name, 'toast')} icon="skip" text={t('تم تخطي فقرة رعاية')} action={t('تراجع')} />
        </>
      );
    }
    case 'warning':
      return (
        <>
          <PlayerBg />
          <Toast r={R(name, 'toast')} icon="hourglass" text={t('بقيت 5 دقائق من حصة اليوم')} c={brand.amber} />
        </>
      );
    case 'kidsplayer': {
      const l = R(name, 'lockHint');
      return (
        <>
          <PlayerBg warm />
          <PlayerControls p={0.3} c={brand.kids} />
          <div
            style={abs({
              left: l.x,
              top: l.y,
              width: l.w,
              height: l.h,
              borderRadius: 999,
              background: 'rgba(11,15,20,0.72)',
              color: brand.kids,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 12,
              fontSize: 28,
              fontWeight: 700,
              fontFamily: brand.font,
              direction: 'rtl',
            })}
          >
            <Icon name="lock" size={32} color={brand.kids} stroke={2.4} />
            {t('الفيديو مقفل')}
          </div>
        </>
      );
    }
    case 'hold': {
      const r = R(name, 'ring');
      return (
        <>
          <PlayerBg dim={0.6} />
          <div style={abs({left: r.x, top: r.y, width: r.w, height: r.h, borderRadius: 999, border: '14px solid rgba(234,240,246,0.12)'})} />
          <div style={abs({left: r.x, top: r.y + r.h / 2 - 50, width: r.w, textAlign: 'center', fontSize: 80, fontWeight: 700, color: brand.text, fontFamily: brand.font})}>10</div>
          <Heading title={t('اضغط مطوّلاً للخروج')} sub={t('الخروج قبل نهاية الفيديو يحتاج صبراً')} y={560} />
        </>
      );
    }
    case 'phrase': {
      const ph = R(name, 'phrase');
      const inp = R(name, 'input');
      const letters = t('ضصثقفغعهخحجدشسيبلاتنمكطئءؤرلاىةوزظ').split('');
      return (
        <>
          <PlayerBg dim={0.72} />
          <T xr={ph.x + ph.w} y={ph.y - 90} size={40} c={brand.muted} weight={500}>
            {t('اكتب الجملة للخروج')}
          </T>
          <div style={abs({left: ph.x, top: ph.y, width: ph.w, height: ph.h, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 50, fontWeight: 700, color: brand.amber, fontFamily: brand.font, direction: 'rtl'})}>
            {t('أختار أن أتوقف الآن')}
          </div>
          <div style={abs({left: inp.x, top: inp.y, width: inp.w, height: inp.h, borderRadius: 22, background: '#1A222C', boxShadow: `inset 0 0 0 2px ${rgba(brand.primary, 0.5)}`})} />
          {Array.from({length: 33}).map((_, i) => {
            const row = Math.floor(i / 11);
            const col = i % 11;
            return (
              <div
                key={i}
                style={abs({
                  left: 1300 - col * 100,
                  top: 600 + row * 100,
                  width: 80,
                  height: 80,
                  borderRadius: 18,
                  background: '#1A222C',
                  color: brand.text,
                  display: 'grid',
                  placeItems: 'center',
                  fontSize: 38,
                  fontWeight: 600,
                  fontFamily: brand.font,
                })}
              >
                {letters[i % letters.length]}
              </div>
            );
          })}
        </>
      );
    }
    case 'guard':
      return (
        <>
          <div style={abs({inset: 0, background: 'radial-gradient(60% 60% at 50% 45%, #0F2A2A, #06090C 70%)'})} />
          <Heading title={t('«شاشة» تعيدك الآن')} sub={t('القفل مفعّل — لا يمكن فتح تطبيق آخر')} icon="shieldCheck" y={290} />
        </>
      );
    case 'status': {
      const rows: [string, string][] = [
        ['overlay', t('العرض فوق التطبيقات')],
        ['usage', t('الوصول إلى بيانات الاستخدام')],
        ['install', t('تثبيت التحديثات')],
      ];
      return (
        <>
          <T xr={1720} y={120} size={60} weight={700}>
            {t('حالة الحماية')}
          </T>
          <T xr={1720} y={205} size={30} c={brand.muted} weight={500}>
            {t('صلاحيات يمنحها سكربت ADB مرة واحدة')}
          </T>
          {rows.map(([k, label]) => {
            const r = R(name, k);
            return (
              <React.Fragment key={k}>
                <div style={abs({left: r.x, top: r.y, width: r.w, height: r.h, borderRadius: 24, background: '#151B24', border: '1.5px solid rgba(234,240,246,0.07)'})} />
                <T xr={r.x + r.w - 110} y={r.y + r.h / 2 - 24} size={36}>
                  {label}
                </T>
                <div style={abs({left: r.x + r.w - 88, top: r.y + r.h / 2 - 28, width: 56, height: 56, borderRadius: 999, background: rgba(brand.primary, 0.16), display: 'grid', placeItems: 'center'})}>
                  <Icon name="check" size={36} color={brand.primary} stroke={3} />
                </div>
                <div style={abs({left: r.x + 36, top: r.y + r.h / 2 - 22, fontSize: 30, fontWeight: 700, color: brand.primary, fontFamily: brand.font})}>{t('ممنوحة')}</div>
              </React.Fragment>
            );
          })}
        </>
      );
    }
    case 'qr': {
      const q = R(name, 'qr');
      return (
        <>
          <div style={abs({left: q.x, top: q.y, width: q.w, height: q.h, borderRadius: 40, background: '#EAF0F6', padding: 26, boxSizing: 'border-box'})}>
            <QrCode size={q.w - 52} bg="#EAF0F6" quiet={1} />
          </div>
          <T xr={1740} y={250} size={64} weight={700}>
            {t('امسح الرمز بجوالك')}
          </T>
          <T xr={1740} y={350} size={34} c={brand.muted} weight={500}>
            {t('افتح الكاميرا ووجّهها إلى الرمز، ثم افتح الرابط.')}
          </T>
          <div style={abs({right: W - 1740, top: 440, fontFamily: brand.mono, fontSize: 36, color: brand.primary, direction: 'ltr'})}>{`http://${TV_IP}:${PAIR_PORT}`}</div>
          <div style={abs({right: W - 1740, top: 540, display: 'flex', gap: 14, direction: 'rtl'})}>
            {[t('بحث'), t('تحكم'), t('إرسال رابط'), t('قسم الأب')].map((label) => (
              <div key={label} style={{padding: '10px 24px', borderRadius: 999, background: '#1A222C', fontSize: 28, fontWeight: 600, color: brand.text, fontFamily: brand.font}}>
                {label}
              </div>
            ))}
          </div>
        </>
      );
    }
    case 'stats': {
      const ch = R(name, 'chart');
      const cl = R(name, 'channels');
      const cp = R(name, 'compare');
      const a = [42, 55, 38, 61, 47, 70, 52];
      const k = [30, 25, 40, 28, 35, 45, 20];
      return (
        <>
          <T xr={1830} y={70} size={56} weight={700}>
            {t('الإحصائيات · هذا الأسبوع')}
          </T>
          <div style={abs({left: ch.x, top: ch.y, width: ch.w, height: ch.h, borderRadius: 30, background: '#151B24'})} />
          {a.map((v, i) => {
            const bx = ch.x + ch.w - 110 - i * ((ch.w - 120) / 7);
            return (
              <React.Fragment key={i}>
                <Bar x={bx} y={ch.y + ch.h - 70 - v * 5.2} w={34} h={v * 5.2} r={10} c={brand.primary} />
                <Bar x={bx + 40} y={ch.y + ch.h - 70 - k[i] * 5.2} w={34} h={k[i] * 5.2} r={10} c={brand.kids} />
              </React.Fragment>
            );
          })}
          <div style={abs({left: cl.x, top: cl.y, width: cl.w, height: cl.h, borderRadius: 30, background: '#151B24'})} />
          <T xr={cl.x + cl.w - 36} y={cl.y + 30} size={32}>
            {t('أكثر القنوات')}
          </T>
          {[t('عالم الطبيعة'), t('مطبخ سلمى'), t('تقنية ببساطة'), t('رحلات هادئة')].map((c, i) => (
            <React.Fragment key={c}>
              <div style={abs({left: cl.x + cl.w - 36 - 56, top: cl.y + 110 + i * 100, width: 56, height: 56, borderRadius: 999, background: `linear-gradient(135deg, ${THUMBS[i][0]}, ${THUMBS[i][1]})`})} />
              <T xr={cl.x + cl.w - 110} y={cl.y + 116 + i * 100} size={30} weight={500}>
                {c}
              </T>
              <div style={abs({left: cl.x + 36, top: cl.y + 118 + i * 100, fontSize: 28, color: brand.muted, fontFamily: brand.font, direction: 'rtl'})}>{t('{n} د', {n: [124, 96, 61, 40][i]})}</div>
            </React.Fragment>
          ))}
          <div style={abs({left: cp.x, top: cp.y, width: cp.w, height: cp.h, borderRadius: 999, background: rgba(brand.primary, 0.14), color: brand.primary, display: 'grid', placeItems: 'center', fontSize: 32, fontWeight: 700, fontFamily: brand.font, direction: 'rtl'})}>
            <span><span style={{unicodeBidi: 'isolate', direction: 'ltr'}}>−12%</span> {t('عن الأسبوع الماضي')}</span>
          </div>
        </>
      );
    }
    case 'pin': {
      const d = R(name, 'dots');
      const hasKeypad = !!screenInfo(name).focus?.keypad;
      return (
        <>
          <Heading title={title.replace(t('أول تشغيل: '), '')} sub={hasKeypad ? t('ملف الكبار') : t('أربعة أرقام تحمي الإعدادات والملفات')} y={140} />
          <Dots x={d.x} y={d.y} w={d.w} h={d.h} filled={2} />
          <Keypad x={760} y={520} w={400} h={480} />
        </>
      );
    }
    case 'profiles': {
      const ad = R(name, 'adult');
      const ch = R(name, 'child');
      const setup = name === 'profiles_setup';
      const P = (r: typeof ad, c: string, label: string, icon: IconName) => (
        <>
          <div style={abs({left: r.x + r.w / 2 - 125, top: r.y + 20, width: 250, height: 250, borderRadius: 999, background: rgba(c, 0.14), border: `5px solid ${c}`, display: 'grid', placeItems: 'center'})}>
            <Icon name={icon} size={120} color={c} stroke={2} />
          </div>
          <div style={abs({left: r.x, top: r.y + 300, width: r.w, textAlign: 'center', fontSize: 40, fontWeight: 700, color: brand.text, fontFamily: brand.font})}>{label}</div>
        </>
      );
      return (
        <>
          <Heading title={setup ? t('أنشئ الملفات') : t('من يشاهد؟')} sub={setup ? t('ملف للكبار برمز سري، وملف لكل طفل') : undefined} y={100} />
          {P(ad, brand.primary, t('الكبار'), 'lock')}
          {P(ch, brand.kids, t('الطفل'), 'star')}
        </>
      );
    }
    case 'kids': {
      const fc = R(name, 'firstCard');
      return (
        <>
          <div style={abs({inset: 0, background: 'radial-gradient(80% 60% at 70% 0%, rgba(255,209,102,0.10), transparent 70%)'})} />
          <TopBar kids search={null} />
          {[0, 1].map((ri) =>
            [0, 1, 2].map((ci) => {
              const x = fc.x - ci * (fc.w + 40);
              const y = fc.y + ri * (fc.h + 80);
              return (
                <React.Fragment key={`${ri}${ci}`}>
                  <div style={abs({left: x - 6, top: y - 6, width: fc.w + 12, height: fc.h + 12, borderRadius: 34, background: rgba(brand.kids, ri === 0 && ci === 0 ? 0.0 : 0)})} />
                  <Thumb x={x} y={y} w={fc.w} h={fc.h} i={[2, 7, 5, 4, 2, 0][ri * 3 + ci]} r={28} />
                  <Bar x={x + fc.w - 260} y={y + fc.h + 18} w={260} h={24} r={10} c="rgba(255,209,102,0.22)" />
                </React.Fragment>
              );
            }),
          )}
        </>
      );
    }
    case 'settings': {
      const m = R(name, 'minutes');
      const s = R(name, 'schedule');
      const cats = [t('الملفات'), t('الحصص والجداول'), t('قوائم السماح'), 'SponsorBlock', t('حالة الحماية'), t('التحديثات'), t('حول')];
      return (
        <>
          <div style={abs({left: 1300, top: 0, width: 620, height: 1080, background: '#10151C'})} />
          {cats.map((c, i) => (
            <div
              key={c}
              style={abs({
                right: 60,
                top: 160 + i * 104,
                width: 500,
                height: 84,
                borderRadius: 20,
                background: i === 1 ? rgba(brand.primary, 0.14) : 'transparent',
                color: i === 1 ? brand.primary : brand.muted,
                fontSize: 32,
                fontWeight: 600,
                fontFamily: brand.font,
                direction: 'rtl',
                display: 'flex',
                alignItems: 'center',
                padding: '0 30px',
                boxSizing: 'border-box',
              })}
            >
              {c}
            </div>
          ))}
          <T xr={1210} y={110} size={52} weight={700}>
            {t('الحصص والجداول')}
          </T>
          {[
            [m, t('الحصة اليومية · الكبار'), t('60 دقيقة')],
            [s, t('الأوقات المسموحة'), '07:00 – 22:00'],
          ].map(([r, label, val], i) => {
            const rr = r as typeof m;
            return (
              <React.Fragment key={i}>
                <div style={abs({left: rr.x, top: rr.y, width: rr.w, height: rr.h, borderRadius: 24, background: '#151B24'})} />
                <T xr={rr.x + rr.w - 36} y={rr.y + rr.h / 2 - 24} size={34}>
                  {label as string}
                </T>
                <div style={abs({left: rr.x + 36, top: rr.y + rr.h / 2 - 26, fontSize: 36, fontWeight: 700, color: brand.primary, fontFamily: brand.font, direction: 'ltr'})}>{val as string}</div>
              </React.Fragment>
            );
          })}
          <div style={abs({left: m.x, top: 560, width: m.w, height: 150, borderRadius: 24, background: '#151B24'})} />
          <T xr={m.x + m.w - 36} y={582} size={30} c={brand.muted} weight={500}>
            {t('ملف الطفل')}
          </T>
          <div style={abs({left: m.x + 36, top: 582, fontSize: 32, fontWeight: 700, color: brand.kids, fontFamily: brand.font, direction: 'ltr'})}>{t('45 دقيقة')}</div>
          <Bar x={m.x + 36} y={650} w={m.w - 72} h={14} r={7} c="rgba(234,240,246,0.1)" />
          <Bar x={m.x + 36 + (m.w - 72) * 0.25} y={650} w={(m.w - 72) * 0.75} h={14} r={7} c={brand.kids} />
        </>
      );
    }
    case 'timeup': {
      const p = R(name, 'pin');
      return (
        <>
          <div style={abs({inset: 0, background: 'radial-gradient(60% 60% at 50% 35%, rgba(255,181,71,0.10), transparent 70%)'})} />
          <Heading title={t('انتهى الوقت')} sub={t('انتهت حصة المشاهدة لهذا اليوم. نلتقي غداً.')} icon="hourglass" c={brand.amber} y={120} />
          <Dots x={p.x} y={p.y} w={p.w} h={p.h} filled={0} c={brand.amber} />
          <div style={abs({left: 0, right: 0, top: p.y + p.h + 40, textAlign: 'center', fontSize: 30, color: brand.muted, fontFamily: brand.font, direction: 'rtl'})}>{t('أدخل الرمز للفتح، أو افتح من الجوال')}</div>
        </>
      );
    }
    case 'update': {
      // (no capture yet: the in-app updater is still a stub) — laid out on the manifest's release/progress rects
      const r = R(name, 'release');
      const pg = R(name, 'progress');
      return (
        <>
          <Heading title={t('يتوفر إصدار جديد')} sub={t('من GitHub Releases')} icon="download" y={40} />
          <div style={abs({left: r.x, top: r.y, width: r.w, height: r.h, borderRadius: 28, background: '#151B24', border: '1.5px solid rgba(234,240,246,0.08)'})} />
          <div style={abs({left: r.x + 40, top: r.y + 34, fontFamily: brand.mono, fontSize: 44, color: brand.text, direction: 'ltr'})}>{RELEASE_TAG}</div>
          <div style={abs({left: r.x + 40, top: r.y + 104, fontFamily: brand.mono, fontSize: 28, color: brand.muted, direction: 'ltr'})}>{`${APK_NAME} · ${APK_SIZE}`}</div>
          <Bar x={pg.x} y={pg.y + pg.h / 2 - 8} w={pg.w} h={16} r={8} c="rgba(234,240,246,0.1)" />
          <Bar x={pg.x + pg.w * 0.36} y={pg.y + pg.h / 2 - 8} w={pg.w * 0.64} h={16} r={8} c={brand.primary} />
          <div style={abs({left: 0, right: 0, top: pg.y + pg.h + 30, textAlign: 'center', fontSize: 30, color: brand.muted, fontFamily: brand.font, direction: 'rtl'})}>{t('جارٍ التنزيل… 64٪')}</div>
        </>
      );
    }
    case 'error':
      return (
        <>
          <Heading title={t('يوتيوب غيّر شيئاً. حدّث التطبيق.')} sub={t('تعذّر تشغيل الفيديو.')} icon="warning" c={brand.error} y={150} />
          <Btn x={633} y={880} w={386} h={104} label={t('فحص التحديثات')} icon="download" />
          <Btn x={1049} y={877} w={247} h={110} label={t('رجوع')} primary />
        </>
      );
    case 'menu':
      return (
        <>
          <PlayerBg dim={0.5} />
          <div style={abs({left: 1130, top: 225, width: 677, height: 757, borderRadius: 40, background: '#131920'})} />
        </>
      );
    default:
      return null;
  }
};

// Phone pages are real vector mocks (see PhonePages.tsx); the phone placeholder is only a fallback.
const renderPhone = (title: string) => (
  <div style={abs({inset: 0, display: 'grid', placeItems: 'center', background: '#0B0F14'})}>
    <div style={{fontSize: 22, color: brand.muted, fontFamily: brand.font}}>{title}</div>
  </div>
);

export const Placeholder: React.FC<{name: string; phase?: number}> = ({name}) => {
  const info = screenInfo(name);
  if (info.device === 'phone') return renderPhone(info.title);
  return (
    <div
      style={{
        position: 'absolute',
        left: 0,
        top: 0,
        width: W,
        height: H,
        overflow: 'hidden',
        background: 'radial-gradient(90% 80% at 70% 0%, #121A24 0%, #0B0F14 60%)',
        fontFamily: brand.font,
      }}
    >
      {renderTv(name, info.kind, info.title)}
      {/* discreet tag: this is a placeholder for the real capture */}
      <div
        style={abs({
          left: 40,
          bottom: 34,
          display: 'flex',
          alignItems: 'center',
          gap: 14,
          padding: '10px 22px',
          borderRadius: 999,
          background: 'rgba(11,15,20,0.78)',
          border: '1.5px dashed rgba(234,240,246,0.22)',
          direction: 'rtl',
        })}
      >
        <Icon name="tv" size={30} color={brand.muted} stroke={2} />
        <span style={{fontSize: 26, fontWeight: 600, color: brand.text}}>{info.title}</span>
        <span style={{fontSize: 22, fontFamily: brand.mono, color: brand.muted, direction: 'ltr'}}>{info.file}</span>
      </div>
    </div>
  );
};

export {RP};
